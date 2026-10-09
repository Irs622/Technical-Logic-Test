package shop

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/** Worker latar: kedaluwarsa reservasi, rekonsiliasi pembayaran, monitor invariant (docs/backend.md A.4-A.5). */
class Workers(private val inv: Inventory, private val db: Db, private val scope: CoroutineScope, private val alerter: Alerter = LogAlerter()) {
    private val log = LoggerFactory.getLogger("workers")
    @Volatile var autoPaused = false

    fun start(expireEveryMs: Long = 10_000, reconcileEveryMs: Long = 60_000, monitorEveryMs: Long = 5_000) {
        scope.launch { while (isActive) { runCatching { inv.expireDue() }.onFailure { log.error("expire", it) }; delay(expireEveryMs) } }
        scope.launch { while (isActive) { delay(reconcileEveryMs); runCatching { inv.reconcilePayments() }.onFailure { log.error("reconcile", it) } } }
        scope.launch { while (isActive) { runCatching { monitorOnce() }.onFailure { log.error("monitor", it) }; delay(monitorEveryMs) } }
    }

    /** Pelanggaran invariant -> alert + kill switch otomatis (jeda kampanye). */
    fun monitorOnce(): List<String> {
        val v = inv.invariantViolations()
        if (v.isNotEmpty()) {
            alerter.alert("Invariant dilanggar, kampanye dijeda otomatis", v)
            db.conn { c -> c.query("SELECT id FROM campaign WHERE status = 'ACTIVE'") { it.getLong(1) } }.forEach {
                inv.setStatus(it, "PAUSED", "monitor", "auto-pause: ${v.first()}")
                autoPaused = true
            }
        }
        return v
    }
}
