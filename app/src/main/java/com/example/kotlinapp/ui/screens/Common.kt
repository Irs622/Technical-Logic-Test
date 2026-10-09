package com.example.kotlinapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.kotlinapp.ui.components.CartBadge
import com.example.kotlinapp.ui.components.Hairline
import com.example.kotlinapp.ui.theme.Toko
import com.example.kotlinapp.ui.theme.TokoType
import kotlinx.coroutines.delay

/** Waktu server terkoreksi, berdetak tiap detik. */
@Composable
fun rememberServerNow(offsetMs: Long): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis() + offsetMs) }
    LaunchedEffect(offsetMs) {
        while (true) {
            now.longValue = System.currentTimeMillis() + offsetMs
            delay(1000)
        }
    }
    return now
}

fun fmtCountdown(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

@Composable
fun BottomActionBar(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Toko.colors.paper)) {
        Hairline()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

enum class Tab(val route: String, val label: String, val outlined: ImageVector, val filled: ImageVector) {
    Home("home", "Beranda", Icons.Outlined.Home, Icons.Filled.Home),
    Categories("categories", "Kategori", Icons.AutoMirrored.Outlined.List, Icons.AutoMirrored.Filled.List),
    Cart("cart", "Keranjang", Icons.Outlined.ShoppingCart, Icons.Filled.ShoppingCart),
    Account("account", "Akun", Icons.Outlined.Person, Icons.Filled.Person),
}

/** 4 tab, tanpa pill indikator Material (DESIGN.md §7.7). */
@Composable
fun TokoBottomNav(current: String?, cartCount: Int, onSelect: (Tab) -> Unit) {
    val c = Toko.colors
    Column(Modifier.fillMaxWidth().background(c.paper)) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            Tab.entries.forEach { tab ->
                val selected = current == tab.route
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(if (selected) tab.filled else tab.outlined, tab.label, Modifier.size(24.dp), tint = if (selected) c.ink else c.inkMuted)
                        if (tab == Tab.Cart) Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp)) { CartBadge(cartCount) }
                    }
                    Text(tab.label, style = TokoType.caption, color = if (selected) c.ink else c.inkMuted)
                }
            }
        }
    }
}
