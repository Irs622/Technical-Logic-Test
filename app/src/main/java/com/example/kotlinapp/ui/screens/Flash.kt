package com.example.kotlinapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kotlinapp.data.Campaign
import com.example.kotlinapp.data.FlashOrder
import com.example.kotlinapp.ui.*
import com.example.kotlinapp.ui.components.*
import com.example.kotlinapp.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault())

@Composable
fun FlashScreen(vm: FlashViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { vm.start(); onDispose { vm.stop() } }
    val c = Toko.colors
    val now by rememberServerNow(ui.offsetMs)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Promo live", onBack)
        val camp = ui.campaign
        when {
            ui.loading && camp == null -> LoadingText()
            camp == null -> ErrorState("Promo gagal dimuat", ui.error ?: "Coba lagi sebentar.", vm::retry)
            else -> {
                val order = ui.order
                val holding = order != null && order.status in setOf("PENDING_PAYMENT", "PAID", "FULFILLED")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CampaignInfo(camp, now)
                    ui.notice?.let { n ->
                        val col = when (n.tone) { Tone.Info -> c.ink; Tone.Warn -> c.kunyit; Tone.Error -> c.cabai }
                        Text(n.text, style = TokoType.body, color = col)
                    }
                    if (order != null) OrderPanel(order, now, vm)
                    TokoCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Overline("Aturan promo")
                            Text("• Maksimal 1 unit per pelanggan.", style = TokoType.body, color = c.ink)
                            Text("• Bayar dalam ${camp.paymentWindowSec / 60} menit setelah pesanan dibuat; setelah itu stok dilepas.", style = TokoType.body, color = c.ink)
                            Text("• Stok ditentukan server saat kamu menekan Beli, bukan angka di layar.", style = TokoType.body, color = c.ink)
                        }
                    }
                }
                if (!holding) {
                    val live = camp.status == "ACTIVE" && now >= camp.startsAt.toEpochMilli() && now < camp.endsAt.toEpochMilli()
                    BottomActionBar {
                        TokoButton(
                            when {
                                ui.submitting -> "Memproses…"
                                camp.status == "PAUSED" -> "Promo dijeda"
                                now < camp.startsAt.toEpochMilli() -> "Belum dimulai"
                                !live -> "Promo berakhir"
                                else -> "Beli sekarang"
                            },
                            vm::buy, Modifier.fillMaxWidth(), enabled = live && !ui.submitting,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignInfo(camp: Campaign, now: Long) {
    val c = Toko.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProductImage(camp.productName, "Promo live", Modifier.fillMaxWidth().height(200.dp), large = true)
        Overline("Live · ${camp.sellerName}")
        Text("${camp.productName} · ${camp.unitLabel}", style = TokoType.title, color = c.ink)
        PriceText(camp.promoPrice, style = TokoType.priceLarge, color = c.cabai)
        PriceText(camp.normalPrice, style = TokoType.caption, color = c.inkFaint, strike = true)
        val start = camp.startsAt.toEpochMilli(); val end = camp.endsAt.toEpochMilli()
        Text(
            when {
                camp.status == "PAUSED" -> "Promo dijeda sementara."
                now < start -> "Mulai dalam ${fmtCountdown(start - now)}"
                now < end -> "Berakhir dalam ${fmtCountdown(end - now)}"
                else -> "Promo sudah berakhir."
            },
            style = TokoType.mono, color = c.ink,
        )
        Text(
            "Sisa ± ${camp.remainingApprox} (perkiraan, bisa berubah)",
            style = TokoType.caption, color = if (camp.remainingApprox in 1..5) c.kunyit else c.inkMuted,
        )
    }
}

@Composable
private fun OrderPanel(o: FlashOrder, now: Long, vm: FlashViewModel) {
    val c = Toko.colors
    val clipboard = LocalClipboardManager.current
    TokoCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(o.orderNo, Modifier.weight(1f), style = TokoType.mono, color = c.inkMuted)
                val (label, col) = statusStyle(o.status)
                StatusLine(label, col)
            }
            when (o.status) {
                "PENDING_PAYMENT" -> {
                    o.va?.let { va ->
                        Overline("Nomor VA ${va.bank}")
                        SelectableMono(va.number, style = TokoType.priceLarge)
                        PriceText(va.amount, style = TokoType.price)
                        Text(
                            "Bayar sebelum ${clock.format(o.reservedUntil)} WIB. Setelah itu pesanan dibatalkan dan stok dilepas.",
                            style = TokoType.body, color = c.ink,
                        )
                        Text("Sisa waktu ${fmtCountdown(o.reservedUntil.toEpochMilli() - now)}", style = TokoType.mono, color = c.kunyit)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TextAction("Salin nomor VA", { clipboard.setText(AnnotatedString(va.number)) })
                            TextAction("Batalkan pesanan", vm::cancel, color = c.inkMuted)
                        }
                        TokoButton("Bayar (simulasi)", vm::simulatePay, Modifier.fillMaxWidth(), kind = BtnKind.Secondary)
                    } ?: Text("Menyiapkan nomor VA…", style = TokoType.body, color = c.inkMuted)
                }
                "PAID", "FULFILLED" -> Text("Pembayaran diterima. Pesananmu diproses penjual.", style = TokoType.body, color = c.ink)
                "EXPIRED" -> Text("Waktu pembayaran habis. Pesanan dibatalkan.", style = TokoType.body, color = c.inkMuted)
                "CANCELLED" -> Text("Pesanan dibatalkan.", style = TokoType.body, color = c.inkMuted)
                "REFUND_REQUIRED", "REFUNDED" -> Text("Pembayaranmu masuk setelah stok dilepas. Dana dikembalikan otomatis.", style = TokoType.body, color = c.ink)
                "VOIDED" -> Text("Pesanan ini dibatalkan platform. Dana dikembalikan penuh.", style = TokoType.body, color = c.ink)
            }
        }
    }
}
