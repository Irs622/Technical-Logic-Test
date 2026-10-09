package shop

import kotlinx.serialization.json.JsonObject
import java.sql.SQLException

/** Pemegang slot promo: order (sistem baru) atau VA lama (sistem bug), diurutkan menurut waktu terbit. */
data class Holder(val userId: Long, val paid: Boolean, val orderId: Long?)

enum class Decision { KEEP, SUBSIDY, REFUND_VOUCHER, CANCEL_VA }

/**
 * Penanganan insiden lintas kampanye: eksposur, pembekuan massal, rencana & eksekusi kompensasi
 * (opsi A/B/C di docs/incident-live-commerce.md §1.5). Semua keputusan deterministik dan idempoten.
 */
class Incident(private val db: Db, private val inv: Inventory, private val gateway: MockGateway) {

    fun holders(campaignId: Long): List<Holder> = db.conn { c ->
        val o = c.query(
            "SELECT id, user_id, status FROM orders WHERE campaign_id = ? AND status IN ('PENDING_PAYMENT','PAID','FULFILLED') ORDER BY reserved_at, id",
            campaignId,
        ) { Holder(it.getLong(2), it.getString(3) != "PENDING_PAYMENT", it.getLong(1)) }
        val l = c.query("SELECT user_id, paid FROM legacy_va WHERE campaign_id = ? ORDER BY seq", campaignId) {
            Holder(it.getLong(1), it.getBoolean(2), null)
        }
        o + l
    }

    private data class Camp(val allocation: Int, val promo: Long, val normal: Long, val seller: String)

    private fun camp(campaignId: Long) = db.conn { c ->
        c.queryOne("SELECT allocation, promo_price, normal_price, seller_name FROM campaign WHERE id = ?", campaignId) {
            Camp(it.getInt(1), it.getLong(2), it.getLong(3), it.getString(4))
        }
    }

    /** Keputusan per pemegang: yang pertama [allocation] dipertahankan, sisanya mengikuti opsi. */
    fun decisions(h: List<Holder>, allocation: Int, option: String, sellerPct: Int): List<Pair<Holder, Decision>> {
        val excess = h.drop(allocation)
        val paidExcess = excess.filter { it.paid }
        val fulfill = when (option) { "A" -> paidExcess.size; "C" -> (paidExcess.size * sellerPct.coerceIn(0, 100) + 50) / 100; else -> 0 }
        var seen = 0
        return h.mapIndexed { i, x ->
            when {
                i < allocation -> x to Decision.KEEP
                !x.paid -> x to Decision.CANCEL_VA
                seen++ < fulfill -> x to Decision.SUBSIDY
                else -> x to Decision.REFUND_VOUCHER
            }
        }
    }

    fun plan(campaignId: Long, option: String, voucherValue: Long = 50_000, sellerPct: Int = 50): JsonObject? {
        val c = camp(campaignId) ?: return null
        val d = decisions(holders(campaignId), c.allocation, option, sellerPct)
        val diff = c.normal - c.promo
        val subsidy = d.count { it.second == Decision.SUBSIDY }
        val refund = d.count { it.second == Decision.REFUND_VOUCHER }
        val cancel = d.count { it.second == Decision.CANCEL_VA }
        return obj(
            "campaign_id" to campaignId, "seller" to c.seller, "option" to option, "allocation" to c.allocation,
            "holders" to d.size, "kept" to d.count { it.second == Decision.KEEP },
            "excess" to subsidy + refund + cancel,
            "paid_excess" to subsidy + refund, "unpaid_excess_va_to_cancel" to cancel,
            "fulfilled_with_subsidy" to subsidy, "refunded" to refund,
            "price_difference_per_unit" to diff,
            "platform_subsidy_total" to subsidy * diff,
            "refund_total" to refund * c.promo,
            "voucher_total" to refund * voucherValue,
            "platform_cost_total" to subsidy * diff + refund * voucherValue,
        )
    }

