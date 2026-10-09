package shop

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class OrderRow(
    val id: Long, val orderNo: String, val userId: Long, val campaignId: Long, val status: String,
    val unitPrice: Long, val reservedAt: Instant, val reservedUntil: Instant, val paidAt: Instant?,
    val productName: String, val vaBank: String?, val vaNumber: String?,
)

sealed interface Outcome {
    data class Ok(val order: OrderRow, val replay: Boolean) : Outcome
    data object SoldOut : Outcome
    data object NotActive : Outcome
    data class Already(val orderId: Long) : Outcome
    data class PaymentUnavailable(val retryable: Boolean, val message: String) : Outcome
}

enum class CallbackResult { CONFIRMED, DUPLICATE, LATE_RECOVERED, LATE_REFUNDED, IGNORED, BAD_SIGNATURE }

private const val ORDER_SELECT = """
    SELECT o.id, o.order_no, o.user_id, o.campaign_id, o.status, o.unit_price, o.reserved_at, o.reserved_until,
           o.paid_at, p.name AS product_name, v.bank AS va_bank, v.va_number
      FROM orders o
      JOIN campaign c ON c.id = o.campaign_id
      JOIN products p ON p.id = c.product_id
      LEFT JOIN payment_va v ON v.order_id = o.id
"""

private fun mapOrder(rs: java.sql.ResultSet) = OrderRow(
    rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"), rs.getLong("campaign_id"),
    rs.getString("status"), rs.getLong("unit_price"), rs.instant("reserved_at")!!, rs.instant("reserved_until")!!,
    rs.instant("paid_at"), rs.getString("product_name"), rs.getString("va_bank"), rs.getString("va_number"),
)

/**
 * Otoritas stok flash sale. Aturan emas (docs/backend.md A.2):
 *  - keputusan alokasi = affected_rows dari satu UPDATE bersyarat, bukan hasil bacaan sebelumnya;
 *  - reservasi + order + ledger dalam satu transaksi singkat;
 *  - panggilan gateway di luar transaksi;
 *  - perubahan status lewat compare-and-set.
 */
class Inventory(val db: Db, val gateway: MockGateway, val admission: Admission = Admission()) {
    private val orderSeq = AtomicLong()
    private val soldOutCache = ConcurrentHashMap<Long, Boolean>()

    /** Wajib dipanggil setelah Seed.resetCampaign agar cache "habis" tidak basi. */
    fun resetCaches() { soldOutCache.clear(); allocCache.clear(); admission.reset() }

    private val allocCache = ConcurrentHashMap<Long, Int>()
    fun allocationOf(campaignId: Long): Int = allocCache.computeIfAbsent(campaignId) {
        db.conn { c -> c.queryOne("SELECT allocation FROM campaign WHERE id = ?", campaignId) { it.getInt(1) } } ?: 0
    }

    /** Waiting room: apakah pembeli ini boleh menyentuh database sekarang? Pemegang order selalu lolos. */
    fun admitted(userId: Long, campaignId: Long): Boolean =
        admission.isAdmitted(campaignId, allocationOf(campaignId), userId)

    fun queuePosition(userId: Long, campaignId: Long) = admission.position(campaignId, allocationOf(campaignId), userId)

    fun soldOutNow(campaignId: Long): Boolean = db.conn { c ->
        c.queryOne("SELECT reserved + sold >= allocation FROM campaign WHERE id = ?", campaignId) { it.getBoolean(1) }
    } ?: true

    // ---------- Pembelian ----------

    fun purchase(userId: Long, campaignId: Long, idemKey: String): Outcome {
        var replay = false
        val order = when (val r = reserve(userId, campaignId, idemKey)) {
            is Reserve.Created -> { replay = r.replay; r.order }
            Reserve.SoldOut -> return Outcome.SoldOut
            Reserve.NotActive -> return Outcome.NotActive
            is Reserve.Already -> return Outcome.Already(r.orderId)
        }
        if (order.status != "PENDING_PAYMENT" || order.vaNumber != null) return Outcome.Ok(order, replay)
        return when (val o = ensureVa(order)) {
            is Outcome.Ok -> Outcome.Ok(o.order, replay)
            else -> o
        }
    }

