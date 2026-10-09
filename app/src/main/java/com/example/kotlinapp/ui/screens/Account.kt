package com.example.kotlinapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kotlinapp.data.FlashOrder
import com.example.kotlinapp.data.Receipt
import com.example.kotlinapp.ui.ShopViewModel
import com.example.kotlinapp.ui.components.*
import com.example.kotlinapp.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

/** Label + warna status order flash sale (teks + titik, bukan chip berlatar penuh). */
@Composable
fun statusStyle(status: String): Pair<String, Color> {
    val c = Toko.colors
    return when (status) {
        "PENDING_PAYMENT" -> "Menunggu pembayaran" to c.kunyit
        "PAID", "FULFILLED" -> "Lunas" to c.daun
        "EXPIRED" -> "Kedaluwarsa" to c.inkMuted
        "CANCELLED" -> "Dibatalkan" to c.inkMuted
        "REFUND_REQUIRED", "REFUNDED" -> "Dana dikembalikan" to c.tintaBiru
        "VOIDED" -> "Dibatalkan platform" to c.kunyit
        else -> status to c.inkMuted
    }
}

@Composable
fun AccountScreen(vm: ShopViewModel, onFlashOrder: (FlashOrder) -> Unit, onReceipt: (Receipt) -> Unit) {
    val acc by vm.account.collectAsStateWithLifecycle()
    val c = Toko.colors
    LaunchedEffect(Unit) { vm.refreshAccount() }
    var url by remember { mutableStateOf(vm.settings.baseUrl) }
    var user by remember { mutableStateOf(vm.settings.userId.toString()) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader("Akun") }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Overline("Saldo simulasi")
                if (acc.balance != null) PriceText(acc.balance!!, style = TokoType.display)
                else Text(acc.error ?: "Memuat…", style = TokoType.body, color = c.inkMuted)
                TokoButton("Isi ulang +Rp 100.000", vm::topup, kind = BtnKind.Secondary)
                acc.message?.let { Text(it, style = TokoType.caption, color = c.inkMuted) }
            }
            Hairline()
        }
        item { Overline("Riwayat promo live", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)) }
        if (acc.flashOrders.isEmpty()) item { Text("Belum ada pesanan promo.", Modifier.padding(horizontal = 16.dp), style = TokoType.body, color = c.inkMuted) }
        items(acc.flashOrders, key = { "f${it.orderId}" }) { o ->
            val (label, col) = statusStyle(o.status)
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onFlashOrder(o) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row { Text(o.productName, Modifier.weight(1f), style = TokoType.bodyStrong, color = c.ink); PriceText(o.unitPrice, style = TokoType.mono) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.orderNo, Modifier.weight(1f), style = TokoType.mono, color = c.inkMuted)
                    StatusLine(label, col)
                }
            }
            Hairline(Modifier.padding(horizontal = 16.dp))
        }
        item { Overline("Riwayat belanja", Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)) }
        if (acc.shopOrders.isEmpty()) item { Text("Belum ada pesanan.", Modifier.padding(horizontal = 16.dp), style = TokoType.body, color = c.inkMuted) }
        items(acc.shopOrders, key = { "s${it.orderNo}" }) { r ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onReceipt(r) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row { Text(r.orderNo, Modifier.weight(1f), style = TokoType.mono, color = c.ink); PriceText(r.total, style = TokoType.mono) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.items.sumOf { it.qty }} barang · ${dateFmt.format(r.createdAt)}", Modifier.weight(1f), style = TokoType.caption, color = c.inkMuted)
                    StatusLine("Lunas", c.daun)
                }
            }
            Hairline(Modifier.padding(horizontal = 16.dp))
        }
        item {
            Column(Modifier.padding(16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Overline("Pengaturan simulasi")
                TokoField("Alamat server", url, { url = it })
                TokoField("ID pengguna demo", user, { user = it.filter(Char::isDigit) }, number = true)
                Text(
                    "Emulator: http://10.0.2.2:8080. HP fisik: jalankan adb reverse tcp:8080 tcp:8080, lalu pakai http://localhost:8080. " +
                        "Ganti ID pengguna untuk mencoba pembeli lain pada promo yang sama.",
                    style = TokoType.caption, color = c.inkMuted,
                )
                TokoButton("Simpan pengaturan", { vm.updateServer(url, user.toLongOrNull()?.takeIf { it > 0 } ?: 1L) }, kind = BtnKind.Secondary)
            }
        }
    }
}