    /** Eksekusi massal & idempoten. Aman dijalankan ulang: baris kompensasi unik per (kampanye, pengguna, jenis). */
    fun execute(campaignId: Long, option: String, voucherValue: Long = 50_000, sellerPct: Int = 50): JsonObject? {
        val c = camp(campaignId) ?: return null
        val before = plan(campaignId, option, voucherValue, sellerPct)!!      // rencana dihitung SEBELUM order di-void
        val d = decisions(holders(campaignId), c.allocation, option, sellerPct)
        val diff = c.normal - c.promo
        var written = 0
        db.tx { conn ->
            fun put(user: Long, kind: String, amount: Long) {
                try { conn.exec("INSERT INTO compensations (campaign_id, user_id, kind, amount, option_code) VALUES (?,?,?,?,?)", campaignId, user, kind, amount, option); written++ }
                catch (e: SQLException) { if (!e.isUniqueViolation()) throw e }
            }
            d.forEach { (h, dec) ->
                when (dec) {
                    Decision.KEEP -> {}
                    Decision.SUBSIDY -> put(h.userId, "SUBSIDY", diff)
                    Decision.REFUND_VOUCHER -> { put(h.userId, "REFUND", c.promo); put(h.userId, "VOUCHER", voucherValue) }
                    Decision.CANCEL_VA -> put(h.userId, "VA_CANCELLED", 0)
                }
            }
        }
        // Order yang dibatalkan/di-refund ditandai VOIDED dan counter diperbaiki; VA belum dibayar dibatalkan di gateway.
        val voidIds = d.filter { it.second == Decision.REFUND_VOUCHER || it.second == Decision.CANCEL_VA }.mapNotNull { it.first.orderId }
        inv.voidOrders(campaignId, voidIds, "ops", "kompensasi opsi $option")
        return JsonObject(before + ("compensation_rows_written" to toEl(written)))
    }

    /** Eksposur semua kampanye: berapa VA/order di atas alokasi dan kerugian penjual bila dipenuhi di harga promo. */
    fun exposure(): JsonObject {
        data class Row(val id: Long, val seller: String, val allocation: Int, val holders: Int, val diff: Long, val status: String)
        val rows = db.conn { c ->
            c.query(
                """SELECT c.id, c.seller_name, c.allocation, c.normal_price - c.promo_price, c.status,
                          (SELECT COUNT(*) FROM orders o WHERE o.campaign_id = c.id AND o.status IN ('PENDING_PAYMENT','PAID','FULFILLED'))
                        + (SELECT COUNT(*) FROM legacy_va l WHERE l.campaign_id = c.id)
                     FROM campaign c ORDER BY c.id""",
            ) { Row(it.getLong(1), it.getString(2), it.getInt(3), it.getInt(6), it.getLong(4), it.getString(5)) }
        }
        val affected = rows.filter { it.holders > it.allocation }
        val totalExcess = affected.sumOf { it.holders - it.allocation }
        return obj(
            "campaigns" to rows.size, "campaigns_affected" to affected.size,
            "total_excess" to totalExcess,
            "worst_case_loss_if_fulfilled" to affected.sumOf { (it.holders - it.allocation) * it.diff },
            "top" to affected.sortedByDescending { it.holders - it.allocation }.take(10).map {
                obj("campaign_id" to it.id, "seller" to it.seller, "allocation" to it.allocation, "holders" to it.holders,
                    "excess" to it.holders - it.allocation, "loss_if_fulfilled" to (it.holders - it.allocation) * it.diff, "status" to it.status)
            },
        )
    }

    /** Pembekuan massal: jeda semua kampanye dan batalkan semua VA belum dibayar di gateway. */
    fun freezeAll(actor: String, reason: String): JsonObject {
        val ids = db.conn { c -> c.query("SELECT id FROM campaign WHERE status <> 'PAUSED'") { it.getLong(1) } }
        ids.forEach { inv.setStatus(it, "PAUSED", actor, reason) }
        val pending = db.conn { c -> c.query("SELECT id FROM orders WHERE status = 'PENDING_PAYMENT'") { it.getLong(1).toString() } }
        val cancelled = gateway.cancelUnpaid(pending)
        return obj("campaigns_paused" to ids.size, "pending_va_cancelled_at_gateway" to cancelled)
    }
}
