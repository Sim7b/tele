package com.iptv.player.ui.components

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * Requests focus, retrying briefly: the target element may not be attached yet
 * (Dialog composed in its own window, lazy list item not measured yet).
 */
suspend fun FocusRequester.requestFocusWithRetry(attempts: Int = 10, delayMs: Long = 50L): Boolean {
    repeat(attempts) {
        try {
            requestFocus()
            return true
        } catch (e: IllegalStateException) {
            delay(delayMs)
        }
    }
    return false
}
