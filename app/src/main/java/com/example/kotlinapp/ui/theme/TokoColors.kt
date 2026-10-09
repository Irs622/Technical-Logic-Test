package com.example.kotlinapp.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Token warna dari DESIGN.md §3. Komponen tidak boleh memakai Color(0x...) langsung. */
@Immutable
data class TokoColors(
    val paper: Color, val surface: Color, val surfaceSunken: Color,
    val ink: Color, val inkMuted: Color, val inkFaint: Color, val line: Color,
    val cabai: Color, val onCabai: Color,
    val daun: Color, val kunyit: Color, val tintaBiru: Color,
)

val LightToko = TokoColors(
    paper = Color(0xFFF4F1EA), surface = Color(0xFFFBFAF6), surfaceSunken = Color(0xFFEAE6DC),
    ink = Color(0xFF1A1A17), inkMuted = Color(0xFF5E5B53), inkFaint = Color(0xFF8F8B80), line = Color(0xFFD6D1C4),
    cabai = Color(0xFFC8361A), onCabai = Color(0xFFFFFFFF),
    daun = Color(0xFF2E5E3A), kunyit = Color(0xFFB7800F), tintaBiru = Color(0xFF24497A),
)

val DarkToko = TokoColors(
    paper = Color(0xFF151513), surface = Color(0xFF1D1D1A), surfaceSunken = Color(0xFF0F0F0E),
    ink = Color(0xFFECE9E1), inkMuted = Color(0xFFA19D93), inkFaint = Color(0xFF6D6A62), line = Color(0xFF34332F),
    cabai = Color(0xFFF0603F), onCabai = Color(0xFF1A0A05),
    daun = Color(0xFF7FBF8E), kunyit = Color(0xFFE6B44A), tintaBiru = Color(0xFF8AAEE0),
)

val LocalTokoColors = staticCompositionLocalOf { LightToko }
