package shop

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ScaleAndIncidentTest {
    private fun newApp(allocation: Int = 100): App {
        val app = App()
        app.db.tx { Seed.resetCampaign(it, allocation, 30, 3600) }
        app.inv.resetCaches()
        return app
    }
    private fun App.active(id: Long = 1) = db.conn { c ->
        c.queryOne("SELECT COUNT(*) FROM orders WHERE campaign_id = ? AND status IN ('PENDING_PAYMENT','PAID','FULFILLED')", id) { it.getInt(1) }!!
    }

    // ---------- Waiting room ----------
    @Test fun `waiting room hanya meloloskan 5x alokasi dan membuka slot saat reservasi dilepas`() {
        val app = newApp(10)                                 // limit awal = 50
        val admitted = (1L..200L).count { app.inv.admitted(it, 1) }
        assertEquals(50, admitted)
        assertEquals(1, app.inv.queuePosition(51, 1))
        assertEquals(150, (51L..200L).count { app.inv.queuePosition(it, 1) > 0 })

        val o = (app.inv.purchase(1, 1, "k") as Outcome.Ok).order
        app.db.conn { it.exec("UPDATE orders SET reserved_until = ${Sql.dateAdd("SECOND", "-1")} WHERE id = ?", o.id) }
        app.inv.expireDue()                                  // satu reservasi dilepas -> +5 slot
        assertEquals(5, (51L..200L).count { app.inv.admitted(it, 1) })
    }

    @Test fun `beban 250000 dengan waiting room membuat hanya sebagian kecil menyentuh database`() = runBlocking {
        val app = newApp(100)
        val r = app.load.run("safe", 250_000, 100, admission = true).jsonObject
        println("admission: ${r.toString().take(400)}")
        assertEquals(100, r["active_orders_or_va"]!!.jsonPrimitive.int)
        assertEquals(0, r["oversell"]!!.jsonPrimitive.int)
        assertEquals(249_500, r["queued_without_db"]!!.jsonPrimitive.int)   // hanya 500 (=5x100) yang menyentuh DB
    }

    // ---------- Harga normal 250 unit gudang ----------
    @Test fun `250 unit gudang dijual harga normal dan tidak bisa minus saat 300 pembeli paralel`() = runBlocking {
        val app = newApp()
        val ok = AtomicInteger(); val out = AtomicInteger()
        (1L..300L).map { u ->
            async(Dispatchers.IO) {
                app.shop.ensureUser(u); app.shop.topup(u, 1_000_000)
                try { app.shop.checkout(u, "w$u", listOf(100L to 1), "REG"); ok.incrementAndGet() }
                catch (e: OutOfStock) { out.incrementAndGet() }
            }
        }.awaitAll()
        assertEquals(250, ok.get()); assertEquals(50, out.get())
        assertEquals(0, app.db.conn { c -> c.queryOne("SELECT stock FROM products WHERE id = 100") { it.getInt(1) } })
        val unit = app.db.conn { c -> c.queryOne("SELECT DISTINCT unit_price FROM shop_order_items WHERE product_id = 100") { it.getLong(1) } }
        assertEquals(1_000_000L, unit)                       // harga kembali normal
    }

    // ---------- Gateway tidak stabil + HTTP ----------
    @Test fun `retry key sama setelah VA gagal membuat reservasi baru`() {
        val app = newApp()
        app.gateway.mode = GatewayMode.FAIL
        assertIs<Outcome.PaymentUnavailable>(app.inv.purchase(5, 1, "kunci"))
        app.gateway.mode = GatewayMode.OK
        val r = app.inv.purchase(5, 1, "kunci")
        assertIs<Outcome.Ok>(r)
        assertEquals("PENDING_PAYMENT", r.order.status)
        assertEquals(1, app.active())
    }

    @Test fun `3000 pembeli lewat HTTP dengan gateway gagal 30 persen tetap tepat 100 order dan satu VA per order`() {
        val app = newApp(100)
        app.gateway.mode = GatewayMode.FLAKY; app.gateway.failRate = 0.3
        val server = embeddedServer(Netty, port = 0) { module(app) }.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        val http = HttpClient.newHttpClient()
        val pool = Executors.newFixedThreadPool(200)
        val created = AtomicInteger(); val soldOut = AtomicInteger(); val requests = AtomicInteger()
        val t0 = System.nanoTime()
        try {
            val futures = (1L..3000L).map { u ->
                pool.submit {
                    repeat(40) {
                        val req = HttpRequest.newBuilder(URI("http://localhost:$port/campaigns/1/purchase"))
                            .header("Authorization", "Bearer demo-$u").header("Idempotency-Key", "k$u")
                            .POST(HttpRequest.BodyPublishers.ofString("""{"quantity":1}""")).build()
                        requests.incrementAndGet()
                        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                        when (resp.statusCode()) {
                            201 -> { created.incrementAndGet(); return@submit }
                            200, 409 -> { if (resp.statusCode() == 409) soldOut.incrementAndGet(); return@submit }
                            else -> Thread.sleep(30)           // 202 antre, 429, 503: coba lagi dengan key yang sama
                        }
                    }
                }
            }
            futures.forEach { it.get() }
        } finally { pool.shutdown(); server.stop(100, 500) }
        val secs = (System.nanoTime() - t0) / 1e9
        println("http load: ${requests.get()} request dalam %.1f dtk (%.0f req/dtk), 201=${created.get()}, 409=${soldOut.get()}".format(secs, requests.get() / secs))

        assertEquals(100, app.active())
        assertEquals(emptyList(), app.inv.invariantViolations())
        val vas = app.db.conn { c -> c.queryOne("SELECT COUNT(*) FROM payment_va") { it.getInt(1) } }
        assertEquals(100, vas)                                // satu VA per order aktif, tanpa VA ganda
    }

    // ---------- Alert ke webhook ----------
    @Test fun `monitor mengirim alert ke webhook saat invariant dilanggar`() {
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        val hook = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        hook.createContext("/hook") { ex -> got.add(ex.requestBody.readBytes().decodeToString()); ex.sendResponseHeaders(200, -1); ex.close() }
        hook.start()
        try {
            val app = newApp()
            app.db.conn { it.exec("UPDATE campaign SET reserved = 3 WHERE id = 1") }
            val alerter = WebhookAlerter("http://localhost:${hook.address.port}/hook")
            Workers(app.inv, app.db, CoroutineScope(Job()), alerter).monitorOnce()
            val body = got.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(body, "webhook tidak menerima alert")
            assertTrue(body.contains("Invariant dilanggar"))
            assertEquals(Outcome.NotActive, app.inv.purchase(1, 1, "k"))   // dan kampanye dijeda
        } finally { hook.stop(0) }
    }

    // ---------- Insiden lintas penjual & kompensasi ----------
    private suspend fun seedIncident(app: App, campaigns: Int, users: Int) {
        app.db.tx { c -> for (i in 2L..(campaigns + 1L)) Seed.createCampaign(c, i, "Penjual $i", 100, 30, 3600) }
        for (id in 2L..(campaigns + 1L)) app.load.run("legacy", users, 100, campaignId = id)
        app.db.conn { c -> c.exec("UPDATE legacy_va SET paid = (MOD(seq, 5) <> 0)") }
    }

    @Test fun `eksposur lintas penjual, rencana A B C, dan eksekusi idempoten`() = runBlocking {
        val app = newApp()
        seedIncident(app, campaigns = 5, users = 600)
        val e = app.incident.exposure()
        assertEquals(5, e["campaigns_affected"]!!.jsonPrimitive.int)
        val excess = e["total_excess"]!!.jsonPrimitive.long
        assertTrue(excess > 0)
        assertEquals(excess * 500_000, e["worst_case_loss_if_fulfilled"]!!.jsonPrimitive.long)

        val a = app.incident.plan(2, "A")!!; val b = app.incident.plan(2, "B")!!; val c = app.incident.plan(2, "C", sellerPct = 50)!!
        val paidExcess = a["paid_excess"]!!.jsonPrimitive.long
        assertEquals(paidExcess * 500_000, a["platform_subsidy_total"]!!.jsonPrimitive.long)
        assertEquals(paidExcess, a["fulfilled_with_subsidy"]!!.jsonPrimitive.long)
        assertEquals(paidExcess * 500_000, b["refund_total"]!!.jsonPrimitive.long)
        assertEquals(paidExcess * 50_000, b["voucher_total"]!!.jsonPrimitive.long)
        assertEquals(0L, b["platform_subsidy_total"]!!.jsonPrimitive.long)
        assertEquals((paidExcess + 1) / 2, c["fulfilled_with_subsidy"]!!.jsonPrimitive.long)
        assertEquals(paidExcess, c["fulfilled_with_subsidy"]!!.jsonPrimitive.long + c["refunded"]!!.jsonPrimitive.long)

        val first = app.incident.execute(2, "B")!!["compensation_rows_written"]!!.jsonPrimitive.int
        assertTrue(first > 0)
        assertEquals(0, app.incident.execute(2, "B")!!["compensation_rows_written"]!!.jsonPrimitive.int)   // idempoten
        assertEquals(first, app.db.conn { c2 -> c2.queryOne("SELECT COUNT(*) FROM compensations WHERE campaign_id = 2") { it.getInt(1) } })
    }

    @Test fun `eksekusi kompensasi pada order di atas alokasi memvoid order dan memperbaiki counter`() {
        val app = newApp()
        app.db.tx { Seed.resetCampaign(it, 2, 30, 3600) }
        app.inv.purchase(1, 1, "a"); app.inv.purchase(2, 1, "b")
        app.db.conn { c ->
            for (u in 3L..6L) c.exec(
                "INSERT INTO orders (order_no, user_id, campaign_id, status, unit_price, idempotency_key, reserved_until) VALUES (?,?,1,?,500000,?,${Sql.dateAdd("HOUR", "1")})",
                "Y-$u", u, if (u % 2 == 0L) "PAID" else "PENDING_PAYMENT", "y$u",
            )
        }
        assertEquals(6, app.active())
        val r = app.incident.execute(1, "B")!!
        assertEquals(4L, r["excess"]!!.jsonPrimitive.long)
        assertEquals(2, app.active())
        assertEquals(emptyList(), app.inv.invariantViolations())
    }

    @Test fun `pembekuan massal menjeda semua kampanye dan membatalkan VA belum dibayar di gateway`() {
        val app = newApp()
        app.db.tx { c -> for (i in 2L..4L) Seed.createCampaign(c, i, "P$i", 100, 30, 3600) }
        val pending = (1L..3L).map { (app.inv.purchase(it, 1, "k$it") as Outcome.Ok).order }
        val r = app.incident.freezeAll("ops", "uji")
        assertEquals(4L, r["campaigns_paused"]!!.jsonPrimitive.long)
        assertEquals(3L, r["pending_va_cancelled_at_gateway"]!!.jsonPrimitive.long)
        assertNull(app.gateway.pay(pending.first().id.toString()))           // VA dikunci: pembayaran baru tidak diterima
        assertEquals(Outcome.NotActive, app.inv.purchase(99, 1, "x"))
    }
}
