package com.example.kotlinapp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Tanpa dynamic color dan tanpa surface tint (DESIGN.md §2). */
@Composable
fun KotlinAppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val toko = if (darkTheme) DarkToko else LightToko
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = toko.cabai, onPrimary = toko.onCabai,
        background = toko.paper, onBackground = toko.ink,
        surface = toko.surface, onSurface = toko.ink,
        surfaceVariant = toko.surfaceSunken, onSurfaceVariant = toko.inkMuted,
        outline = toko.line, error = toko.cabai, surfaceTint = Color.Transparent,
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let {
                it.statusBarColor = toko.paper.toArgb()
                it.navigationBarColor = toko.paper.toArgb()
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    CompositionLocalProvider(LocalTokoColors provides toko) {
        MaterialTheme(colorScheme = scheme, typography = TokoTypography, shapes = TokoShapes, content = content)
    }
}

object Toko {
    val colors: TokoColors
        @Composable get() = LocalTokoColors.current
}
