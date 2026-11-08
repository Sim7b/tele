package io.github.sim7b.tele.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Kind of device, read once at startup: a TV driven by the remote, or a phone / tablet driven by touch.
 * On a TV nothing in this file changes the UI. On a touch device the TV surfaces react to taps, the
 * screens get buttons for what the remote keys do, and narrow screens get layouts that fit them.
 */
object Device {
    /** Phone or tablet. */
    var isTouch: Boolean = false
        private set

    fun init(context: Context) {
        isTouch = !isTelevision(context)
    }

    private fun isTelevision(context: Context): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

// Below: a phone (portrait or narrow window); above: the TV layouts fit
private val COMPACT_WIDTH = 600.dp
// Below: a phone in landscape, the blocks above the lists scroll with them
private val SHORT_HEIGHT = 480.dp

/** Size of the window on a touch device. The TV's is never read: its layouts stay as they are. */
@Immutable
data class ScreenSize(val touch: Boolean = false, val width: Dp = 960.dp, val height: Dp = 540.dp) {
    /** Narrow screen (phone): one column, full-width panels. */
    val compact: Boolean get() = touch && width < COMPACT_WIDTH

    /** Taller than wide. */
    val portrait: Boolean get() = touch && height > width

    /** Low screen (phone in landscape). */
    val short: Boolean get() = touch && height < SHORT_HEIGHT
}

val LocalScreen = staticCompositionLocalOf { ScreenSize() }

/** Side margin of the screens: [tv] (safe area of the TV, also on tablets), 16 dp on a phone. */
@Composable
fun screenMargin(tv: Dp): Dp = if (LocalScreen.current.compact) 16.dp else tv

/**
 * Taps on a TV surface (the Surface of androidx.tv only reacts to the remote): the tap focuses it, as the
 * remote would, then clicks it; a long press is the long OK. Nothing is added on a TV.
 */
fun Modifier.touchClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    if (!Device.isTouch) this else composed {
        val focus = remember { FocusRequester() }
        val click by rememberUpdatedState(onClick)
        val longClick by rememberUpdatedState(onLongClick)
        focusRequester(focus).pointerInput(onLongClick != null) {
            detectTapGestures(
                onLongPress = if (onLongClick == null) null else { _ ->
                    focus.tryFocus()
                    longClick?.invoke()
                },
                onTap = {
                    focus.tryFocus()
                    click()
                }
            )
        }
    }

/** Touch device: a tap on this element (not a focusable one) runs [onTap]. Nothing on a TV. */
fun Modifier.touchTap(onTap: () -> Unit): Modifier =
    if (!Device.isTouch) this else composed {
        val tap by rememberUpdatedState(onTap)
        pointerInput(Unit) { detectTapGestures { tap() } }
    }

/** Touch device: the taps on this element (outside its own buttons) do not reach the layer behind it. */
fun Modifier.blockTouches(): Modifier =
    if (!Device.isTouch) this else pointerInput(Unit) { detectTapGestures { } }

/** Touch device: [onTouch] on every touch inside this element (restarts the auto-hide delays of the panels). */
fun Modifier.onAnyTouch(onTouch: () -> Unit): Modifier =
    if (!Device.isTouch) this else composed {
        val touched by rememberUpdatedState(onTouch)
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    touched()
                }
            }
        }
    }

// Vertical distance of a swipe that switches channel
private val SWIPE_STEP = 72.dp

/**
 * Gestures over the picture of the players on a touch device: tap, double tap (with the third of the screen
 * tapped), long press and vertical swipe ([TouchSteps.swipe]: +1 up, -1 down). Nothing on a TV.
 */
fun Modifier.playerGestures(
    onTap: () -> Unit,
    onDoubleTap: ((TouchSteps.Side) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onSwipe: ((step: Int) -> Unit)? = null
): Modifier = if (!Device.isTouch) this else composed {
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val longPress by rememberUpdatedState(onLongPress)
    val swipe by rememberUpdatedState(onSwipe)
    val threshold = with(LocalDensity.current) { SWIPE_STEP.toPx() }
    pointerInput(onDoubleTap != null, onLongPress != null) {
        detectTapGestures(
            onDoubleTap = if (onDoubleTap == null) null else { offset ->
                doubleTap?.invoke(TouchSteps.side(offset.x, size.width.toFloat()))
            },
            onLongPress = if (onLongPress == null) null else { _ -> longPress?.invoke() },
            onTap = { tap() }
        )
    }.then(
        if (onSwipe == null) Modifier else Modifier.pointerInput(threshold) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    val step = TouchSteps.swipe(total, threshold)
                    if (step != 0) swipe?.invoke(step)
                },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    total += amount
                }
            )
        }
    )
}

/** Row of buttons; [wrap] (phone) lays them on as many lines as their width needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdaptiveButtonRow(wrap: Boolean, modifier: Modifier = Modifier, spacing: Dp = 10.dp, content: @Composable () -> Unit) {
    if (wrap) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) { content() }
    } else {
        // Never squeezed: past the width, the row scrolls (the remote brings the focused button into view)
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically
        ) { content() }
    }
}

/** Width of a dialog panel: [width] on a TV; on a phone, at most [width] and the screen less its margins. */
fun Modifier.dialogWidth(width: Dp): Modifier =
    if (!Device.isTouch) width(width) else padding(horizontal = 16.dp).widthIn(max = width).fillMaxWidth()

private fun FocusRequester.tryFocus() {
    try {
        requestFocus()
    } catch (e: IllegalStateException) {
        // Not attached: the click alone
    }
}
