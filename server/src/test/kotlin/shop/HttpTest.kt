package shop

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

class HttpTest {
    private fun HttpRequestBuilder.auth(u: Long) = header(HttpHeaders.Authorization, "Bearer demo-$u")
    private suspend fun HttpResponse.j(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.data() = this["data"]!!.jsonObject
    private fun JsonObject.code() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test fun `purchase 201 lalu replay 200 dengan key sama`() = testApplication {
        application { module(App()) }
        val a = client.post("/campaigns/1/purchase") { auth(1); header("Idempotency-Key", "k1"); setBody("""{"quantity":1}""") }
        assertEquals(HttpStatusCode.Created, a.status)
        val b = client.post("/campaigns/1/purchase") { auth(1); header("Idempotency-Key", "k1"); setBody("""{"quantity":1}""") }
        assertEquals(HttpStatusCode.OK, b.status)
        assertEquals(a.j().data()["order_id"], b.j().data()["order_id"])
        assertEquals("PENDING_PAYMENT", a.j().data()["status"]!!.jsonPrimitive.content)
        assertNotNull(a.j().data()["va"]!!.jsonObject["number"])
    }

    @Test fun `validasi dan auth`() = testApplication {
        application { module(App()) }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/campaigns/1/purchase") { header("Idempotency-Key", "k") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/campaigns/1/purchase") { auth(1) }.status)
        val q = client.post("/campaigns/1/purchase") { auth(1); header("Idempotency-Key", "k"); setBody("""{"quantity":2}""") }
        assertEquals(HttpStatusCode.BadRequest, q.status)
    }

    @Test fun `stok habis 409 SOLD_OUT dan sudah beli 409 ALREADY_PURCHASED`() = testApplication {
        val app = App()
        app.db.tx { Seed.resetCampaign(it, 1, 30, 3600) }
        application { module(app) }
        assertEquals(HttpStatusCode.Created, client.post("/campaigns/1/purchase") { auth(1); header("Idempotency-Key", "a") }.status)
        val so = client.post("/campaigns/1/purchase") { auth(2); header("Idempotency-Key", "b") }
        assertEquals(HttpStatusCode.Conflict, so.status); assertEquals("SOLD_OUT", so.j().code())
        val al = client.post("/campaigns/1/purchase") { auth(1); header("Idempotency-Key", "c") }
        assertEquals("ALREADY_PURCHASED", al.j().code())
        assertNotNull(al.j().data()["order_id"])
    }

    @Test fun `rate limit 429 dengan Retry-After`() = testApplication {
        application { module(App()) }
        var last: HttpResponse? = null
        repeat(12) { last = client.post("/campaigns/1/purchase") { auth(9); header("Idempotency-Key", "k") } }
        assertEquals(HttpStatusCode.TooManyRequests, last!!.status)
        assertEquals("3", last!!.headers[HttpHeaders.RetryAfter])
    }

    @Test fun `alur bayar VA lewat gateway tiruan menandai PAID`() = testApplication {
        application { module(App()) }
        val id = client.post("/campaigns/1/purchase") { auth(1); header("Idempotency-Key", "k") }.j().data()["order_id"]!!.jsonPrimitive.long
        val pay = client.post("/sim/payments/$id/pay?behavior=duplicate")
        assertEquals(HttpStatusCode.OK, pay.status)
        val cb = pay.j().data()["callbacks"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("CONFIRMED", "DUPLICATE"), cb)
        val o = client.get("/orders/$id") { auth(1) }.j().data()
        assertEquals("PAID", o["status"]!!.jsonPrimitive.content)
    }

    @Test fun `callback dengan tanda tangan salah 403`() = testApplication {
        application { module(App()) }
        val r = client.post("/internal/payments/callback") { header("X-Signature", "x"); setBody("""{"event_id":"e","order_id":1}""") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test fun `toko checkout memotong stok dan saldo, replay idempoten, saldo kurang ditolak`() = testApplication {
        application { module(App()) }
        val body = """{"items":[{"product_id":1,"qty":2},{"product_id":2,"qty":1}],"shipping":"REG"}"""
        val a = client.post("/checkout") { auth(1); header("Idempotency-Key", "c1"); setBody(body) }
        assertEquals(HttpStatusCode.Created, a.status)
        val r = a.j().data()
        assertEquals(2 * 68_000L + 32_500 + 12_000, r["total"]!!.jsonPrimitive.long)
        val again = client.post("/checkout") { auth(1); header("Idempotency-Key", "c1"); setBody(body) }
        assertEquals(r["order_no"], again.j().data()["order_no"])
        val me = client.get("/me") { auth(1) }.j().data()
        assertEquals(1_000_000L - r["total"]!!.jsonPrimitive.long, me["balance"]!!.jsonPrimitive.long)
        assertEquals(38, client.get("/products/1").j().data()["stock"]!!.jsonPrimitive.int)

        val big = client.post("/checkout") { auth(1); header("Idempotency-Key", "c2"); setBody("""{"items":[{"product_id":9,"qty":5}],"shipping":"KILAT"}""") }
        assertEquals("INSUFFICIENT_BALANCE", big.j().code())
        assertEquals(9, client.get("/products/9").j().data()["stock"]!!.jsonPrimitive.int)   // stok ikut di-rollback
    }

    @Test fun `stok toko tidak bisa minus saat checkout paralel`() = testApplication {
        val app = App(); application { module(app) }
        // produk 9 stok 9; 20 pelanggan masing-masing beli 1 -> maksimal 9 sukses
        val codes = (1L..20L).map { u ->
            kotlinx.coroutines.runBlocking {
                client.post("/checkout") { auth(u); header("Idempotency-Key", "p$u"); setBody("""{"items":[{"product_id":9,"qty":1}],"shipping":"REG"}""") }.status
            }
        }
        assertEquals(9, codes.count { it == HttpStatusCode.Created })
        assertEquals(11, codes.count { it == HttpStatusCode.Conflict })
    }
}
