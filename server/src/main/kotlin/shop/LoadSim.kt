package shop

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Pembeli virtual yang menyerbu kampanye secara paralel (tanpa HTTP, langsung ke layanan). */
class LoadSim(private val inv: Inventory, private val legacy: Legacy, private val db: Db) {

    suspend fun run(mode: String, users: Int, allocation: Int, concurrency: Int = 256, doubleTap: Boolean = true,
                    campaignId: Long = Seed.CAMPAIGN_ID, admission: Boolean = false) =
        coroutineScope {
            val started = System.nanoTime()
            val created = AtomicInteger(); val soldOut = AtomicInteger(); val other = AtomicInteger(); val queued = AtomicInteger()
            val gate = Semaphore(concurrency)
            val jobs = (1..users).map { u ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (mode == "legacy") {
                            when (legacy.purchase(u.toLong(), campaignId)) {
                                Legacy.Result.VaIssued -> created.incrementAndGet()
                                Legacy.Result.SoldOut -> soldOut.incrementAndGet()
                            }
                        } else {
                            if (admission && !inv.admitted(u.toLong(), campaignId)) { queued.incrementAndGet(); return@withPermit }
                            val key = "load-$u"
                            val first = inv.purchase(u.toLong(), campaignId, key)
                            if (doubleTap) inv.purchase(u.toLong(), campaignId, key)   // tap ganda / retry klien
                            when (first) {
                                is Outcome.Ok -> if (!first.replay) created.incrementAndGet() else other.incrementAndGet()
                                Outcome.SoldOut -> soldOut.incrementAndGet()
                                else -> other.incrementAndGet()
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
            val active = if (mode == "legacy") legacy.issued(campaignId) else activeOrders(campaignId)
            val ms = (System.nanoTime() - started) / 1_000_000
            obj(
                "mode" to mode, "users" to users, "allocation" to allocation,
                "accepted" to created.get(), "queued_without_db" to queued.get(), "sold_out" to soldOut.get(), "other" to other.get(),
                "active_orders_or_va" to active, "oversell" to maxOf(0, active - allocation),
                "stock_counter_left" to (if (mode == "legacy") legacy.stockLeft(campaignId) else null),
                "duration_ms" to ms, "violations" to (if (mode == "legacy") emptyList<String>() else inv.invariantViolations()),
            )
        }

    private fun activeOrders(campaignId: Long): Int = db.conn { c ->
        c.queryOne(
            "SELECT COUNT(*) FROM orders WHERE campaign_id = ? AND status IN ('PENDING_PAYMENT','PAID','FULFILLED')",
            campaignId,
        ) { it.getInt(1) }
    } ?: 0
}
