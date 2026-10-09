package com.example.kotlinapp

import com.example.kotlinapp.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import java.util.UUID

private class MemoryPrefs : AppPrefs {
    override var baseUrl = ""
    override var userId = 1L
    private val keys = HashMap<String, String>()
    override fun purchaseKey(campaignId: Long) = keys.getOrPut("p$campaignId") { UUID.randomUUID().toString() }
    override fun clearPurchaseKey(campaignId: Long) { keys.remove("p$campaignId") }
    override fun checkoutKey() = keys.getOrPut("c") { UUID.randomUUID().toString() }
    override fun clearCheckoutKey() { keys.remove("c") }
    fun hasKey(campaignId: Long) = keys.containsKey("p$campaignId")
}

private const val ORDER_JSON = """{"success":true,"data":{"order_id":7,"order_no":"TS-1","status":"PENDING_PAYMENT","campaign_id":1,
"product_name":"Mesin","unit_price":500000,"reserved_until":"2030-01-01T00:00:00Z","paid_at":null,
"va":{"bank":"BCA","number":"123","amount":500000,"expires_at":"2030-01-01T00:00:00Z"}},"message":"ok","error":null}"""

/** INV-14: klien memakai Idempotency-Key yang SAMA saat retry dan tidak membuat order ganda. */
class PurchaseIdempotencyTest {
    @get:Rule val timeout: Timeout = Timeout(15, TimeUnit.SECONDS)
    private lateinit var server: MockWebServer
    private lateinit var prefs: MemoryPrefs
    private lateinit var repo: ShopRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        prefs = MemoryPrefs().apply { baseUrl = server.url("/").toString().trimEnd('/') }
        repo = ShopRepository(prefs, Api(prefs))
    }
    @After fun tearDown() { server.shutdown() }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)
    private fun err(code: Int, errCode: String) =
        json(code, """{"success":false,"data":null,"message":"x","error":{"code":"$errCode","retryable":false}}""")

    /** Meniru loop retry di FlashViewModel.buy: ulangi selama hasil belum pasti. */
    private suspend fun buyUntilFinal(max: Int = 4): PurchaseResult {
        var r = repo.purchase(1)
        var n = 1
        while (r is PurchaseResult.Unknown && n < max) { r = repo.purchase(1); n++ }
        return r
    }

    private fun recordedKeys(): List<String?> =
        generateSequence { server.takeRequest(300, TimeUnit.MILLISECONDS) }.map { it.getHeader("Idempotency-Key") }.toList()

    @Test fun `server 503 lalu retry aplikasi memakai key yang sama dan sukses menghapus key`() = runBlocking {
        server.enqueue(err(503, "PAYMENT_UNAVAILABLE"))
        server.enqueue(json(201, ORDER_JSON))

        val first = repo.purchase(1)
        assertTrue("hasil pertama: $first", first is PurchaseResult.Unknown)
        assertTrue("key harus dipertahankan saat hasil belum pasti", prefs.hasKey(1))

        val last = buyUntilFinal()
        assertTrue("hasil akhir: $last", last is PurchaseResult.Reserved)
        assertEquals("PENDING_PAYMENT", (last as PurchaseResult.Reserved).order.status)

        val keys = recordedKeys()
        assertEquals(2, keys.size)
        assertNotNull(keys.first())
        assertEquals("semua percobaan memakai key yang sama", 1, keys.toSet().size)
        assertFalse("key dibuang setelah hasil final", prefs.hasKey(1))
    }

    @Test fun `koneksi putus setelah request terkirim diulang OkHttp dengan key yang sama`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json(201, ORDER_JSON))
        val r = buyUntilFinal()
        assertTrue("hasil: $r", r is PurchaseResult.Reserved)
        val keys = recordedKeys()
        assertTrue(keys.size >= 2)
        assertEquals("replay request harus membawa key yang sama", 1, keys.toSet().size)
    }

    @Test fun `server 503 tidak final dan key dipertahankan`() = runBlocking {
        server.enqueue(err(503, "PAYMENT_UNAVAILABLE"))
        assertTrue(repo.purchase(1) is PurchaseResult.Unknown)
        assertTrue(prefs.hasKey(1))
    }

    @Test fun `SOLD_OUT adalah final dan niat beli berikutnya memakai key baru`() = runBlocking {
        server.enqueue(err(409, "SOLD_OUT")); server.enqueue(err(409, "SOLD_OUT"))
        assertEquals(PurchaseResult.SoldOut, repo.purchase(1))
        assertFalse(prefs.hasKey(1))
        repo.purchase(1)
        val a = server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Idempotency-Key")
        val b = server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Idempotency-Key")
        assertNotEquals(a, b)
    }

    @Test fun `202 antrean dan 429 mempertahankan key`() = runBlocking {
        server.enqueue(json(202, """{"success":true,"data":{"state":"WAITING","ticket":"1-1","position_approx":42,"retry_after_sec":3},"message":"Kamu dalam antrean.","error":null}"""))
        val q = repo.purchase(1)
        assertTrue(q is PurchaseResult.Queued)
        assertEquals(42, (q as PurchaseResult.Queued).position)
        assertTrue(prefs.hasKey(1))

        server.enqueue(err(429, "RATE_LIMITED").addHeader("Retry-After", "3"))
        assertEquals(PurchaseResult.RateLimited(3), repo.purchase(1))
        assertTrue(prefs.hasKey(1))
    }

    @Test fun `ALREADY_PURCHASED membawa order id dan final`() = runBlocking {
        server.enqueue(json(409, """{"success":false,"data":{"order_id":9},"message":"x","error":{"code":"ALREADY_PURCHASED","retryable":false}}"""))
        assertEquals(PurchaseResult.AlreadyPurchased(9L), repo.purchase(1))
        assertFalse(prefs.hasKey(1))
    }

    @Test fun `checkout 503 lalu retry memakai key yang sama`() = runBlocking {
        server.enqueue(err(503, "UNAVAILABLE"))
        server.enqueue(err(409, "OUT_OF_STOCK"))
        var r: Res<Receipt> = repo.checkout(mapOf(1L to 1), "REG")
        var n = 1
        while (r is Res.Err && r.retryable && n < 4) { r = repo.checkout(mapOf(1L to 1), "REG"); n++ }
        assertTrue(r is Res.Err && r.code == "OUT_OF_STOCK")
        val keys = recordedKeys()
        assertEquals(2, keys.size)
        assertEquals(1, keys.toSet().size)
    }
}
