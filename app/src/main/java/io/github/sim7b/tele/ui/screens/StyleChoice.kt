package io.github.sim7b.tele.ui.screens

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.model.HomeStyle
import io.github.sim7b.tele.ui.components.LocalScreen
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/** A device with little memory, where the light (classic) mode is advised. */
internal fun isSmallDevice(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return manager.isLowRamDevice || manager.memoryClass <= SMALL_MEMORY_CLASS_MB
}

private const val SMALL_MEMORY_CLASS_MB = 192

/**
 * Choice of the style of the app at the first launch: immersive (the program or title in focus over the whole
 * screen), or classic, the light one for small devices. Advised by the memory of the device; changed later in the
 * settings.
 */
@Composable
fun StyleChoice(onChoose: (HomeStyle) -> Unit) {
    val context = LocalContext.current
    val advised = remember { if (isSmallDevice(context)) HomeStyle.CLASSIC else HomeStyle.IMMERSIVE }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusWithRetry() }

    Dialog(onDismissRequest = { onChoose(advised) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(LocalAppColors.current.background)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 900.dp).padding(24.dp)) {
                Text(
                    text = stringResource(R.string.style_choice_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.style_choice_subtitle),
                    fontSize = 15.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
                )
                val cards: @Composable (Modifier) -> Unit = { cardModifier ->
                    listOf(HomeStyle.IMMERSIVE, HomeStyle.CLASSIC).forEach { style ->
                        StyleCard(
                            style = style,
                            advised = style == advised,
                            onClick = { onChoose(style) },
                            modifier = cardModifier.then(if (style == advised) Modifier.focusRequester(focus) else Modifier)
                        )
                    }
                }
                if (LocalScreen.current.compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { cards(Modifier) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { cards(Modifier.width(380.dp)) }
                }
                Text(
                    text = stringResource(R.string.style_choice_later),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StyleCard(style: HomeStyle, advised: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = LocalAppColors.current.accent
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LocalAppColors.current.surface,
            focusedContainerColor = LocalAppColors.current.surface
        ),
        modifier = modifier.border(2.dp, if (focused) accent else Color.Transparent, shape)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(if (style == HomeStyle.IMMERSIVE) R.string.style_choice_immersive else R.string.style_choice_classic),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            if (advised) {
                Text(
                    text = stringResource(R.string.style_choice_advised),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = stringResource(
                    if (style == HomeStyle.IMMERSIVE) R.string.style_choice_immersive_description else R.string.style_choice_classic_description
                ),
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}
