package shop

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/** Koneksi H2 (mode MySQL). Semua keputusan waktu memakai jam database, bukan jam aplikasi. */
class Db(name: String = "shop", poolSize: Int = 32, mysqlUrl: String? = null, user: String? = null, pw: String? = null) : AutoCloseable {
    val ds: HikariDataSource
    val mysql: Boolean = mysqlUrl != null

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Sql.mysql = mysql
        val cfg = HikariConfig().apply {
            jdbcUrl = mysqlUrl ?: "jdbc:h2:mem:$name;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"
            if (user != null) setUsername(user)
            if (!pw.isNullOrEmpty()) setPassword(pw)
            maximumPoolSize = poolSize
            minimumIdle = poolSize
            isAutoCommit = true
            poolName = "pool-$name"
        }
        ds = HikariDataSource(cfg)
        try { initSchema() } catch (e: Throwable) { ds.close(); throw e }
    }

    private fun initSchema() {
        var schema = Db::class.java.getResourceAsStream("/schema.sql")!!.bufferedReader().readText()
        if (mysql) {
            schema = schema.replace(Regex("(?<!CURRENT_)TIMESTAMP\\(3\\)"), "DATETIME(3)")   // jangan rusak CURRENT_TIMESTAMP(3)
            val tables = Regex("CREATE TABLE (\\w+)").findAll(schema).map { it.groupValues[1] }.toList()
            ds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("SET FOREIGN_KEY_CHECKS = 0")
                    tables.forEach { st.execute("DROP TABLE IF EXISTS $it") }
                    st.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        }
        ds.connection.use { c -> c.createStatement().use { it.execute(schema) } }
    }

    /** Transaksi dengan retry terbatas untuk deadlock / lock timeout (maks 2 kali ulang). */
    fun <T> tx(block: (Connection) -> T): T {
        var attempt = 0
        while (true) {
            ds.connection.use { c ->
                c.autoCommit = false
                try {
                    val r = block(c)
                    c.commit()
                    return r
                } catch (e: Throwable) {
                    runCatching { c.rollback() }
                    if (e is SQLException && e.isTransient() && attempt < 2) {
                        attempt++
                    } else throw e
                } finally {
                    c.autoCommit = true
                }
            }
        }
    }

    /** Operasi satu statement tanpa transaksi eksplisit. */
    fun <T> conn(block: (Connection) -> T): T = ds.connection.use(block)

    override fun close() = ds.close()

    companion object {
        private val seq = AtomicInteger()
        private var lastMysql: Db? = null

        /**
         * H2 in-memory (default) atau MySQL 8 bila env MYSQL_URL diset, mis.
         * env: MYSQL_URL (jdbc:mysql://host:port/db), MYSQL_USER, dan opsional MYSQL_PASSWORD
         * Seluruh test suite dapat dijalankan terhadap MySQL dengan env tersebut.
         */
        @Synchronized
        fun fresh(): Db {
            val url = System.getenv("MYSQL_URL")?.takeIf { it.isNotBlank() }
            if (url == null) return Db("shop${seq.incrementAndGet()}_${System.nanoTime()}")
            lastMysql?.close()
            val full = url + (if ('?' in url) "&" else "?") +
                "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowMultiQueries=true&useSSL=false&allowPublicKeyRetrieval=true"
            return Db("mysql${seq.incrementAndGet()}", 24, full, System.getenv("MYSQL_USER") ?: "root", System.getenv("MYSQL_PASSWORD")).also { lastMysql = it }
        }
    }
}

fun SQLException.isTransient() = sqlState == "40001" || sqlState == "50200" || errorCode == 50200 || errorCode == 1213 || errorCode == 1205
fun SQLException.isUniqueViolation() = sqlState == "23505" || errorCode == 1062
fun SQLException.isCheckViolation() = sqlState == "23513" || errorCode == 3819

fun Connection.exec(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { ps -> bind(ps, args); ps.executeUpdate() }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { ps ->
        bind(ps, args)
        ps.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out += map(rs)
            out
        }
    }

fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    query(sql, *args, map = map).firstOrNull()

fun Connection.insertReturningId(sql: String, vararg args: Any?): Long =
    prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
        bind(ps, args)
        ps.executeUpdate()
        ps.generatedKeys.use { it.next(); it.getLong(1) }
    }

private fun bind(ps: java.sql.PreparedStatement, args: Array<out Any?>) {
    args.forEachIndexed { i, a ->
        when (a) {
            null -> ps.setObject(i + 1, null)
            is Instant -> ps.setTimestamp(i + 1, Timestamp.from(a))
            else -> ps.setObject(i + 1, a)
        }
    }
}

fun ResultSet.instant(col: String): Instant? = getTimestamp(col)?.toInstant()
