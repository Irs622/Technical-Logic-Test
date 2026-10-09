package com.example.kotlinapp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.kotlinapp.data.Receipt
import com.example.kotlinapp.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Tepi bawah bergerigi seperti sobekan struk (gigi 8dp), radius 0 di tempat lain. */
class ZigzagBottomShape(private val tooth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val teeth = (size.width / tooth).toInt().coerceAtLeast(1)
        val w = size.width / teeth
        val body = size.height - tooth / 2
        return Outline.Generic(Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, body)
            for (i in teeth - 1 downTo 0) {
                lineTo(i * w + w / 2, size.height)
                lineTo(i * w, body)
            }
            close()
        })
    }
}

@Composable
private fun Dashed() {
    val c = Toko.colors.inkFaint
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(c, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }
}

@Composable
private fun Row2(left: String, right: String, bold: Boolean = false) {
    val c = Toko.colors
    val st = if (bold) TokoType.mono.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) else TokoType.mono
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, Modifier.weight(1f), style = st, color = c.ink)
        Text(right, style = st, color = c.ink)
    }
}

private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

/** Komponen khas aplikasi (DESIGN.md §7.8): isi seluruhnya mono, tepi bergerigi, cap LUNAS. */
@Composable
fun ReceiptView(r: Receipt, modifier: Modifier = Modifier, animate: Boolean = true) {
    val c = Toko.colors
    val tooth = with(LocalDensity.current) { 8.dp.toPx() }
    var shown by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(if (shown) 0f else -32f, tween(300, easing = FastOutSlowInEasing), label = "slide")
    val stamp by animateFloatAsState(if (shown) 1f else 1.2f, tween(150, delayMillis = if (animate) 300 else 0), label = "stamp")

    Column(
        modifier.fillMaxWidth().graphicsLayer { translationY = slide.dp.toPx(); alpha = if (shown) 1f else 0f }
            .clip(ZigzagBottomShape(tooth)).background(c.surface)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("TOKO SIMULASI", Modifier.fillMaxWidth(), style = TokoType.mono.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("Jl. Contoh No. 12, Bandung", Modifier.fillMaxWidth(), style = TokoType.mono, color = c.inkMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Dashed()
        Row2("No. Pesanan", r.orderNo)
        Row2("Tanggal", fmt.format(r.createdAt))
        Dashed()
        r.items.forEach {
            Text(it.name, style = TokoType.mono, color = c.ink)
            Row2("  ${it.qty} x ${formatRupiah(it.unitPrice).removePrefix("Rp ")}", formatRupiah(it.unitPrice * it.qty).removePrefix("Rp "))
        }
        Dashed()
        Row2("Subtotal", formatRupiah(r.subtotal).removePrefix("Rp "))
        Row2(if (r.shipping == "KILAT") "Ongkir kilat (simulasi)" else "Ongkir reguler (simulasi)", formatRupiah(r.shippingFee).removePrefix("Rp "))
        Row2("TOTAL", formatRupiah(r.total), bold = true)
        Dashed()
        Text("Dibayar dengan Saldo Simulasi", style = TokoType.mono, color = c.ink)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                "LUNAS",
                Modifier.scale(stamp).rotate(-8f).graphicsLayer { alpha = 0.85f }
                    .border(2.dp, c.daun, ShapeSm).padding(horizontal = 16.dp, vertical = 6.dp),
                style = TokoType.title, color = c.daun,
            )
        }
    }
}