    private sealed interface Reserve {
        data class Created(val order: OrderRow, val replay: Boolean = false) : Reserve
        data object SoldOut : Reserve
        data object NotActive : Reserve
        data class Already(val orderId: Long) : Reserve
    }

    private fun reserve(userId: Long, campaignId: Long, key: String): Reserve {
        findByKey(userId, key)?.let { return Reserve.Created(it, replay = true) }
        if (soldOutCache[campaignId] == true) {
            // Jalur cepat hanya boleh MENOLAK (tidak pernah menerima), jadi aman terhadap oversell.
            activeOrderOf(userId, campaignId)?.let { return Reserve.Already(it) }
            return Reserve.SoldOut
        }
        try {
            val orderId = db.tx { c ->
                val updated = c.exec(
                    """UPDATE campaign SET reserved = reserved + 1
                        WHERE id = ? AND status = 'ACTIVE'
                          AND CURRENT_TIMESTAMP(3) >= starts_at AND CURRENT_TIMESTAMP(3) < ends_at
                          AND reserved + sold < allocation""",
                    campaignId,
                )
                if (updated == 0) return@tx -1L
                val cp = c.queryOne("SELECT promo_price, payment_window_sec FROM campaign WHERE id = ?", campaignId) {
                    it.getLong(1) to it.getInt(2)
                }!!
                val id = c.insertReturningId(
                    """INSERT INTO orders (order_no, user_id, campaign_id, status, unit_price, idempotency_key, reserved_until)
                       VALUES (?, ?, ?, 'PENDING_PAYMENT', ?, ?, ${Sql.dateAdd("SECOND", "?")})""",
                    newOrderNo(), userId, campaignId, cp.first, key, cp.second,
                )
                c.exec("INSERT INTO inventory_ledger (order_id, campaign_id, entry_type) VALUES (?, ?, 'RESERVE')", id, campaignId)
                c.exec(
                    "INSERT INTO order_events (order_id, from_status, to_status, actor) VALUES (?, NULL, 'PENDING_PAYMENT', ?)",
                    id, "user:$userId",
                )
                id
            }
            if (orderId < 0) return classifyRejection(userId, campaignId)
            return Reserve.Created(findById(orderId)!!)
        } catch (e: SQLException) {
            if (e.isUniqueViolation()) {
                findByKey(userId, key)?.let { return Reserve.Created(it, replay = true) }
                activeOrderOf(userId, campaignId)?.let { return Reserve.Already(it) }
            }
            // CHECK ck_no_oversell menolak: perlakukan sebagai habis (jaring pengaman terakhir).
            if (e.isCheckViolation()) { soldOutCache[campaignId] = true; return Reserve.SoldOut }
            throw e
        }
    }

    private fun classifyRejection(userId: Long, campaignId: Long): Reserve {
        activeOrderOf(userId, campaignId)?.let { return Reserve.Already(it) }
        val active = db.conn { c ->
            c.queryOne(
                """SELECT status = 'ACTIVE' AND CURRENT_TIMESTAMP(3) >= starts_at AND CURRENT_TIMESTAMP(3) < ends_at
                     FROM campaign WHERE id = ?""", campaignId,
            ) { it.getBoolean(1) }
        }
        return if (active == true) { soldOutCache[campaignId] = true; Reserve.SoldOut } else Reserve.NotActive
    }

