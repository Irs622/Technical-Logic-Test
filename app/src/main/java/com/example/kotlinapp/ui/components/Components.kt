package com.example.kotlinapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kotlinapp.ui.theme.*
import java.util.Locale

/** Selalu `Rp 24.500` (DESIGN.md §7.3). Harga disimpan sebagai Long rupiah. */
fun formatRupiah(v: Long): String {
    val abs = "%,d".format(Locale.US, kotlin.math.abs(v)).replace(',', '.')
    return (if (v < 0) "-Rp " else "Rp ") + abs
}

private fun spokenRupiah(v: Long): String = when {
    v >= 1_000_000 && v % 1_000_000 == 0L -> "${v / 1_000_000} juta rupiah"
    v >= 1_000 && v % 1_000 == 0L -> "${v / 1_000} ribu rupiah"
    else -> "$v rupiah"
}

@Composable
fun PriceText(
    price: Long, modifier: Modifier = Modifier, style: TextStyle = TokoType.price,
    color: Color = Toko.colors.ink, strike: Boolean = false,
) {
    Text(
        formatRupiah(price), modifier.semantics { contentDescription = spokenRupiah(price) }, style = style, color = color,
        textDecoration = if (strike) TextDecoration.LineThrough else null,
    )
}

@Composable
fun TokoCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Toko.colors
    Column(
        modifier
            .clip(ShapeMd).background(c.surface).border(1.dp, c.line, ShapeMd)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        content = content,
    )
}

enum class BtnKind { Primary, Secondary }

@Composable
fun TokoButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary, enabled: Boolean = true,
) {
    val c = Toko.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = if (kind == BtnKind.Primary) c.cabai else Color.Transparent
    val fg = if (kind == BtnKind.Primary) c.onCabai else c.ink
    Box(
        modifier
            .heightIn(min = 48.dp).clip(ShapeMd)
            .background(if (enabled) bg else c.surfaceSunken)
            .then(if (kind == BtnKind.Secondary) Modifier.border(1.dp, if (enabled) c.ink else c.line, ShapeMd) else Modifier)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (pressed && enabled) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.08f)))
        Text(
            text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = if (enabled) fg else c.inkFaint,
            style = TokoType.bodyStrong.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Toko.colors.ink) {
    Text(
        text, modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
            .clickable(role = Role.Button, onClick = onClick),
        style = TokoType.label, color = color, textDecoration = TextDecoration.Underline,
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Toko.colors
    Text(
        text,
        Modifier.heightIn(min = 36.dp).clip(ShapeSm)
            .background(if (selected) c.ink else Color.Transparent)
            .border(1.dp, if (selected) c.ink else c.line, ShapeSm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = TokoType.label, color = if (selected) c.paper else c.ink,
    )
}

@Composable
fun Stepper(qty: Int, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, maxQty: Int = 99) {
    val c = Toko.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepBtn("−", "Kurangi jumlah", onMinus)
        Text(qty.toString(), Modifier.width(32.dp), style = TokoType.price, color = c.ink, textAlign = TextAlign.Center)
        StepBtn("+", "Tambah jumlah", onPlus, enabled = qty < maxQty)
    }
}

@Composable
private fun StepBtn(glyph: String, desc: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Toko.colors
    // Kotak 32dp terlihat, area sentuh 48dp.
    Box(
        Modifier.size(48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(ShapeSm).border(1.dp, if (enabled) c.ink else c.line, ShapeSm), contentAlignment = Alignment.Center) {
            Text(glyph, style = TokoType.price, color = if (enabled) c.ink else c.inkFaint)
        }
    }
}

/** Placeholder tipografis (inisial + kategori), bukan gambar acak — DESIGN.md §6. */
@Composable
fun ProductImage(name: String, category: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val c = Toko.colors
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier.background(c.surfaceSunken).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(initials, style = if (large) TokoType.display else TokoType.headline, color = c.inkMuted)
            Spacer(Modifier.height(4.dp))
            Text(category.uppercase(), style = TokoType.overline, color = c.inkFaint)
        }
    }
}

@Composable
fun SimBanner(modifier: Modifier = Modifier) {
    val c = Toko.colors
    Box(
        modifier.fillMaxWidth().heightIn(min = 28.dp).background(c.kunyit.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text("Mode simulasi — tidak ada pembayaran sungguhan.", style = TokoType.caption, color = c.ink) }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = TokoType.overline, color = Toko.colors.inkMuted)

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Toko.colors.line))

@Composable
fun StatusLine(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = TokoType.label, color = color)
    }
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kembali", tint = Toko.colors.ink)
            }
        } else Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = TokoType.headline, color = Toko.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, actionText: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = Toko.colors
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = c.inkFaint)
        Spacer(Modifier.height(4.dp))
        Text(title, style = TokoType.title, color = c.ink, textAlign = TextAlign.Center)
        Text(body, style = TokoType.body, color = c.inkMuted, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TokoButton(actionText, onAction, kind = BtnKind.Secondary)
        }
    }
}

@Composable
fun ErrorState(title: String, message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = Toko.colors
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = TokoType.title, color = c.ink, textAlign = TextAlign.Center)
        Text(message, style = TokoType.body, color = c.inkMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        TokoButton("Coba lagi", onRetry, kind = BtnKind.Secondary)
    }
}

@Composable
fun LoadingText(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("Memuat…", style = TokoType.body, color = Toko.colors.inkMuted)
    }

@Composable
fun TokoField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = false, error: String? = null) {
    val c = Toko.colors
    Column(modifier) {
        Text(label, style = TokoType.label, color = c.inkMuted)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true, shape = ShapeSm,
            textStyle = TokoType.body.copy(color = c.ink),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.ink, unfocusedBorderColor = c.line,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, cursorColor = c.ink,
            ),
            isError = error != null,
        )
        if (error != null) Text(error, style = TokoType.caption, color = c.cabai, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun CartBadge(count: Int) {
    if (count <= 0) return
    Box(Modifier.size(16.dp).clip(CircleShape).background(Toko.colors.cabai), contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99" else count.toString(), style = TokoType.mono.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Toko.colors.onCabai)
    }
}

@Composable
fun SelectableMono(text: String, modifier: Modifier = Modifier, style: TextStyle = TokoType.mono) =
    SelectionContainer { Text(text, modifier, style = style, color = Toko.colors.ink) }
