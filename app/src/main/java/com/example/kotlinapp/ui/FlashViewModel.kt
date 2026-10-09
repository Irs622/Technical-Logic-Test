package com.example.kotlinapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kotlinapp.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tone { Info, Warn, Error }
data class Notice(val text: String, val tone: Tone)

data class FlashUi(
    val loading: Boolean = true,
    val error: String? = null,
    val campaign: Campaign? = null,
    /** serverTime - waktu perangkat; dipakai agar countdown tidak bergantung jam perangkat. */
    val offsetMs: Long = 0,
    val order: FlashOrder? = null,
    val submitting: Boolean = false,
    val notice: Notice? = null,
)

const val CAMPAIGN_ID = 1L

/** Alur beli flash sale. Stok yang tampil hanya perkiraan; keputusan ada di server. */
class FlashViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val repo = ShopRepository(settings, Api(settings))

    private val _ui = MutableStateFlow(FlashUi())
    val ui: StateFlow<FlashUi> = _ui.asStateFlow()
    private var poll: Job? = null

    private var users = 0

    /** Dipanggil tiap layar yang butuh data kampanye; polling berjalan selama ada minimal satu layar. */
    fun start() {
        users++
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (true) {
                refresh()
                delay(4000)
            }
        }
    }

    fun stop() { users = maxOf(0, users - 1); if (users == 0) poll?.cancel() }

    private suspend fun refresh() {
        val c = repo.campaign(CAMPAIGN_ID)
        val o = repo.flashOrders()
        _ui.update { s ->
            when (c) {
                is Res.Ok -> {
                    val mine = (o as? Res.Ok)?.value?.filter { it.campaignId == CAMPAIGN_ID }
                    s.copy(
                        loading = false, error = null, campaign = c.value,
                        offsetMs = c.value.serverTime.toEpochMilli() - System.currentTimeMillis(),
                        order = mine?.let { list ->
                            list.firstOrNull { it.status == "PENDING_PAYMENT" || it.status == "PAID" || it.status == "FULFILLED" } ?: list.firstOrNull()
                        } ?: s.order,
                    )
                }
                is Res.Err -> s.copy(loading = false, error = if (s.campaign == null) c.message else null)
            }
        }
    }

    fun retry() { _ui.update { it.copy(loading = true, error = null) }; viewModelScope.launch { refresh() } }

    fun buy() {
        if (_ui.value.submitting) return
        _ui.update { it.copy(submitting = true, notice = null) }
        viewModelScope.launch {
            var result: PurchaseResult = repo.purchase(CAMPAIGN_ID)
            var attempts = 1
            // Waiting room: tunggu giliran, lalu beli dengan Idempotency-Key yang sama.
            var polls = 0
            while (result is PurchaseResult.Queued && polls < 120) {
                val q = result as PurchaseResult.Queued
                _ui.update { it.copy(notice = Notice("Kamu dalam antrean. Perkiraan posisi ${q.position}.", Tone.Info)) }
                delay(q.retryAfterSec * 1000L)
                val st = repo.queueState(q.ticket)
                if (st is Res.Ok && st.value.state == "REJECTED") { result = PurchaseResult.SoldOut; break }
                if (st is Res.Ok && st.value.state == "ADMITTED") result = repo.purchase(CAMPAIGN_ID)
                polls++
            }
            // Hasil belum pasti -> ulangi dengan Idempotency-Key yang sama (aman, tidak membuat order ganda).
            while (result is PurchaseResult.Unknown && attempts < 4) {
                _ui.update { it.copy(notice = Notice("Memeriksa pesananmu…", Tone.Info)) }
                delay(1000L * attempts)
                result = repo.purchase(CAMPAIGN_ID)
                attempts++
            }
            val notice: Notice? = when (val r = result) {
                is PurchaseResult.Reserved -> { _ui.update { it.copy(order = r.order) }; null }
                PurchaseResult.SoldOut -> Notice("Stok promo sudah habis. Kamu tidak dikenai biaya.", Tone.Warn)
                is PurchaseResult.AlreadyPurchased -> Notice("Kamu sudah punya pesanan untuk promo ini.", Tone.Info)
                PurchaseResult.CampaignNotActive -> Notice("Promo ini sedang dijeda atau sudah berakhir.", Tone.Warn)
                is PurchaseResult.RateLimited -> Notice("Terlalu cepat. Coba lagi dalam ${r.retryAfterSec} detik.", Tone.Warn)
                is PurchaseResult.Queued -> Notice("Antrean masih panjang. Tekan Beli lagi nanti.", Tone.Warn)
                is PurchaseResult.Unknown -> Notice("Hasil belum pasti. Tekan Beli lagi — pesananmu tidak akan terduplikasi.", Tone.Warn)
                is PurchaseResult.Failure -> Notice(r.message, Tone.Error)
            }
            _ui.update { it.copy(submitting = false, notice = notice) }
            refresh()
        }
    }

    fun simulatePay() {
        val o = _ui.value.order ?: return
        viewModelScope.launch {
            val r = repo.simulatePay(o.orderId)
            _ui.update { it.copy(notice = (r as? Res.Err)?.let { e -> Notice(e.message, Tone.Error) }) }
            refresh()
        }
    }

    fun cancel() {
        val o = _ui.value.order ?: return
        viewModelScope.launch {
            val r = repo.cancelOrder(o.orderId)
            _ui.update { it.copy(notice = (r as? Res.Err)?.let { e -> Notice(e.message, Tone.Error) } ?: Notice("Pesanan dibatalkan.", Tone.Info)) }
            refresh()
        }
    }

    override fun onCleared() { poll?.cancel() }
}