    /** Membuat VA di gateway SETELAH transaksi reservasi commit. */
    private fun ensureVa(order: OrderRow): Outcome {
        val va = try {
            gateway.createVa(order.id.toString(), order.unitPrice, order.reservedUntil)
        } catch (e: GatewayTimeout) {
            // Hasil tidak diketahui: cek dulu apakah VA sudah terbentuk sebelum menyerah / retry.
            gateway.lookup(order.id.toString())
                ?: return Outcome.PaymentUnavailable(true, "Pembayaran sedang diproses. Coba lagi sebentar.")
        } catch (e: GatewayFailure) {
            if (release(order.id, "CANCELLED", "gateway", "VA gagal dibuat")) {
                // Bebaskan idempotency key: percobaan ulang dengan key yang sama harus membuat reservasi BARU,
                // bukan mengembalikan order yang sudah dibatalkan.
                db.conn { c -> c.exec("UPDATE orders SET idempotency_key = CONCAT(idempotency_key, '#x', id) WHERE id = ?", order.id) }
            }
            return Outcome.PaymentUnavailable(true, "Pembayaran sedang tidak tersedia. Tidak ada biaya yang dikenakan.")
        }
        db.conn { c ->
            try {
                c.exec(
                    """INSERT INTO payment_va (order_id, provider, provider_ref, va_number, bank, amount, status, expires_at)
                       VALUES (?, 'mock', ?, ?, ?, ?, 'ACTIVE', ?)""",
                    order.id, va.providerRef, va.number, va.bank, va.amount, order.reservedUntil,
                )
            } catch (e: SQLException) { if (!e.isUniqueViolation()) throw e else 0 }
        }
        return Outcome.Ok(findById(order.id)!!, replay = false)
    }

    // ---------- Status & pelepasan ----------

    /** Lepas reservasi: CAS PENDING_PAYMENT -> [to], ledger RELEASE (sekali), reserved-1. Idempoten. */
    fun release(orderId: Long, to: String, actor: String, reason: String): Boolean {
        var cidOut = 0L
        val done = db.tx { c ->
            val cid = c.queryOne("SELECT campaign_id FROM orders WHERE id = ?", orderId) { it.getLong(1) } ?: return@tx false
            lockCampaign(c, cid)                                     // urutan lock tetap: campaign -> orders
            val n = c.exec("UPDATE orders SET status = ? WHERE id = ? AND status = 'PENDING_PAYMENT'", to, orderId)
            if (n == 0) return@tx false
            c.exec("INSERT INTO inventory_ledger (order_id, campaign_id, entry_type) VALUES (?, ?, 'RELEASE')", orderId, cid)
            c.exec("UPDATE campaign SET reserved = reserved - 1 WHERE id = ?", cid)
            event(c, orderId, "PENDING_PAYMENT", to, actor, reason)
            cidOut = cid
            true
        }
        if (done) { soldOutCache.clear(); admission.onRelease(cidOut) }
        return done
    }

    /** Kunci baris kampanye lebih dulu agar semua jalur memakai urutan lock yang sama (campaign -> orders). */
    private fun lockCampaign(c: Connection, campaignId: Long) {
        c.query("SELECT id FROM campaign WHERE id = ? FOR UPDATE", campaignId) { it.getLong(1) }
    }

    private fun campaignIdOf(orderId: Long): Long = db.conn { c -> c.queryOne("SELECT campaign_id FROM orders WHERE id = ?", orderId) { it.getLong(1) } } ?: 0

    fun cancel(userId: Long, orderId: Long): Boolean {
        val o = findById(orderId) ?: return false
        if (o.userId != userId) return false
        return release(orderId, "CANCELLED", "user:$userId", "dibatalkan pelanggan") || o.status == "CANCELLED"
    }

    // ---------- Pembayaran ----------

    fun handleCallback(body: String, signature: String): CallbackResult {
        if (!java.security.MessageDigest.isEqual(Signature.hmac(gateway.secret, body).toByteArray(), signature.toByteArray()))
            return CallbackResult.BAD_SIGNATURE
        val j = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        return confirmPaid(j.str("event_id")!!, j.long("order_id")!!, body)
    }

