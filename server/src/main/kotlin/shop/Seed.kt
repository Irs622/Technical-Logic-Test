package shop

import java.sql.Connection

object Seed {
    const val CAMPAIGN_ID = 1L
    const val DEMO_USER = 1L

    private data class P(
        val id: Long, val name: String, val cat: String, val unit: String, val origin: String,
        val price: Long, val compare: Long?, val stock: Int, val desc: String,
    )

    private val products = listOf(
        P(1, "Kopi Gayo Arabika", "Bahan Dapur", "250 g", "Takengon", 68_000, 85_000, 40,
            "Biji kopi sangrai medium dari dataran tinggi Gayo. Aroma cokelat dan rempah, asam rendah."),
        P(2, "Gula Aren Cair", "Bahan Dapur", "500 ml", "Garut", 32_500, null, 60,
            "Gula aren cair tanpa pengawet. Cocok untuk kopi susu dan kolak."),
        P(3, "Sambal Roa Botol", "Makanan", "200 g", "Manado", 45_000, null, 25,
            "Sambal ikan roa asap, pedas sedang. Tahan 6 bulan sebelum dibuka."),
        P(4, "Beras Pandan Wangi", "Bahan Dapur", "5 kg", "Cianjur", 78_000, 84_000, 80,
            "Beras pulen beraroma pandan, kemasan 5 kg."),
        P(5, "Tas Anyaman Pandan", "Kerajinan", "1 pcs", "Tasikmalaya", 125_000, null, 12,
            "Tas anyaman daun pandan dengan lapisan dalam kain katun. Dianyam tangan."),
        P(6, "Keripik Tempe Original", "Makanan", "150 g", "Malang", 18_000, null, 120,
            "Keripik tempe tipis renyah, digoreng dengan minyak baru setiap batch."),
        P(7, "Teh Tarik Kayu Aro", "Minuman", "100 g", "Kerinci", 38_000, 42_000, 35,
            "Teh hitam dari kebun tertinggi di Sumatra, rasa tegas dan bersih."),
        P(8, "Madu Hutan Sumbawa", "Minuman", "350 ml", "Sumbawa", 95_000, null, 18,
            "Madu hutan murni, belum dipanaskan. Dapat mengkristal alami."),
        P(9, "Kain Batik Tulis Cap", "Kerajinan", "2 m", "Pekalongan", 210_000, 240_000, 9,
            "Katun primisima motif parang, kombinasi tulis dan cap."),
        P(10, "Kerupuk Kulit Sapi", "Makanan", "200 g", "Padang", 27_500, null, 70,
            "Kerupuk kulit renyah, bumbu balado. Belum digoreng."),
        P(11, "Minyak Kelapa Murni", "Bahan Dapur", "250 ml", "Manado", 36_000, null, 45,
            "Minyak kelapa dingin-tekan tanpa bahan tambahan."),
        P(12, "Kopi Bubuk Toraja", "Bahan Dapur", "200 g", "Tana Toraja", 74_000, null, 30,
            "Kopi bubuk halus untuk seduh tubruk, body penuh."),
        // Produk kampanye flash sale (live commerce)
        P(100, "Mesin Espresso Manual 15 Bar", "Elektronik", "1 unit", "Bandung",
            1_000_000, null, 250, "Mesin espresso manual 15 bar, boiler stainless. Stok gudang dijual dengan harga normal."),
    )

    /** Isi data awal. Aman dipanggil pada DB baru. */
    fun init(db: Db) = db.tx { c ->
        c.exec("INSERT INTO users (id, name, balance) VALUES (?, ?, ?)", DEMO_USER, "Pelanggan Demo", 1_000_000L)
        products.forEach {
            c.exec(
                "INSERT INTO products (id,name,category,unit_label,origin,price,compare_price,stock,description) VALUES (?,?,?,?,?,?,?,?,?)",
                it.id, it.name, it.cat, it.unit, it.origin, it.price, it.compare, it.stock, it.desc,
            )
        }
        resetCampaign(c, 100, 30, 3600)
    }

    /**
     * Reset kampanye flash sale: alokasi, lama sesi (menit), jendela bayar (detik).
     * Sesi dimulai 1 menit yang lalu agar langsung aktif. Menghapus semua order kampanye dan data legacy.
     */
    fun resetCampaign(c: Connection, allocation: Int, windowMin: Int, paymentWindowSec: Int) {
        listOf(
            "DELETE FROM payment_va", "DELETE FROM payment_events", "DELETE FROM inventory_ledger",
            "DELETE FROM order_events", "DELETE FROM orders", "DELETE FROM campaign_audit",
            "DELETE FROM compensations", "DELETE FROM campaign", "DELETE FROM legacy_va", "DELETE FROM legacy_stock",
        ).forEach { c.exec(it) }
        c.exec("UPDATE products SET stock = 250 WHERE id = 100")
        createCampaign(c, CAMPAIGN_ID, "Toko Kopi Sudut", allocation, windowMin, paymentWindowSec)
    }

    fun createCampaign(c: Connection, id: Long, seller: String, allocation: Int, windowMin: Int, paymentWindowSec: Int) {
        c.exec(
            """INSERT INTO campaign (id, product_id, seller_name, promo_price, normal_price, allocation,
               payment_window_sec, status, starts_at, ends_at)
               VALUES (?, 100, ?, 500000, 1000000, ?, ?, 'ACTIVE',
               ${Sql.dateAdd("MINUTE", "-1")}, ${Sql.dateAdd("MINUTE", "?")})""",
            id, seller, allocation, paymentWindowSec, windowMin,
        )
        c.exec("INSERT INTO legacy_stock (campaign_id, allocation, stock) VALUES (?, ?, ?)", id, allocation, allocation)
    }
}
