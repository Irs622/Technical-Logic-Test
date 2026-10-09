package com.example.kotlinapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.kotlinapp.R

@OptIn(ExperimentalTextApi::class)
private fun sans(weight: Int) = Font(
    R.font.instrument_sans, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val InstrumentSans = FontFamily(sans(400), sans(500), sans(600))
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

private fun s(size: Int, line: Int, w: FontWeight, f: FontFamily = InstrumentSans, ls: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = f, fontSize = size.sp, lineHeight = line.sp, fontWeight = w, letterSpacing = ls)

/** Token tipografi DESIGN.md §4. */
object TokoType {
    val display = s(32, 36, FontWeight.SemiBold)
    val headline = s(24, 30, FontWeight.SemiBold)
    val title = s(18, 24, FontWeight.SemiBold)
    val body = s(15, 22, FontWeight.Normal)
    val bodyStrong = s(15, 22, FontWeight.Medium)
    val label = s(13, 18, FontWeight.Medium)
    val caption = s(12, 16, FontWeight.Normal)
    val overline = s(11, 14, FontWeight.SemiBold, ls = 0.08.em)
    val price = s(16, 20, FontWeight.Medium, JetBrainsMono)
    val priceLarge = s(24, 28, FontWeight.Medium, JetBrainsMono)
    val mono = s(13, 18, FontWeight.Normal, JetBrainsMono)
}

val TokoTypography = Typography(
    displayLarge = TokoType.display, headlineMedium = TokoType.headline, titleMedium = TokoType.title,
    bodyLarge = TokoType.body, bodyMedium = TokoType.body, labelLarge = TokoType.label,
    labelMedium = TokoType.label, bodySmall = TokoType.caption, labelSmall = TokoType.overline,
)