    fun confirmPaid(eventId: String, orderId: Long, rawPayload: String = "{}"): CallbackResult {
        var needRefund = false
        val result = db.tx { c ->
            try {
                c.exec(
                    "INSERT INTO payment_events (provider, provider_event_id, order_id, payload) VALUES ('mock', ?, ?, ?)",
                    eventId, orderId, rawPayload.take(1900),
                )
            } catch (e: SQLException) {
                if (e.isUniqueViolation()) return@tx CallbackResult.DUPLICATE else throw e
            }
            val cid = c.queryOne("SELECT campaign_id FROM orders WHERE id = ?", orderId) { it.getLong(1) }
                ?: return@tx CallbackResult.IGNORED
            lockCampaign(c, cid)

            val n = c.exec("UPDATE orders SET status = 'PAID', paid_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND status = 'PENDING_PAYMENT'", orderId)
            if (n == 1) {
                c.exec("UPDATE campaign SET reserved = reserved - 1, sold = sold + 1 WHERE id = ?", cid)
                c.exec("INSERT INTO inventory_ledger (order_id, campaign_id, entry_type) VALUES (?, ?, 'CONFIRM')", orderId, cid)
                event(c, orderId, "PENDING_PAYMENT", "PAID", "gateway", null)
                return@tx CallbackResult.CONFIRMED
            }
            val status = c.queryOne("SELECT status FROM orders WHERE id = ? FOR UPDATE", orderId) { it.getString(1) }   // baca terbaru, bukan snapshot
            if (status != "EXPIRED") return@tx CallbackResult.IGNORED

            // Pembayaran terlambat: pulihkan hanya bila alokasi MASIH tersedia, selain itu refund.
            val got = c.exec("UPDATE campaign SET sold = sold + 1 WHERE id = ? AND reserved + sold < allocation", cid)
            if (got == 1) {
                try {
                    c.exec("UPDATE orders SET status = 'PAID', paid_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND status = 'EXPIRED'", orderId)
                    c.exec("INSERT INTO inventory_ledger (order_id, campaign_id, entry_type) VALUES (?, ?, 'CONFIRM')", orderId, cid)
                    event(c, orderId, "EXPIRED", "PAID", "gateway", "bayar terlambat, alokasi masih ada")
                    return@tx CallbackResult.LATE_RECOVERED
                } catch (e: SQLException) {
                    if (!e.isUniqueViolation()) throw e
                    c.exec("UPDATE campaign SET sold = sold - 1 WHERE id = ?", cid)   // pelanggan sudah punya order aktif lain
                }
            }
            c.exec("UPDATE orders SET status = 'REFUND_REQUIRED' WHERE id = ? AND status = 'EXPIRED'", orderId)
            event(c, orderId, "EXPIRED", "REFUND_REQUIRED", "gateway", "bayar terlambat, alokasi tidak tersedia")
            needRefund = true
            CallbackResult.LATE_REFUNDED
        }
        if (needRefund) {
            gateway.refund(orderId.toString())
            db.tx { c ->
                if (c.exec("UPDATE orders SET status = 'REFUNDED' WHERE id = ? AND status = 'REFUND_REQUIRED'", orderId) == 1)
                    event(c, orderId, "REFUND_REQUIRED", "REFUNDED", "worker:refund", null)
            }
        }
        return result
    }

    // ---------- Worker ----------

    fun expireDue(batch: Int = 500): Int {
        val ids = db.conn { c ->
            c.query(
                "SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND reserved_until < CURRENT_TIMESTAMP(3) ORDER BY reserved_until LIMIT ?",
                batch,
            ) { it.getLong(1) }
        }
        return ids.count { release(it, "EXPIRED", "worker:expire", "melewati batas bayar") }
    }

    /** Samakan order dengan gateway: callback yang hilang/terlambat diperbaiki di sini. */
    fun reconcilePayments(): Int {
        val pending = db.conn { c ->
            c.query("SELECT id FROM orders WHERE status = 'PENDING_PAYMENT'") { it.getLong(1) }
        }
        var fixed = 0
        for (id in pending) {
            val va = gateway.lookup(id.toString()) ?: continue
            if (!va.paid) continue
            val r = confirmPaid("recon-$id", id)
            if (r == CallbackResult.CONFIRMED || r == CallbackResult.LATE_RECOVERED) fixed++
        }
        return fixed
    }

    // ---------- Invariant & admin ----------

