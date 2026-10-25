package com.iptv.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette dynamique issue des réglages (fond, couleur d'accentuation, thème clair).
 */
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceCard: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val focusBorder: Color,
    val isLight: Boolean
)

val DefaultAppColors = AppColors(
    background = Color(0xFF080B10),
    surface = Color(0xFF111722),
    surfaceCard = Color(0xFF1A2333),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    accent = Color(0xFF06B6D4),
    focusBorder = Color(0xFF38BDF8),
    isLight = false
)

val LocalAppColors = staticCompositionLocalOf { DefaultAppColors }

// Couleur sombre fixe : texte posé sur une couleur d'accentuation (lisible quel que soit le thème)
val BackgroundDark = Color(0xFF080B10)
val AccentBlue = Color(0xFF3B82F6)
val LiveRed = Color(0xFFEF4444)

val SurfaceDark: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.surface
val SurfaceCard: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceCard
val FocusBorderColor: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.focusBorder
val AccentCyan: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent
val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textPrimary
val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textSecondary
