package io.github.sim7b.tele.ui.components

import androidx.compose.runtime.withFrameNanos
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