    fun invariantViolations(): List<String> = db.conn { c ->
        val out = ArrayList<String>()
        c.query("SELECT id, reserved, sold, allocation FROM campaign WHERE reserved + sold > allocation") {
            out += "campaign ${it.getLong(1)}: reserved+sold=${it.getInt(2) + it.getInt(3)} > allocation=${it.getInt(4)}"
        }
        c.query(
            """SELECT c.id, c.reserved, c.sold,
                      (SELECT COUNT(*) FROM orders o WHERE o.campaign_id = c.id AND o.status = 'PENDING_PAYMENT'),
                      (SELECT COUNT(*) FROM orders o WHERE o.campaign_id = c.id AND o.status IN ('PAID','FULFILLED')),
                      c.allocation
                 FROM campaign c""",
        ) {
            if (it.getInt(2) != it.getInt(4) || it.getInt(3) != it.getInt(5))
                out += "campaign ${it.getLong(1)}: counter reserved/sold=${it.getInt(2)}/${it.getInt(3)} tidak sama dengan order ${it.getInt(4)}/${it.getInt(5)}"
            if (it.getInt(4) + it.getInt(5) > it.getInt(6))
                out += "campaign ${it.getLong(1)}: order aktif ${it.getInt(4) + it.getInt(5)} > allocation ${it.getInt(6)}"
        }
        c.query(
            """SELECT user_id, campaign_id, COUNT(*) FROM orders
                WHERE status IN ('PENDING_PAYMENT','PAID','FULFILLED') GROUP BY user_id, campaign_id HAVING COUNT(*) > 1""",
        ) { out += "user ${it.getLong(1)} punya ${it.getInt(3)} order aktif di campaign ${it.getLong(2)}" }
        out
    }

    fun setStatus(campaignId: Long, status: String, actor: String, reason: String): Boolean = db.tx { c ->
        val n = c.exec("UPDATE campaign SET status = ? WHERE id = ?", status, campaignId)
        if (n == 1) c.exec("INSERT INTO campaign_audit (campaign_id, action, actor, reason) VALUES (?,?,?,?)", campaignId, status, actor, reason)
        n == 1
    }.also { soldOutCache.clear() }

    fun resume(campaignId: Long, actor: String, reason: String): Result<Unit> {
        val v = invariantViolations()
        if (v.isNotEmpty()) return Result.failure(IllegalStateException("Invariant dilanggar: ${v.first()}"))
        setStatus(campaignId, "ACTIVE", actor, reason)
        return Result.success(Unit)
    }

    /** Tandai order aktif di atas alokasi (urut reserved_at) sebagai VOIDED, refund yang sudah dibayar, perbaiki counter. */
    fun voidExcess(campaignId: Long, actor: String, reason: String): Int {
        val voided = ArrayList<Pair<Long, String>>()
        db.tx { c ->
            lockCampaign(c, campaignId)
            val alloc = c.queryOne("SELECT allocation FROM campaign WHERE id = ?", campaignId) { it.getInt(1) } ?: return@tx
            val active = c.query(
                "SELECT id, status FROM orders WHERE campaign_id = ? AND status IN ('PENDING_PAYMENT','PAID','FULFILLED') ORDER BY reserved_at, id",
                campaignId,
            ) { it.getLong(1) to it.getString(2) }
            active.drop(alloc).forEach { (id, st) ->
                if (c.exec("UPDATE orders SET status = 'VOIDED' WHERE id = ? AND status = ?", id, st) == 1) {
                    event(c, id, st, "VOIDED", actor, reason)
                    voided += id to st
                }
            }
            c.exec(
                """UPDATE campaign SET
                     reserved = (SELECT COUNT(*) FROM orders WHERE campaign_id = ? AND status = 'PENDING_PAYMENT'),
                     sold = (SELECT COUNT(*) FROM orders WHERE campaign_id = ? AND status IN ('PAID','FULFILLED'))
                   WHERE id = ?""", campaignId, campaignId, campaignId,
            )
            c.exec("INSERT INTO campaign_audit (campaign_id, action, actor, reason) VALUES (?,?,?,?)", campaignId, "VOID_EXCESS", actor, reason)
        }
        voided.filter { it.second != "PENDING_PAYMENT" }.forEach { gateway.refund(it.first.toString()) }
        gateway.cancelUnpaid(voided.filter { it.second == "PENDING_PAYMENT" }.map { it.first.toString() })
        return voided.size
    }

