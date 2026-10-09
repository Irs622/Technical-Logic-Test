package shop

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class GatewayTimeout : RuntimeException("Gateway timeout")
class GatewayFailure : RuntimeException("Gateway menolak permintaan")

enum class GatewayMode { OK, FAIL, TIMEOUT }

data class GatewayVa(
    val orderRef: String,
    val bank: String,
    val number: String,
    val providerRef: String,
    val amount: Long,
    val expiresAt: Instant,
    @Volatile var paid: Boolean = false,
    @Volatile var refunded: Boolean = false,
    @Volatile var cancelled: Boolean = false,
)

object Signature {
    fun hmac(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Gateway pembayaran TIRUAN. Perilakunya dapat diatur supaya skenario insiden bisa diuji:
 * VA gagal pasti (FAIL), VA terbentuk tapi respons timeout (TIMEOUT), callback ganda/terlambat/hilang.
 */
class MockGateway(val secret: String = "dev-secret") {
    @Volatile var mode: GatewayMode = GatewayMode.OK
    private val vas = ConcurrentHashMap<String, GatewayVa>()
    private val seq = java.util.concurrent.atomic.AtomicLong(1000)

    /** Idempoten per orderRef: panggilan ulang mengembalikan VA yang sama. */
    fun createVa(orderRef: String, amount: Long, expiresAt: Instant): GatewayVa {
        if (mode == GatewayMode.FAIL) throw GatewayFailure()
        val va = vas.computeIfAbsent(orderRef) {
            val n = seq.incrementAndGet()
            GatewayVa(orderRef, "BCA", "80770" + n.toString().padStart(11, '0'), "gw_$n", amount, expiresAt)
        }
        if (mode == GatewayMode.TIMEOUT) throw GatewayTimeout()
        return va
    }

    fun lookup(orderRef: String): GatewayVa? = vas[orderRef]

    /** Pelanggan membayar VA. Mengembalikan body callback yang sudah ditandatangani, atau null bila VA tidak ada. */
    fun pay(orderRef: String): SignedEvent? {
        val va = vas[orderRef] ?: return null
        if (va.cancelled) return null
        va.paid = true
        return signedEvent(va, "evt_${va.providerRef}")
    }

    fun signedEvent(va: GatewayVa, eventId: String): SignedEvent {
        val body = obj(
            "event_id" to eventId, "order_id" to va.orderRef.toLong(),
            "amount" to va.amount, "type" to "PAID",
        ).toString()
        return SignedEvent(body, Signature.hmac(secret, body))
    }

    fun refund(orderRef: String) { vas[orderRef]?.refunded = true }

    /** Pembatalan massal VA yang belum dibayar (dipakai saat insiden untuk membekukan eksposur). */
    fun cancelUnpaid(orderRefs: Collection<String>): Int {
        var n = 0
        for (r in orderRefs) vas[r]?.let { if (!it.paid && !it.cancelled) { it.cancelled = true; n++ } }
        return n
    }

    fun paidCount() = vas.values.count { it.paid }
    fun clear() = vas.clear()
}

data class SignedEvent(val body: String, val signature: String)
