package io.github.sim7b.tele.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import io.github.sim7b.tele.data.storage.AppAccentColor
import io.github.sim7b.tele.data.storage.AppThemeBackground
import io.github.sim7b.tele.data.storage.UiDensity
import io.github.sim7b.tele.ui.components.Device
import io.github.sim7b.tele.ui.components.LocalScreen
import io.github.sim7b.tele.ui.components.ScreenSize

/**
 * App theme: palette derived from the settings (background, accent, light mode) exposed through
 * [LocalAppColors], UI density applied by rescaling [LocalDensity], and the window size of a touch
 * device in [LocalScreen].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TeleTheme(
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
            // Neutral focus ring (white / ink): the accent stays reserved for indicators (progress, selection)
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

    // UI density applies to the whole app by scaling dp/sp
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, uiDensity) {
        Density(baseDensity.density * uiDensity.scaleFactor, baseDensity.fontScale)
    }
    // Phone or tablet: size of the window in those dp, for the layouts of the narrow screens
    val screen = if (!Device.isTouch) ScreenSize() else {
        val windowSize = LocalWindowInfo.current.containerSize
        with(scaledDensity) { ScreenSize(touch = true, width = windowSize.width.toDp(), height = windowSize.height.toDp()) }
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalDensity provides scaledDensity,
        LocalScreen provides screen
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
