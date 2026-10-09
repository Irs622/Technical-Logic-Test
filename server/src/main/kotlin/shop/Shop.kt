package shop

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

class OutOfStock(val productName: String) : RuntimeException()
class InsufficientBalance(val deficit: Long) : RuntimeException()
class BadItems(msg: String) : RuntimeException(msg)

/** Toko reguler: katalog + checkout dengan Saldo Simulasi. Stok & saldo juga dikurangi lewat UPDATE bersyarat. */
class Shop(private val db: Db) {
    private val seq = AtomicLong()

    fun ensureUser(id: Long) = db.conn { c ->
        c.exec("MERGE INTO users (id, name, balance) KEY (id) VALUES (?, ?, COALESCE((SELECT balance FROM users WHERE id = ?), 1000000))",
            id, "Pelanggan $id", id)
    }

    private fun productJson(rs: java.sql.ResultSet) = obj(
        "id" to rs.getLong("id"), "name" to rs.getString("name"), "category" to rs.getString("category"),
        "unit_label" to rs.getString("unit_label"), "origin" to rs.getString("origin"),
        "price" to rs.getLong("price"),
        "compare_price" to rs.getLong("compare_price").takeIf { !rs.wasNull() },
        "stock" to rs.getInt("stock"), "description" to rs.getString("description"),
    )

    fun products(category: String?, q: String?): List<JsonObject> = db.conn { c ->
        c.query(
            """SELECT * FROM products WHERE id < 100
                 AND (? IS NULL OR category = ?) AND (? IS NULL OR LOWER(name) LIKE ?) ORDER BY id""",
            category, category, q, q?.let { "%${it.lowercase()}%" }, map = ::productJson,
        )
    }

    fun product(id: Long): JsonObject? = db.conn { c -> c.queryOne("SELECT * FROM products WHERE id = ?", id, map = ::productJson) }

    fun categories(): List<JsonObject> = db.conn { c ->
        c.query("SELECT category, COUNT(*) FROM products WHERE id < 100 GROUP BY category ORDER BY category") {
            obj("name" to it.getString(1), "count" to it.getInt(2))
        }
    }

    fun balance(userId: Long): Long = db.conn { c -> c.queryOne("SELECT balance FROM users WHERE id = ?", userId) { it.getLong(1) } } ?: 0

    fun topup(userId: Long, amount: Long): Long {
        require(amount in 1..1_000_000) { "Jumlah isi ulang 1 sampai 1.000.000" }
        db.conn { c -> c.exec("UPDATE users SET balance = balance + ? WHERE id = ?", amount, userId) }
        return balance(userId)
    }

    fun checkout(userId: Long, key: String, items: List<Pair<Long, Int>>, shipping: String): JsonObject {
        existingReceipt(userId, key)?.let { return it }
        if (items.isEmpty() || items.any { it.second !in 1..99 }) throw BadItems("Keranjang kosong atau jumlah tidak valid")
        val fee = when (shipping) { "REG" -> 12_000L; "KILAT" -> 25_000L; else -> throw BadItems("Opsi kirim tidak dikenal") }
        val merged = items.groupBy({ it.first }, { it.second }).map { it.key to it.value.sum() }.sortedBy { it.first }  // urutan lock tetap

        val id = try {
            db.tx { c ->
                var subtotal = 0L
                val lines = merged.map { (pid, qty) ->
                    val p = c.queryOne("SELECT name, price FROM products WHERE id = ? AND id < 100", pid) { it.getString(1) to it.getLong(2) }
                        ?: throw BadItems("Produk $pid tidak ditemukan")
                    if (c.exec("UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?", qty, pid, qty) == 0)
                        throw OutOfStock(p.first)
                    subtotal += p.second * qty
                    Triple(pid, p, qty)
                }
                val total = subtotal + fee
                if (c.exec("UPDATE users SET balance = balance - ? WHERE id = ? AND balance >= ?", total, userId, total) == 0)
                    throw InsufficientBalance(total - balance(userId))
                val oid = c.insertReturningId(
                    "INSERT INTO shop_orders (order_no, user_id, idempotency_key, shipping, subtotal, shipping_fee, total) VALUES (?,?,?,?,?,?,?)",
                    "TK-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "") + "-" + seq.incrementAndGet().toString().padStart(5, '0'),
                    userId, key, shipping, subtotal, fee, total,
                )
                lines.forEach { (pid, p, qty) ->
                    c.exec("INSERT INTO shop_order_items (shop_order_id, product_id, name, qty, unit_price) VALUES (?,?,?,?,?)", oid, pid, p.first, qty, p.second)
                }
                oid
            }
        } catch (e: java.sql.SQLException) {
            if (e.isUniqueViolation()) return existingReceipt(userId, key)!! else throw e
        }
        return receipt(id)!!
    }

    private fun existingReceipt(userId: Long, key: String): JsonObject? {
        val id = db.conn { c -> c.queryOne("SELECT id FROM shop_orders WHERE user_id = ? AND idempotency_key = ?", userId, key) { it.getLong(1) } }
        return id?.let(::receipt)
    }

    private fun receipt(id: Long): JsonObject? = db.conn { c ->
        val head = c.queryOne("SELECT order_no, shipping, subtotal, shipping_fee, total, created_at FROM shop_orders WHERE id = ?", id) {
            obj("order_no" to it.getString(1), "shipping" to it.getString(2), "subtotal" to it.getLong(3),
                "shipping_fee" to it.getLong(4), "total" to it.getLong(5), "created_at" to it.instant("created_at"),
                "payment" to "Saldo Simulasi")
        } ?: return@conn null
        val items = c.query("SELECT product_id, name, qty, unit_price FROM shop_order_items WHERE shop_order_id = ? ORDER BY id", id) {
            obj("product_id" to it.getLong(1), "name" to it.getString(2), "qty" to it.getInt(3), "unit_price" to it.getLong(4))
        }
        JsonObject(head + ("items" to toEl(items)))
    }

    fun orders(userId: Long): List<JsonObject> {
        val ids = db.conn { c -> c.query("SELECT id FROM shop_orders WHERE user_id = ? ORDER BY id DESC LIMIT 50", userId) { it.getLong(1) } }
        return ids.mapNotNull(::receipt)
    }
}