    /** Tandai order tertentu VOIDED (refund bila sudah dibayar, batalkan VA bila belum), lalu perbaiki counter dari kenyataan. */
    fun voidOrders(campaignId: Long, orderIds: List<Long>, actor: String, reason: String): Int {
        if (orderIds.isEmpty()) return 0
        val voided = ArrayList<Pair<Long, String>>()
        db.tx { c ->
            lockCampaign(c, campaignId)
            orderIds.forEach { id ->
                val st = c.queryOne("SELECT status FROM orders WHERE id = ?", id) { it.getString(1) } ?: return@forEach
                if (st in setOf("PENDING_PAYMENT", "PAID", "FULFILLED") && c.exec("UPDATE orders SET status = 'VOIDED' WHERE id = ? AND status = ?", id, st) == 1) {
                    event(c, id, st, "VOIDED", actor, reason); voided += id to st
                }
            }
            c.exec(
                """UPDATE campaign SET
                     reserved = (SELECT COUNT(*) FROM orders WHERE campaign_id = ? AND status = 'PENDING_PAYMENT'),
                     sold = (SELECT COUNT(*) FROM orders WHERE campaign_id = ? AND status IN ('PAID','FULFILLED'))
                   WHERE id = ?""", campaignId, campaignId, campaignId,
            )
        }
        voided.filter { it.second != "PENDING_PAYMENT" }.forEach { gateway.refund(it.first.toString()) }
        gateway.cancelUnpaid(voided.filter { it.second == "PENDING_PAYMENT" }.map { it.first.toString() })
        return voided.size
    }

    fun reconciliation(campaignId: Long): kotlinx.serialization.json.JsonObject = db.conn { c ->
        val cp = c.queryOne("SELECT allocation, reserved, sold, status FROM campaign WHERE id = ?", campaignId) {
            obj("allocation" to it.getInt(1), "reserved" to it.getInt(2), "sold" to it.getInt(3), "status" to it.getString(4))
        }
        val byStatus = c.query("SELECT status, COUNT(*) FROM orders WHERE campaign_id = ? GROUP BY status", campaignId) {
            it.getString(1) to it.getInt(2)
        }.toMap()
        val active = (byStatus["PENDING_PAYMENT"] ?: 0) + (byStatus["PAID"] ?: 0) + (byStatus["FULFILLED"] ?: 0)
        obj(
            "campaign" to cp, "orders_by_status" to byStatus, "active_orders" to active,
            "gateway_paid" to gateway.paidCount(), "violations" to invariantViolations(),
        )
    }

    // ---------- Query ----------

    fun findById(id: Long): OrderRow? = db.conn { c -> c.queryOne("$ORDER_SELECT WHERE o.id = ?", id, map = ::mapOrder) }
    fun findByKey(userId: Long, key: String): OrderRow? =
        db.conn { c -> c.queryOne("$ORDER_SELECT WHERE o.user_id = ? AND o.idempotency_key = ?", userId, key, map = ::mapOrder) }
    fun ordersOf(userId: Long): List<OrderRow> =
        db.conn { c -> c.query("$ORDER_SELECT WHERE o.user_id = ? ORDER BY o.id DESC LIMIT 50", userId, map = ::mapOrder) }
    private fun activeOrderOf(userId: Long, campaignId: Long): Long? = db.conn { c ->
        c.queryOne(
            "SELECT id FROM orders WHERE user_id = ? AND campaign_id = ? AND status IN ('PENDING_PAYMENT','PAID','FULFILLED')",
            userId, campaignId,
        ) { it.getLong(1) }
    }

    private fun event(c: Connection, orderId: Long, from: String?, to: String, actor: String, reason: String?) {
        c.exec("INSERT INTO order_events (order_id, from_status, to_status, actor, reason) VALUES (?,?,?,?,?)", orderId, from, to, actor, reason)
    }

    private fun newOrderNo(): String {
        val d = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "")
        return "TS-$d-" + orderSeq.incrementAndGet().toString().padStart(5, '0')
    }
}
