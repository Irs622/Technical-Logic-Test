package com.example.kotlinapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kotlinapp.data.Product
import com.example.kotlinapp.data.Res
import com.example.kotlinapp.ui.*
import com.example.kotlinapp.ui.components.*
import com.example.kotlinapp.ui.theme.*

@Composable
fun ProductCard(p: Product, qty: Int, onOpen: () -> Unit, onAdd: () -> Unit, onMinus: () -> Unit, modifier: Modifier = Modifier) {
    val c = Toko.colors
    TokoCard(modifier, onClick = onOpen) {
        ProductImage(p.name, p.category, Modifier.fillMaxWidth().aspectRatio(4f / 5f))
        Hairline()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = TokoType.bodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            Text("${p.unitLabel} · ${p.origin}", style = TokoType.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PriceText(p.price)
            if (p.comparePrice != null && p.discountPercent != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PriceText(p.comparePrice, style = TokoType.caption, color = c.inkFaint, strike = true)
                    Text("-${p.discountPercent}%", style = TokoType.label, color = c.cabai)
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.stock in 1..5) Text("Sisa ${p.stock}", style = TokoType.caption, color = c.kunyit)
                else if (p.stock == 0) Text("Habis", style = TokoType.caption, color = c.inkMuted)
                Spacer(Modifier.weight(1f))
                if (p.stock > 0) {
                    if (qty == 0) QuickAdd(onAdd)
                    else Stepper(qty, onMinus, onAdd, maxQty = minOf(99, p.stock))
                }
            }
        }
    }
}

@Composable
private fun QuickAdd(onClick: () -> Unit) {
    val c = Toko.colors
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).clip(ShapeSm).border(1.dp, c.ink, ShapeSm), contentAlignment = Alignment.Center) {
            Text("+", style = TokoType.price, color = c.ink)
        }
    }
}

@Composable
private fun SkeletonCard(modifier: Modifier = Modifier) {
    val c = Toko.colors
    Column(modifier.clip(ShapeMd).border(1.dp, c.line, ShapeMd)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).background(c.surfaceSunken))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.8f).height(14.dp).background(c.surfaceSunken))
            Box(Modifier.fillMaxWidth(0.5f).height(14.dp).background(c.surfaceSunken))
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
fun HomeScreen(vm: ShopViewModel, flash: FlashViewModel, onProduct: (Long) -> Unit, onFlash: () -> Unit) {
    val state by vm.catalog.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val f by flash.ui.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { flash.start(); onDispose { flash.stop() } }
    val c = Toko.colors

    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Toko Simulasi", style = TokoType.headline, color = c.ink, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    state.query, vm::setQuery, Modifier.fillMaxWidth(), singleLine = true, shape = ShapeSm,
                    placeholder = { Text("Cari produk", style = TokoType.body, color = c.inkFaint) },
                    leadingIcon = { Icon(Icons.Outlined.Search, "Cari", tint = c.inkMuted) },
                    textStyle = TokoType.body.copy(color = c.ink),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.ink, unfocusedBorderColor = c.line,
                        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, cursorColor = c.ink,
                    ),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("Semua", state.selected == null) { vm.selectCategory(null) } }
                    items(state.categories) { cat -> Chip(cat.name, state.selected == cat.name) { vm.selectCategory(cat.name) } }
                }
                f.campaign?.let { camp -> FlashPromoBlock(camp.productName, camp.promoPrice, camp.normalPrice, camp.remainingApprox, camp.status, onFlash) }
                Overline(if (state.selected != null) state.selected!! else "Baru masuk")
            }
        }
        when {
            state.loading && state.products.isEmpty() -> items(4) { SkeletonCard() }
            state.error != null && state.products.isEmpty() -> item(span = { GridItemSpan(2) }) {
                ErrorState("Katalog gagal dimuat", state.error!!, vm::loadCatalog)
            }
            state.products.isEmpty() -> item(span = { GridItemSpan(2) }) {
                EmptyState(Icons.Outlined.Search, "Produk tidak ditemukan", "Coba kata kunci lain atau pilih kategori berbeda.")
            }
            else -> items(state.products, key = { it.id }) { p ->
                ProductCard(p, cart[p.id] ?: 0, { onProduct(p.id) }, { vm.add(p) }, { vm.setQty(p.id, (cart[p.id] ?: 1) - 1) })
            }
        }
    }
}

