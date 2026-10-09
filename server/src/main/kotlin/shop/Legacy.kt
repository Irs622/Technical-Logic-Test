package shop

import kotlinx.coroutines.delay

/**
 * IMPLEMENTASI LAMA (BUG) — hanya untuk mereproduksi insiden.
 *
 * Persis alur di studi kasus: baca stok, putuskan lanjut bila > 0, lalu tulis stok baru sebagai
 * langkah terpisah (tanpa transaksi, tanpa UPDATE bersyarat). [latencyMs] memodelkan jeda jaringan/CPU
 * antara baca dan tulis di server nyata; pada trafik tinggi banyak request membaca nilai yang sama
 * (lost update) sehingga VA terbit lebih banyak daripada alokasi.
 */
class Legacy(private val db: Db, private val latencyMs: Long = 2) {
    private val seq = java.util.concurrent.atomic.AtomicLong()

    sealed interface Result { data object VaIssued : Result; data object SoldOut : Result }

    suspend fun purchase(userId: Long, campaignId: Long): Result {
        val stock = db.conn { c -> c.queryOne("SELECT stock FROM legacy_stock WHERE campaign_id = ?", campaignId) { it.getInt(1) } } ?: 0
        if (stock <= 0) return Result.SoldOut
        delay(latencyMs)                                              // pekerjaan lain di antara baca & tulis
        db.conn { c -> c.exec("UPDATE legacy_stock SET stock = ? WHERE campaign_id = ?", stock - 1, campaignId) }
        db.conn { c -> c.exec("INSERT INTO legacy_va (campaign_id, user_id, seq) VALUES (?, ?, ?)", campaignId, userId, seq.incrementAndGet()) }
        return Result.VaIssued
    }

    fun issued(campaignId: Long): Int = db.conn { c -> c.queryOne("SELECT COUNT(*) FROM legacy_va WHERE campaign_id = ?", campaignId) { it.getInt(1) } } ?: 0
    fun stockLeft(campaignId: Long): Int = db.conn { c -> c.queryOne("SELECT stock FROM legacy_stock WHERE campaign_id = ?", campaignId) { it.getInt(1) } } ?: 0
}
