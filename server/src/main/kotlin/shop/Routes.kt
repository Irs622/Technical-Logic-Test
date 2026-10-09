package shop

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class App(
    val db: Db = Db.fresh(),
    val gateway: MockGateway = MockGateway(System.getenv("GATEWAY_CALLBACK_SECRET") ?: "dev-secret"),
) {
    val inv = Inventory(db, gateway)
    val incident = Incident(db, inv, gateway)
    val legacy = Legacy(db)
    val shop = Shop(db)
    val load = LoadSim(inv, legacy, db)

    init { Seed.init(db) }

    private val hits = ConcurrentHashMap<Long, ArrayDeque<Long>>()

    /** Rate limit sederhana per pengguna: maks 10 percobaan beli per 10 detik. */
    fun rateLimited(userId: Long): Boolean {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(userId) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
            if (q.size >= 10) return true
            q.addLast(now)
            return false
        }
    }
}

private fun orderJson(o: OrderRow) = obj(
    "order_id" to o.id, "order_no" to o.orderNo, "status" to o.status, "campaign_id" to o.campaignId,
    "product_name" to o.productName, "unit_price" to o.unitPrice,
    "reserved_until" to o.reservedUntil, "paid_at" to o.paidAt,
    "va" to o.vaNumber?.let { obj("bank" to o.vaBank, "number" to it, "amount" to o.unitPrice, "expires_at" to o.reservedUntil) },
)

private suspend fun ApplicationCall.json(status: HttpStatusCode, body: JsonObject) =
    respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.ok(data: Any?, message: String = "OK", status: HttpStatusCode = HttpStatusCode.OK) =
    json(status, envelope(data, message))

private suspend fun ApplicationCall.fail(status: HttpStatusCode, code: String, msg: String, retryable: Boolean = false, retryAfter: Int? = null) {
    if (retryAfter != null) response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
    json(status, errorEnvelope(code, msg, retryable, retryAfter))
}

/** Auth sederhana untuk simulasi: `Authorization: Bearer demo-<userId>`. */
private fun ApplicationCall.userId(): Long? =
    request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.removePrefix("demo-")?.toLongOrNull()?.takeIf { it > 0 }

private suspend fun ApplicationCall.body(): JsonObject =
    runCatching { Json.parseToJsonElement(receiveText()) as JsonObject }.getOrElse { JsonObject(emptyMap()) }

