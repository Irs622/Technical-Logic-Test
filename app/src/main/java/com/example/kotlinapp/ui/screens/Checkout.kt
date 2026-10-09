package com.example.kotlinapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kotlinapp.ui.ShopViewModel
import com.example.kotlinapp.ui.components.*
import com.example.kotlinapp.ui.theme.*

@Composable
fun CartScreen(vm: ShopViewModel, onProduct: (Long) -> Unit, onCheckout: () -> Unit, onShop: () -> Unit) {
    val cart by vm.cart.collectAsStateWithLifecycle()
    val c = Toko.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Keranjang")
        SimBanner()
        if (cart.isEmpty()) {
            EmptyState(Icons.Outlined.ShoppingCart, "Keranjangmu masih kosong", "Produk yang kamu tambahkan akan muncul di sini.", "Mulai belanja", onShop)
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(cart.keys.toList(), key = { it }) { id ->
                    val p = vm.productOf(id) ?: return@items
                    val q = cart[id] ?: 0
                    TokoCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProductImage(p.name, p.category, Modifier.size(64.dp).clip(ShapeSm).clickable { onProduct(id) })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(p.name, style = TokoType.bodyStrong, color = c.ink)
                                Text(p.unitLabel, style = TokoType.caption, color = c.inkMuted)
                                PriceText(p.price)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Stepper(q, { vm.setQty(id, q - 1) }, { vm.setQty(id, q + 1) }, maxQty = minOf(99, p.stock))
                                    Spacer(Modifier.weight(1f))
                                    TextAction("Hapus", { vm.remove(id) })
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Overline("Ringkasan")
                        SummaryRow("Subtotal (${vm.cartCount(cart)} barang)", vm.subtotal(cart))
                        Text("Ongkir dihitung di langkah berikutnya.", style = TokoType.caption, color = c.inkMuted)
                    }
                }
            }
            BottomActionBar {
                SummaryRow("Total", vm.subtotal(cart), bold = true)
                Spacer(Modifier.height(12.dp))
                TokoButton("Checkout", onCheckout, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun SummaryRow(label: String, amount: Long, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = if (bold) TokoType.bodyStrong else TokoType.body, color = Toko.colors.ink)
        PriceText(amount, style = if (bold) TokoType.price else TokoType.mono)
    }
}

@Composable
fun CheckoutScreen(vm: ShopViewModel, onBack: () -> Unit, onDone: () -> Unit, onTopup: () -> Unit) {
    val cart by vm.cart.collectAsStateWithLifecycle()
    val acc by vm.account.collectAsStateWithLifecycle()
    val co by vm.checkout.collectAsStateWithLifecycle()
    val c = Toko.colors
    var shipping by remember { mutableStateOf("REG") }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("Jl. Contoh No. 12, Coblong, Bandung 40132") }
    LaunchedEffect(Unit) { vm.refreshAccount(); vm.clearCheckoutError() }

    val fee = if (shipping == "KILAT") 25_000L else 12_000L
    val sub = vm.subtotal(cart)
    val total = sub + fee
    val balance = acc.balance
    val short = if (balance != null && total > balance) total - balance else 0L

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Checkout", onBack)
        SimBanner()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Overline("Alamat")
                if (editing) TokoField("Alamat pengiriman", address, { address = it })
                else Text(address, style = TokoType.body, color = c.ink)
                TextAction(if (editing) "Simpan alamat" else "Ubah alamat", { editing = !editing })
            }
            Hairline()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Overline("Pengiriman")
                ShipOption("Reguler", "Tiba 2–3 hari", 12_000, shipping == "REG") { shipping = "REG" }
                ShipOption("Kilat", "Tiba besok", 25_000, shipping == "KILAT") { shipping = "KILAT" }
            }
            Hairline()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Overline("Pembayaran")
                Text("Saldo Simulasi", style = TokoType.bodyStrong, color = c.ink)
                Row {
                    Text("Saldo kamu", Modifier.weight(1f), style = TokoType.body, color = c.inkMuted)
                    if (balance != null) PriceText(balance, style = TokoType.mono) else Text("…", style = TokoType.mono, color = c.inkMuted)
                }
                if (balance != null && short == 0L) Row {
                    Text("Sisa setelah bayar", Modifier.weight(1f), style = TokoType.body, color = c.inkMuted)
                    PriceText(balance - total, style = TokoType.mono)
                }
            }
            Hairline()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Overline("Ringkasan")
                cart.forEach { (id, q) -> vm.productOf(id)?.let { p ->
                    Row { Text("${p.name} × $q", Modifier.weight(1f), style = TokoType.body, color = c.ink); PriceText(p.price * q, style = TokoType.mono) }
                } }
                SummaryRow("Subtotal", sub)
                SummaryRow("Ongkir", fee)
            }
        }
        BottomActionBar {
            SummaryRow("Total", total, bold = true)
            if (short > 0) {
                Text("Saldo kurang ${formatRupiah(short)}. Isi ulang di Akun.", style = TokoType.caption, color = c.kunyit, modifier = Modifier.padding(top = 4.dp))
                TextAction("Isi ulang saldo", onTopup)
            }
            co.error?.let { Text(it, style = TokoType.caption, color = c.cabai, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))
            TokoButton(
                if (co.submitting) "Memproses…" else "Bayar sekarang", { vm.pay(shipping, onDone) }, Modifier.fillMaxWidth(),
                enabled = !co.submitting && balance != null && short == 0L && cart.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ShipOption(name: String, eta: String, fee: Long, selected: Boolean, onClick: () -> Unit) {
    val c = Toko.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(ShapeMd).border(1.dp, if (selected) c.ink else c.line, ShapeMd)
            .background(c.surface).clickable(role = Role.RadioButton, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = TokoType.bodyStrong, color = c.ink)
            Text(eta, style = TokoType.caption, color = c.inkMuted)
        }
        PriceText(fee, style = TokoType.mono)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(16.dp).border(1.dp, c.ink, androidx.compose.foundation.shape.CircleShape).padding(3.dp)) {
            if (selected) Box(Modifier.fillMaxSize().background(c.ink, androidx.compose.foundation.shape.CircleShape))
        }
    }
}

@Composable
fun ReceiptScreen(vm: ShopViewModel, onOrders: () -> Unit, onShop: () -> Unit) {
    val r by vm.receipt.collectAsStateWithLifecycle()
    val c = Toko.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Pembayaran berhasil", Modifier.fillMaxWidth().padding(top = 16.dp), style = TokoType.headline, color = c.ink, textAlign = TextAlign.Center)
        Text("Simpan struk ini sebagai bukti pesanan.", Modifier.fillMaxWidth(), style = TokoType.body, color = c.inkMuted, textAlign = TextAlign.Center)
        r?.let { ReceiptView(it) }
        TokoButton("Lihat pesanan", onOrders, Modifier.fillMaxWidth(), kind = BtnKind.Secondary)
        TokoButton("Belanja lagi", onShop, Modifier.fillMaxWidth(), kind = BtnKind.Secondary)
    }
}
