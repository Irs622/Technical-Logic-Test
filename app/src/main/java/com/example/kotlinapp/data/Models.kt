package com.example.kotlinapp.data

import java.time.Instant

data class Product(
    val id: Long, val name: String, val category: String, val unitLabel: String, val origin: String,
    val price: Long, val comparePrice: Long?, val stock: Int, val description: String,
) {
    val discountPercent: Int?
        get() = comparePrice?.takeIf { it > price }?.let { (((it - price) * 100) / it).toInt() }
}

data class Category(val name: String, val count: Int)

data class Campaign(
    val id: Long, val productName: String, val unitLabel: String, val sellerName: String,
    val promoPrice: Long, val normalPrice: Long, val status: String,
    val startsAt: Instant, val endsAt: Instant, val remainingApprox: Int,
    val paymentWindowSec: Int, val serverTime: Instant,
)

data class Va(val bank: String, val number: String, val amount: Long, val expiresAt: Instant)

data class FlashOrder(
    val orderId: Long, val orderNo: String, val status: String, val campaignId: Long, val productName: String,
    val unitPrice: Long, val reservedUntil: Instant, val paidAt: Instant?, val va: Va?,
)

data class ReceiptItem(val name: String, val qty: Int, val unitPrice: Long)

data class Receipt(
    val orderNo: String, val shipping: String, val subtotal: Long, val shippingFee: Long, val total: Long,
    val createdAt: Instant, val items: List<ReceiptItem>,
)

sealed interface Res<out T> {
    data class Ok<T>(val value: T) : Res<T>
    data class Err(val code: String, val message: String, val retryable: Boolean, val status: Int = 0, val extra: Long? = null) : Res<Nothing>
}

/** Hasil final/non-final dari tombol Beli (docs/backend.md B.2). */
sealed interface PurchaseResult {
    data class Reserved(val order: FlashOrder) : PurchaseResult
    /** Masuk waiting room: pantau antrean, lalu ulangi pembelian dengan key yang sama. */
    data class Queued(val ticket: String, val position: Int, val retryAfterSec: Int) : PurchaseResult
    data object SoldOut : PurchaseResult
    data class AlreadyPurchased(val orderId: Long?) : PurchaseResult
    data object CampaignNotActive : PurchaseResult
    data class RateLimited(val retryAfterSec: Int) : PurchaseResult
    /** Hasil belum pasti (timeout/5xx): ulangi dengan Idempotency-Key YANG SAMA. */
    data class Unknown(val message: String) : PurchaseResult
    data class Failure(val message: String) : PurchaseResult
}

data class QueueState(val state: String, val position: Int, val retryAfterSec: Int)
