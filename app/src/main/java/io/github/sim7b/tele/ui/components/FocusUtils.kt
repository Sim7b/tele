package io.github.sim7b.tele.ui.components

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * Requests focus, retrying briefly: the target element may not be attached yet
 * (Dialog composed in its own window, lazy list item not measured yet), or refuse it for now.
 * Waits for the next frame first: when a screen replaces another, the focus of the removed element is
 * cleared at the end of the frame, after the new screen asked for it.
 */
suspend fun FocusRequester.requestFocusWithRetry(attempts: Int = 10, delayMs: Long = 50L): Boolean {
    try {
        withFrameNanos { }
    } catch (e: IllegalStateException) {
        // No frame clock (outside a composition): ask at once
    }
    repeat(attempts) {
        try {
            if (requestFocus()) return true
        } catch (e: IllegalStateException) {
            // Not attached yet
        }
        delay(delayMs)
    }
    return false
}

/**
 * For a menu opened by a long press on OK: the repeats and the release of that key reach the menu and
 * would confirm its first item. They are ignored until a new press has started in the menu.
 */
fun Modifier.ignoreHeldConfirm(): Modifier = composed {
    var armed by remember { mutableStateOf(false) }
    onPreviewKeyEvent { event ->
        val keyCode = event.nativeKeyEvent.keyCode
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER &&
            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) return@onPreviewKeyEvent false
        when {
            event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                armed = true
                false
            }
            !armed -> true
            event.type == KeyEventType.KeyUp -> {
                armed = false
                false
            }
            else -> false
        }
    }
}
