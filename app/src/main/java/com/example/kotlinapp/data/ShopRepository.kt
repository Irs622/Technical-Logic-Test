package com.example.kotlinapp.data

import kotlinx.serialization.json.*
import java.io.IOException
import java.time.Instant

private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.long
private fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int
private fun JsonObject.ln(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.inst(k: String) = Instant.parse(s(k))
private fun JsonObject.instN(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.let(Instant::parse)

private fun parseProduct(o: JsonObject) = Product(
    o.l("id"), o.s("name"), o.s("category"), o.s("unit_label"), o.s("origin"),
    o.l("price"), o.ln("compare_price"), o.i("stock"), o.s("description"),
)

private fun parseOrder(o: JsonObject) = FlashOrder(
    o.l("order_id"), o.s("order_no"), o.s("status"), o.l("campaign_id"), o.s("product_name"), o.l("unit_price"),
    o.inst("reserved_until"), o.instN("paid_at"),
    (o["va"] as? JsonObject)?.let { Va(it.s("bank"), it.s("number"), it.l("amount"), it.inst("expires_at")) },
)

private fun parseReceipt(o: JsonObject) = Receipt(
    o.s("order_no"), o.s("shipping"), o.l("subtotal"), o.l("shipping_fee"), o.l("total"), o.inst("created_at"),
    o["items"]!!.jsonArray.map { (it as JsonObject).let { i -> ReceiptItem(i.s("name"), i.i("qty"), i.l("unit_price")) } },
)

class ShopRepository(val settings: Settings, private val api: Api) {

    private suspend fun <T> run(parse: (JsonElement) -> T, request: suspend () -> RawResponse): Res<T> =
        try {
            val r = request()
            if (r.success && r.data != null) Res.Ok(parse(r.data!!))
            else Res.Err(r.errorCode ?: "ERROR", r.message.ifBlank { "Terjadi kesalahan." }, r.retryable || r.status >= 500, r.status,
                ((r.data as? JsonObject)?.get("order_id") as? JsonPrimitive)?.longOrNull)
        } catch (e: IOException) {
            Res.Err("NETWORK", "Tidak bisa terhubung ke server. Periksa alamat server di Akun.", true)
        } catch (e: Exception) {
            Res.Err("PARSE", "Data dari server tidak bisa dibaca.", false)
        }

    suspend fun products(category: String?, q: String?): Res<List<Product>> = run({ it.jsonArray.map { p -> parseProduct(p.jsonObject) } }) {
        val qs = buildList {
            if (category != null) add("category=" + java.net.URLEncoder.encode(category, "UTF-8"))
            if (!q.isNullOrBlank()) add("q=" + java.net.URLEncoder.encode(q, "UTF-8"))
        }.joinToString("&")
        api.call("GET", "/products" + if (qs.isEmpty()) "" else "?$qs")
    }

    suspend fun product(id: Long): Res<Product> = run({ parseProduct(it.jsonObject) }) { api.call("GET", "/products/$id") }

    suspend fun categories(): Res<List<Category>> = run({ it.jsonArray.map { c -> Category(c.jsonObject.s("name"), c.jsonObject.i("count")) } }) {
        api.call("GET", "/categories")
    }

    suspend fun balance(): Res<Long> = run({ it.jsonObject.l("balance") }) { api.call("GET", "/me") }
    suspend fun topup(amount: Long): Res<Long> = run({ it.jsonObject.l("balance") }) {
        api.call("POST", "/me/topup", buildJsonObject { put("amount", amount) })
    }

    suspend fun checkout(items: Map<Long, Int>, shipping: String): Res<Receipt> {
        val key = settings.checkoutKey()
        val body = buildJsonObject {
            put("shipping", shipping)
            putJsonArray("items") { items.forEach { (id, q) -> add(buildJsonObject { put("product_id", id); put("qty", q) }) } }
        }
        val res = run({ parseReceipt(it.jsonObject) }) { api.call("POST", "/checkout", body, key) }
        // Kunci dibuang hanya setelah hasil final; saat jaringan putus, kunci sama dipakai lagi.
        if (res is Res.Ok || (res is Res.Err && !res.retryable)) settings.clearCheckoutKey()
        return res
    }

    suspend fun shopOrders(): Res<List<Receipt>> = run({ it.jsonArray.map { r -> parseReceipt(r.jsonObject) } }) { api.call("GET", "/shop/orders") }

    // ---- Flash sale ----

    suspend fun campaign(id: Long): Res<Campaign> = run({
        val o = it.jsonObject
        Campaign(
            o.l("id"), o.s("product_name"), o.s("unit_label"), o.s("seller_name"), o.l("promo_price"), o.l("normal_price"),
            o.s("status"), o.inst("starts_at"), o.inst("ends_at"), o.jsonObject["stock_hint"]!!.jsonObject.i("remaining_approx"),
            o.i("payment_window_sec"), o.inst("server_time"),
        )
    }) { api.call("GET", "/campaigns/$id") }

    suspend fun flashOrders(): Res<List<FlashOrder>> = run({ it.jsonArray.map { o -> parseOrder(o.jsonObject) } }) { api.call("GET", "/orders") }

    suspend fun cancelOrder(id: Long): Res<FlashOrder> = run({ parseOrder(it.jsonObject) }) { api.call("POST", "/orders/$id/cancel") }

    /** Simulasi: pelanggan membayar VA lewat gateway tiruan. */
    suspend fun simulatePay(orderId: Long): Res<Unit> = run({ }) { api.call("POST", "/sim/payments/$orderId/pay") }

    suspend fun orderById(id: Long): Res<FlashOrder> = run({ parseOrder(it.jsonObject) }) { api.call("GET", "/orders/$id") }

    /**
     * Satu niat beli = satu Idempotency-Key. Hasil final menghapus key; hasil tidak pasti (jaringan/5xx/timeout)
     * mempertahankannya sehingga percobaan berikutnya aman (tidak membuat order ganda).
     */
    suspend fun purchase(campaignId: Long): PurchaseResult {
        val key = settings.purchaseKey(campaignId)
        val body = buildJsonObject { put("quantity", 1) }
        val r = try {
            api.call("POST", "/campaigns/$campaignId/purchase", body, key)
        } catch (e: IOException) {
            return PurchaseResult.Unknown("Memeriksa pesananmu…")
        }
        val result: PurchaseResult = when {
            r.success && r.data != null -> PurchaseResult.Reserved(parseOrder(r.data!!.jsonObject))
            r.errorCode == "SOLD_OUT" -> PurchaseResult.SoldOut
            r.errorCode == "ALREADY_PURCHASED" -> PurchaseResult.AlreadyPurchased(((r.data as? JsonObject)?.ln("order_id")))
            r.errorCode == "CAMPAIGN_NOT_ACTIVE" -> PurchaseResult.CampaignNotActive
            r.errorCode == "RATE_LIMITED" -> return PurchaseResult.RateLimited(r.retryAfter ?: 3)
            r.status >= 500 -> return PurchaseResult.Unknown(r.message.ifBlank { "Memeriksa pesananmu…" })
            else -> PurchaseResult.Failure(r.message.ifBlank { "Pembelian gagal." })
        }
        settings.clearPurchaseKey(campaignId)
        return result
    }
}
