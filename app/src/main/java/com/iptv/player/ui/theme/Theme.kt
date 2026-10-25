package com.iptv.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.iptv.player.data.storage.AppAccentColor
import com.iptv.player.data.storage.AppThemeBackground
import com.iptv.player.data.storage.UiDensity

/**
 * Thème de l'application : palette issue des réglages (fond, accent, mode clair) exposée via
 * [LocalAppColors], et densité d'interface appliquée en redimensionnant [LocalDensity].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IPTVPlayerTheme(
    themeBackground: AppThemeBackground = AppThemeBackground.ORIGINAL,
    accentColor: AppAccentColor = AppAccentColor.CYAN,
    uiDensity: UiDensity = UiDensity.NORMAL,
    content: @Composable () -> Unit
) {
    val appColors = remember(themeBackground, accentColor) {
        val accent = Color(accentColor.colorHex)
        AppColors(
            background = Color(themeBackground.backgroundColorHex),
            surface = Color(themeBackground.surfaceColorHex),
            surfaceCard = Color(themeBackground.surfaceCardColorHex),
            textPrimary = if (themeBackground.isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
            textSecondary = if (themeBackground.isLight) Color(0xFF475569) else Color(0xFF94A3B8),
            accent = accent,
            // Anneau de focus neutre (blanc / encre) : l'accent reste réservé aux indicateurs (progression, sélection)
            focusBorder = if (themeBackground.isLight) Color(0xFF0F172A) else Color.White.copy(alpha = 0.92f),
            isLight = themeBackground.isLight
        )
    }

    val colorScheme = if (appColors.isLight) {
        lightColorScheme(
            primary = appColors.accent,
            onPrimary = BackgroundDark,
            surface = appColors.surface,
            onSurface = appColors.textPrimary,
            background = appColors.background,
            onBackground = appColors.textPrimary
        )
    } else {
        darkColorScheme(
            primary = appColors.accent,
            onPrimary = BackgroundDark,
            surface = appColors.surface,
            onSurface = appColors.textPrimary,
            background = appColors.background,
            onBackground = appColors.textPrimary
        )
    }

    // La densité d'interface s'applique à toute l'application en mettant à l'échelle les dp/sp
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, uiDensity) {
        Density(baseDensity.density * uiDensity.scaleFactor, baseDensity.fontScale)
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