fun Application.module(app: App = App()) {
    val inv = app.inv

    routing {
        get("/health") { call.ok(obj("status" to "up")) }

        // ---------- Toko reguler ----------
        get("/products") { call.ok(app.shop.products(call.parameters["category"], call.parameters["q"]?.takeIf { it.isNotBlank() })) }
        get("/products/{id}") {
            val p = call.parameters["id"]?.toLongOrNull()?.let(app.shop::product)
            if (p == null) call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Produk tidak ditemukan") else call.ok(p)
        }
        get("/categories") { call.ok(app.shop.categories()) }

        get("/me") {
            val u = call.userId() ?: return@get call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            app.shop.ensureUser(u)
            call.ok(obj("user_id" to u, "balance" to app.shop.balance(u)))
        }
        post("/me/topup") {
            val u = call.userId() ?: return@post call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            app.shop.ensureUser(u)
            val amount = call.body().long("amount") ?: 100_000
            try { call.ok(obj("balance" to app.shop.topup(u, amount))) }
            catch (e: IllegalArgumentException) { call.fail(HttpStatusCode.BadRequest, "VALIDATION_ERROR", e.message ?: "Tidak valid") }
        }

        post("/checkout") {
            val u = call.userId() ?: return@post call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            val key = call.request.headers["Idempotency-Key"]
                ?: return@post call.fail(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Header Idempotency-Key wajib.")
            app.shop.ensureUser(u)
            val b = call.body()
            val items = (b["items"] as? JsonArray)?.mapNotNull {
                val o = it as? JsonObject ?: return@mapNotNull null
                val pid = o.long("product_id") ?: return@mapNotNull null
                pid to (o.long("qty") ?: 0L).toInt()
            } ?: emptyList()
            try {
                call.ok(app.shop.checkout(u, key, items, b.str("shipping") ?: "REG"), "Pesanan dibayar.", HttpStatusCode.Created)
            } catch (e: OutOfStock) {
                call.fail(HttpStatusCode.Conflict, "OUT_OF_STOCK", "Stok ${e.productName} tidak cukup.")
            } catch (e: InsufficientBalance) {
                call.fail(HttpStatusCode.Conflict, "INSUFFICIENT_BALANCE", "Saldo kurang Rp ${"%,d".format(e.deficit).replace(',', '.')}. Isi ulang di Akun.")
            } catch (e: BadItems) {
                call.fail(HttpStatusCode.BadRequest, "VALIDATION_ERROR", e.message ?: "Tidak valid")
            }
        }
        get("/shop/orders") {
            val u = call.userId() ?: return@get call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            call.ok(app.shop.orders(u))
        }

        // ---------- Flash sale ----------
        get("/campaigns/{id}") {
            val id = call.parameters["id"]!!.toLongOrNull() ?: return@get call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Kampanye tidak ditemukan")
            val j = app.db.conn { c ->
                c.queryOne(
                    """SELECT c.id, c.product_id, p.name, p.unit_label, c.seller_name, c.promo_price, c.normal_price, c.allocation,
                              c.reserved, c.sold, c.payment_window_sec, c.status, c.starts_at, c.ends_at, CURRENT_TIMESTAMP(3) AS now_ts
                         FROM campaign c JOIN products p ON p.id = c.product_id WHERE c.id = ?""", id,
                ) {
                    val remaining = maxOf(0, it.getInt("allocation") - it.getInt("reserved") - it.getInt("sold"))
                    obj(
                        "id" to it.getLong("id"), "product_id" to it.getLong("product_id"), "product_name" to it.getString("name"),
                        "unit_label" to it.getString("unit_label"), "seller_name" to it.getString("seller_name"),
                        "promo_price" to it.getLong("promo_price"), "normal_price" to it.getLong("normal_price"), "currency" to "IDR",
                        "status" to it.getString("status"), "starts_at" to it.instant("starts_at"), "ends_at" to it.instant("ends_at"),
                        "stock_hint" to obj("remaining_approx" to remaining, "is_approximate" to true),
                        "payment_window_sec" to it.getInt("payment_window_sec"), "server_time" to it.instant("now_ts"),
                    )
                }
            }
            if (j == null) call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Kampanye tidak ditemukan") else call.ok(j)
        }

        post("/campaigns/{id}/purchase") {
            val u = call.userId() ?: return@post call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            val cid = call.parameters["id"]!!.toLongOrNull() ?: return@post call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Kampanye tidak ditemukan")
            val key = call.request.headers["Idempotency-Key"]
                ?: return@post call.fail(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Header Idempotency-Key wajib.")
            val qty = call.body().long("quantity") ?: 1
            if (qty != 1L) return@post call.fail(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Promo ini maksimal 1 unit per pelanggan.")
            if (app.rateLimited(u)) return@post call.fail(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Terlalu cepat. Coba lagi dalam 3 detik.", true, 3)

            if (call.request.queryParameters["mode"] == "legacy") {
                // Jalur bug lama, hanya untuk demo insiden.
                return@post when (app.legacy.purchase(u, cid)) {
                    Legacy.Result.VaIssued -> call.ok(obj("legacy" to true, "note" to "VA terbit tanpa jaminan alokasi"), "Pesanan dibuat (legacy).", HttpStatusCode.Created)
                    Legacy.Result.SoldOut -> call.fail(HttpStatusCode.Conflict, "SOLD_OUT", "Stok promo sudah habis.")
                }
            }

            // Waiting room: hanya yang diizinkan yang boleh menyentuh database.
            if (!inv.admitted(u, cid)) {
                val pos = inv.queuePosition(u, cid)
                if (inv.soldOutNow(cid)) return@post call.fail(HttpStatusCode.Conflict, "SOLD_OUT", "Stok promo sudah habis. Kamu tidak dikenai biaya.")
                return@post call.ok(
                    obj("state" to "WAITING", "ticket" to "$cid-$u", "position_approx" to pos, "retry_after_sec" to 3),
                    "Kamu dalam antrean.", HttpStatusCode.Accepted,
                )
            }

            when (val r = inv.purchase(u, cid, key)) {
                is Outcome.Ok -> call.ok(orderJson(r.order), "Pesanan dibuat.", if (r.replay) HttpStatusCode.OK else HttpStatusCode.Created)
                Outcome.SoldOut -> call.fail(HttpStatusCode.Conflict, "SOLD_OUT", "Stok promo sudah habis. Kamu tidak dikenai biaya.")
                Outcome.NotActive -> call.fail(HttpStatusCode.Conflict, "CAMPAIGN_NOT_ACTIVE", "Promo ini sedang dijeda atau sudah berakhir.")
                is Outcome.Already -> call.json(HttpStatusCode.Conflict, errorEnvelope("ALREADY_PURCHASED", "Kamu sudah punya pesanan untuk promo ini.")
                    .let { JsonObject(it + ("data" to obj("order_id" to r.orderId))) })
                is Outcome.PaymentUnavailable -> call.fail(HttpStatusCode.ServiceUnavailable, "PAYMENT_UNAVAILABLE", r.message, r.retryable, 2)
            }
        }

        get("/queue/{ticket}") {
            val u = call.userId() ?: return@get call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            val parts = call.parameters["ticket"]!!.split('-')
            val cid = parts.getOrNull(0)?.toLongOrNull(); val tu = parts.getOrNull(1)?.toLongOrNull()
            if (cid == null || tu != u) return@get call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Tiket tidak ditemukan")
            val state = when {
                inv.admitted(u, cid) -> "ADMITTED"
                inv.soldOutNow(cid) -> "REJECTED"
                else -> "WAITING"
            }
            call.ok(obj("state" to state, "position_approx" to inv.queuePosition(u, cid), "retry_after_sec" to 3))
        }

        get("/orders") {
            val u = call.userId() ?: return@get call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            call.ok(inv.ordersOf(u).map(::orderJson))
        }
        get("/orders/{id}") {
            val u = call.userId() ?: return@get call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            val o = call.parameters["id"]!!.toLongOrNull()?.let(inv::findById)?.takeIf { it.userId == u }
            if (o == null) call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Pesanan tidak ditemukan") else call.ok(orderJson(o))
        }
        post("/orders/{id}/cancel") {
            val u = call.userId() ?: return@post call.fail(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Masuk dulu.")
            val id = call.parameters["id"]!!.toLongOrNull() ?: return@post call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Pesanan tidak ditemukan")
            if (inv.cancel(u, id)) call.ok(orderJson(inv.findById(id)!!), "Pesanan dibatalkan.")
            else call.fail(HttpStatusCode.Conflict, "CONFLICT", "Pesanan tidak bisa dibatalkan.")
        }

        // ---------- Callback gateway ----------
        post("/internal/payments/callback") {
            val body = call.receiveText()
            val sig = call.request.headers["X-Signature"] ?: ""
            val r = inv.handleCallback(body, sig)
            if (r == CallbackResult.BAD_SIGNATURE) call.fail(HttpStatusCode.Forbidden, "BAD_SIGNATURE", "Tanda tangan tidak valid.")
            else call.ok(obj("result" to r.name))      // event duplikat tetap dibalas 200
        }

        // ---------- Admin ----------
        post("/admin/campaigns/{id}/pause") {
            val id = call.parameters["id"]!!.toLong()
            val reason = call.body().str("reason") ?: call.request.queryParameters["reason"] ?: "kill switch manual"
            inv.setStatus(id, "PAUSED", "ops", reason)
            call.ok(obj("status" to "PAUSED"), "Kampanye dijeda.")
        }
        post("/admin/campaigns/{id}/resume") {
            val id = call.parameters["id"]!!.toLong()
            inv.resume(id, "ops", "dilanjutkan").fold(
                { call.ok(obj("status" to "ACTIVE"), "Kampanye dilanjutkan.") },
                { call.fail(HttpStatusCode.Conflict, "INVARIANT_VIOLATED", it.message ?: "Invariant dilanggar") },
            )
        }
        get("/admin/campaigns/{id}/reconciliation") { call.ok(inv.reconciliation(call.parameters["id"]!!.toLong())) }
        post("/admin/campaigns/{id}/void-excess") {
            val n = inv.voidExcess(call.parameters["id"]!!.toLong(), "ops", call.body().str("reason") ?: "kelebihan alokasi")
            call.ok(obj("voided" to n))
        }

        get("/admin/incident/exposure") { call.ok(app.incident.exposure()) }
        post("/admin/incident/freeze-all") {
            call.ok(app.incident.freezeAll("ops", call.body().str("reason") ?: "insiden oversell"), "Semua kampanye dijeda.")
        }
        get("/admin/campaigns/{id}/compensation-plan") {
            val q = call.request.queryParameters
            val plan = app.incident.plan(call.parameters["id"]!!.toLong(), (q["option"] ?: "B").uppercase(), q["voucher"]?.toLong() ?: 50_000, q["seller_pct"]?.toInt() ?: 50)
            if (plan == null) call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Kampanye tidak ditemukan") else call.ok(plan)
        }
        post("/admin/campaigns/{id}/compensation-execute") {
            val q = call.request.queryParameters
            val r = app.incident.execute(call.parameters["id"]!!.toLong(), (q["option"] ?: "B").uppercase(), q["voucher"]?.toLong() ?: 50_000, q["seller_pct"]?.toInt() ?: 50)
            if (r == null) call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "Kampanye tidak ditemukan") else call.ok(r, "Kompensasi dieksekusi.")
        }

        // ---------- Simulasi (hanya build dev) ----------
        post("/sim/campaigns") {
            val q = call.request.queryParameters
            val n = (q["count"]?.toInt() ?: 800).coerceIn(1, 2000)
            app.db.tx { c -> for (i in 2L..(n + 1L)) Seed.createCampaign(c, i, "Penjual $i", q["allocation"]?.toInt() ?: 100, 30, 3600) }
            call.ok(obj("created" to n, "first_id" to 2, "last_id" to n + 1))
        }
        post("/sim/incident") {
            // Reproduksi insiden pada banyak penjual: alur lama ditembak per kampanye, 80% VA dibayar.
            val q = call.request.queryParameters
            val campaigns = (q["campaigns"]?.toInt() ?: 50).coerceIn(1, 800); val users = (q["users"]?.toInt() ?: 500).coerceIn(1, 20_000)
            app.db.tx { c -> for (i in 2L..(campaigns + 1L)) if (c.queryOne("SELECT 1 FROM campaign WHERE id = ?", i) { 1 } == null) Seed.createCampaign(c, i, "Penjual $i", 100, 30, 3600) }
            for (id in 2L..(campaigns + 1L)) app.load.run("legacy", users, 100, campaignId = id)
            app.db.conn { c -> c.exec("UPDATE legacy_va SET paid = (MOD(seq, 5) <> 0)") }
            call.ok(app.incident.exposure(), "Insiden direproduksi pada $campaigns penjual.")
        }
        post("/sim/gateway") {
            app.gateway.mode = runCatching { GatewayMode.valueOf((call.request.queryParameters["mode"] ?: "ok").uppercase()) }.getOrDefault(GatewayMode.OK)
            call.request.queryParameters["rate"]?.toDoubleOrNull()?.let { app.gateway.failRate = it.coerceIn(0.0, 1.0) }
            call.ok(obj("mode" to app.gateway.mode.name, "fail_rate" to app.gateway.failRate))
        }
        post("/sim/reset") {
            val q = call.request.queryParameters
            app.db.tx { c ->
                Seed.resetCampaign(c, q["allocation"]?.toInt() ?: 100, q["window_min"]?.toInt() ?: 30, q["payment_window_sec"]?.toInt() ?: 3600)
            }
            app.gateway.clear(); app.gateway.mode = GatewayMode.OK; inv.resetCaches()
            call.ok(obj("reset" to true))
        }
        post("/sim/payments/{orderId}/pay") {
            val id = call.parameters["orderId"]!!.toLong()
            val behavior = call.request.queryParameters["behavior"] ?: "ok"
            if (behavior == "late") {   // paksa kedaluwarsa dulu agar callback datang terlambat
                app.db.conn { c -> c.exec("UPDATE orders SET reserved_until = ${Sql.dateAdd("SECOND", "-1")} WHERE id = ? AND status = 'PENDING_PAYMENT'", id) }
                inv.expireDue()
            }
            val ev = app.gateway.pay(id.toString())
                ?: return@post call.fail(HttpStatusCode.NotFound, "NOT_FOUND", "VA tidak ditemukan atau sudah dibatalkan")
            if (behavior == "lost") return@post call.ok(obj("callback" to "LOST"), "Pembayaran masuk di gateway, callback tidak terkirim.")
            val results = mutableListOf(inv.handleCallback(ev.body, ev.signature))
            if (behavior == "duplicate") results += inv.handleCallback(ev.body, ev.signature)
            call.ok(obj("callbacks" to results.map { it.name }, "order" to inv.findById(id)?.let(::orderJson)))
        }
        post("/sim/expire") { call.ok(obj("expired" to inv.expireDue())) }
        post("/sim/reconcile") { call.ok(obj("fixed" to inv.reconcilePayments())) }
        post("/sim/load") {
            val q = call.request.queryParameters
            val users = (q["users"]?.toInt() ?: 20_000).coerceIn(1, 250_000)
            val mode = q["mode"] ?: "safe"
            val cid = q["campaign"]?.toLongOrNull() ?: Seed.CAMPAIGN_ID
            val alloc = app.db.conn { c -> c.queryOne("SELECT allocation FROM campaign WHERE id = ?", cid) { it.getInt(1) } } ?: 100
            call.ok(app.load.run(mode, users, alloc, campaignId = cid, admission = q["admission"] == "true"))
        }
    }
}