/** Blok promo statis (bukan carousel). */
@Composable
private fun FlashPromoBlock(name: String, promo: Long, normal: Long, remaining: Int, status: String, onClick: () -> Unit) {
    val c = Toko.colors
    Column(
        Modifier.fillMaxWidth().clip(ShapeMd).border(1.dp, c.ink, ShapeMd).clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Overline(if (status == "ACTIVE") "Promo live · terbatas" else "Promo live · dijeda")
        Text(name, style = TokoType.title, color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            PriceText(promo, style = TokoType.priceLarge, color = c.cabai)
            PriceText(normal, style = TokoType.caption, color = c.inkFaint, strike = true)
        }
        Text("Sisa ± $remaining · maks. 1 unit per pelanggan", style = TokoType.caption, color = if (remaining in 1..5) c.kunyit else c.inkMuted)
        Spacer(Modifier.height(8.dp))
        TokoButton("Lihat promo", onClick, kind = BtnKind.Secondary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun CategoriesScreen(vm: ShopViewModel, onPick: () -> Unit) {
    val state by vm.catalog.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Kategori")
        when {
            state.categories.isEmpty() && state.loading -> LoadingText()
            state.categories.isEmpty() -> ErrorState("Kategori gagal dimuat", state.error ?: "Coba lagi sebentar.", vm::loadCatalog)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.categories) { cat ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { vm.selectCategory(cat.name); onPick() }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(cat.name, Modifier.weight(1f), style = TokoType.body, color = Toko.colors.ink)
                        Text(cat.count.toString(), style = TokoType.mono, color = Toko.colors.inkMuted)
                    }
                    Hairline(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
fun ProductDetailScreen(id: Long, vm: ShopViewModel, onBack: () -> Unit, onCart: () -> Unit) {
    LaunchedEffect(id) { vm.openProduct(id) }
    val res by vm.product.collectAsStateWithLifecycle()
    val c = Toko.colors
    var qty by remember(id) { mutableIntStateOf(1) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Detail produk", onBack)
        when (val r = res) {
            null -> LoadingText()
            is Res.Err -> ErrorState("Produk tidak bisa dimuat", r.message, { vm.openProduct(id) })
            is Res.Ok -> {
                val p = r.value
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ProductImage(p.name, p.category, Modifier.fillMaxWidth().aspectRatio(4f / 5f), large = true)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.name, style = TokoType.title, color = c.ink)
                        PriceText(p.price, style = TokoType.priceLarge)
                        if (p.comparePrice != null) PriceText(p.comparePrice, style = TokoType.caption, color = c.inkFaint, strike = true)
                        Spacer(Modifier.height(8.dp))
                        Hairline()
                        InfoRow("Berat / isi", p.unitLabel)
                        InfoRow("Asal", p.origin)
                        InfoRow("Stok", if (p.stock == 0) "Habis" else if (p.stock <= 5) "Sisa ${p.stock}" else "Tersedia", if (p.stock in 1..5) c.kunyit else if (p.stock == 0) c.inkMuted else c.daun)
                        Hairline()
                        Overline("Deskripsi")
                        Text(p.description, style = TokoType.body, color = c.ink)
                    }
                }
                BottomActionBar {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stepper(qty, { if (qty > 1) qty-- }, { qty++ }, maxQty = maxOf(1, minOf(99, p.stock)))
                        TokoButton(if (p.stock == 0) "Stok habis" else "Tambah ke keranjang", { vm.add(p, qty); onCart() }, Modifier.weight(1f), enabled = p.stock > 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = Toko.colors.ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = TokoType.body, color = Toko.colors.inkMuted)
        Text(value, style = TokoType.bodyStrong, color = valueColor)
    }
}
