package shop

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class InventoryTest {
    private fun newApp(allocation: Int = 100, paymentWindowSec: Int = 3600): App {
        val app = App()
        app.db.tx { Seed.resetCampaign(it, allocation, 30, paymentWindowSec) }
        app.inv.resetCaches()
        return app
    }

    private fun App.active() = db.conn { c ->
        c.queryOne("SELECT COUNT(*) FROM orders WHERE status IN ('PENDING_PAYMENT','PAID','FULFILLED')") { it.getInt(1) }!!
    }
    private fun App.counters() = db.conn { c ->
        c.queryOne("SELECT reserved, sold FROM campaign WHERE id = 1") { it.getInt(1) to it.getInt(2) }!!
    }
    private fun App.expireNow(orderId: Long) = db.conn { c ->
        c.exec("UPDATE orders SET reserved_until = ${Sql.dateAdd("SECOND", "-1")} WHERE id = ?", orderId)
    }
    private fun App.buy(user: Long, key: String = "k$user") = inv.purchase(user, 1, key)

    // INV-01/02/11: tepat 100 order aktif dari ribuan pembeli paralel
    @Test fun `5000 pembeli paralel pada alokasi 100 menghasilkan tepat 100 order aktif`() = runBlocking {
        val app = newApp(100)
        val created = AtomicInteger(); val soldOut = AtomicInteger()
        (1..5000).map { u ->
            async(Dispatchers.IO) {
                when (val r = app.buy(u.toLong())) {
                    is Outcome.Ok -> created.incrementAndGet()
                    Outcome.SoldOut -> soldOut.incrementAndGet()
                    else -> error("tak terduga: $r")
                }
            }
        }.awaitAll()
        assertEquals(100, created.get())
        assertEquals(4900, soldOut.get())
        assertEquals(100, app.active())
        assertEquals(100 to 0, app.counters())
        assertEquals(emptyList(), app.inv.invariantViolations())
    }

    // Bukti insiden: alur lama membiarkan oversell
    @Test fun `alur lama (baca lalu tulis) menerbitkan VA melebihi alokasi`() = runBlocking {
        val app = newApp(100)
        val issued = AtomicInteger()
        (1..2000).map { u ->
            async(Dispatchers.IO) { if (app.legacy.purchase(u.toLong(), 1) == Legacy.Result.VaIssued) issued.incrementAndGet() }
        }.awaitAll()
        println("legacy: VA terbit=${issued.get()} dari alokasi 100, sisa stok di DB=${app.legacy.stockLeft(1)}")
        assertTrue(issued.get() > 100, "alur lama seharusnya oversell, tetapi hanya ${issued.get()} VA")
        assertEquals(issued.get(), app.legacy.issued(1))
    }

    // INV-05
    @Test fun `idempotency key sama mengembalikan order yang sama`() {
        val app = newApp()
        val a = app.buy(7, "abc") as Outcome.Ok
        val b = app.buy(7, "abc") as Outcome.Ok
        assertFalse(a.replay); assertTrue(b.replay)
        assertEquals(a.order.id, b.order.id)
        assertEquals(a.order.vaNumber, b.order.vaNumber)
        assertEquals(1, app.active())
        assertEquals(1 to 0, app.counters())
    }

    // INV-08
    @Test fun `VA kedaluwarsa bersamaan dengan reservasi`() {
        val app = newApp(paymentWindowSec = 900)
        val o = (app.buy(1) as Outcome.Ok).order
        assertEquals(o.reservedUntil, app.gateway.lookup(o.id.toString())!!.expiresAt)
        val secs = java.time.Duration.between(o.reservedAt, o.reservedUntil).seconds
        assertEquals(900, secs)
    }

    // INV-04
    @Test fun `satu pelanggan hanya boleh satu order aktif`() {
        val app = newApp()
        val a = app.buy(7, "k1") as Outcome.Ok
        val b = app.buy(7, "k2")
        assertEquals(Outcome.Already(a.order.id), b)
        assertEquals(1 to 0, app.counters())
    }

    @Test fun `setelah kedaluwarsa stok kembali dan pelanggan boleh beli lagi`() {
        val app = newApp(allocation = 1)
        val a = app.buy(1) as Outcome.Ok
        assertEquals(Outcome.SoldOut, app.buy(2))
        app.expireNow(a.order.id)
        assertEquals(1, app.inv.expireDue())
        assertEquals(0 to 0, app.counters())
        assertIs<Outcome.Ok>(app.buy(2))            // stok dilepas ke orang lain
        assertEquals(Outcome.SoldOut, app.buy(1, "baru"))
        assertEquals(0, app.inv.expireDue())
    }

    // INV-06
    @Test fun `pelepasan reservasi idempoten walau worker berjalan paralel`() = runBlocking {
        val app = newApp(5)
        val ids = (1..5L).map { (app.buy(it) as Outcome.Ok).order.id }
        ids.forEach { app.expireNow(it) }
        val total = (1..8).map { async(Dispatchers.IO) { app.inv.expireDue() } }.awaitAll().sum()
        assertEquals(5, total)
        assertEquals(0 to 0, app.counters())
        assertEquals(emptyList(), app.inv.invariantViolations())
    }

    // INV-07
    @Test fun `callback ganda hanya menghitung satu penjualan`() {
        val app = newApp()
        val o = (app.buy(1) as Outcome.Ok).order
        val ev = app.gateway.pay(o.id.toString())!!
        assertEquals(CallbackResult.CONFIRMED, app.inv.handleCallback(ev.body, ev.signature))
        assertEquals(CallbackResult.DUPLICATE, app.inv.handleCallback(ev.body, ev.signature))
        assertEquals(0 to 1, app.counters())
        assertEquals("PAID", app.inv.findById(o.id)!!.status)
    }

    @Test fun `callback dengan tanda tangan salah ditolak`() {
        val app = newApp()
        val o = (app.buy(1) as Outcome.Ok).order
        val ev = app.gateway.pay(o.id.toString())!!
        assertEquals(CallbackResult.BAD_SIGNATURE, app.inv.handleCallback(ev.body, "palsu"))
        assertEquals("PENDING_PAYMENT", app.inv.findById(o.id)!!.status)
    }

    @Test fun `callback hilang diperbaiki rekonsiliasi`() {
        val app = newApp()
        val o = (app.buy(1) as Outcome.Ok).order
        app.gateway.pay(o.id.toString())                 // gateway menerima uang, callback tidak sampai
        assertEquals("PENDING_PAYMENT", app.inv.findById(o.id)!!.status)
        assertEquals(1, app.inv.reconcilePayments())
        assertEquals("PAID", app.inv.findById(o.id)!!.status)
        assertEquals(0 to 1, app.counters())
    }

    @Test fun `pembayaran terlambat dipulihkan bila alokasi masih ada`() {
        val app = newApp(allocation = 2)
        val o = (app.buy(1) as Outcome.Ok).order
        app.expireNow(o.id); app.inv.expireDue()
        val ev = app.gateway.pay(o.id.toString())!!
        assertEquals(CallbackResult.LATE_RECOVERED, app.inv.handleCallback(ev.body, ev.signature))
        assertEquals("PAID", app.inv.findById(o.id)!!.status)
        assertEquals(0 to 1, app.counters())
        assertEquals(emptyList(), app.inv.invariantViolations())
    }

    @Test fun `pembayaran terlambat saat alokasi sudah diambil orang lain di-refund otomatis`() {
        val app = newApp(allocation = 1)
        val a = (app.buy(1) as Outcome.Ok).order
        app.expireNow(a.id); app.inv.expireDue()
        assertIs<Outcome.Ok>(app.buy(2))                              // unit terjual ke pembeli lain
        val ev = app.gateway.pay(a.id.toString())!!
        assertEquals(CallbackResult.LATE_REFUNDED, app.inv.handleCallback(ev.body, ev.signature))
        assertEquals("REFUNDED", app.inv.findById(a.id)!!.status)
        assertTrue(app.gateway.lookup(a.id.toString())!!.refunded)
        assertEquals(1 to 0, app.counters())
        assertEquals(emptyList(), app.inv.invariantViolations())
    }

    @Test fun `gateway gagal pasti melepas reservasi tanpa VA`() {
        val app = newApp()
        app.gateway.mode = GatewayMode.FAIL
        val r = app.buy(1)
        assertIs<Outcome.PaymentUnavailable>(r)
        assertEquals(0 to 0, app.counters())
        assertEquals(0, app.active())
        app.gateway.mode = GatewayMode.OK
        assertIs<Outcome.Ok>(app.buy(1, "k-baru"))
    }

    @Test fun `gateway timeout - cek status VA dulu, tidak terbit VA ganda`() {
        val app = newApp()
        app.gateway.mode = GatewayMode.TIMEOUT
        val r = app.buy(1) as Outcome.Ok                              // VA ternyata sudah terbentuk di gateway
        assertNotNull(r.order.vaNumber)
        assertEquals(r.order.vaNumber, app.gateway.lookup(r.order.id.toString())!!.number)
        assertEquals(1, app.active())
    }

    @Test fun `kampanye dijeda menolak pembelian dan resume hanya bila invariant aman`() {
        val app = newApp()
        app.inv.setStatus(1, "PAUSED", "ops", "uji")
        assertEquals(Outcome.NotActive, app.buy(1))
        assertTrue(app.inv.resume(1, "ops", "lanjut").isSuccess)
        assertIs<Outcome.Ok>(app.buy(1))
    }

    @Test fun `monitor invariant menjeda kampanye otomatis saat counter menyimpang`() {
        val app = newApp()
        app.db.conn { it.exec("UPDATE campaign SET reserved = 3 WHERE id = 1") }    // counter tidak sama dengan order
        val w = Workers(app.inv, app.db, CoroutineScope(Job()))
        assertTrue(w.monitorOnce().isNotEmpty())
        assertEquals(Outcome.NotActive, app.buy(1))
        assertTrue(app.inv.resume(1, "ops", "x").isFailure)
    }

    @Test fun `database menolak oversell walau kode salah (CHECK constraint)`() {
        val app = newApp(allocation = 3)
        val e = assertFailsWith<java.sql.SQLException> {
            app.db.conn { it.exec("UPDATE campaign SET reserved = 4 WHERE id = 1") }
        }
        assertTrue(e.isCheckViolation())
    }

    @Test fun `void-excess membatalkan order di atas alokasi dan memperbaiki counter`() {
        val app = newApp(allocation = 2)
        app.buy(1); app.buy(2)
        // Simulasi korban insiden: order tambahan masuk lewat jalur lama (tanpa alokasi).
        app.db.conn { c ->
            for (u in 3L..5L) c.exec(
                "INSERT INTO orders (order_no, user_id, campaign_id, status, unit_price, idempotency_key, reserved_until) VALUES (?,?,1,'PENDING_PAYMENT',500000,?,${Sql.dateAdd("HOUR", "1")})",
                "X-$u", u, "x$u",
            )
        }
        assertEquals(5, app.active())
        assertTrue(app.inv.invariantViolations().isNotEmpty())
        assertEquals(3, app.inv.voidExcess(1, "ops", "kelebihan"))
        assertEquals(2, app.active())
        assertEquals(emptyList(), app.inv.invariantViolations())
    }
}
