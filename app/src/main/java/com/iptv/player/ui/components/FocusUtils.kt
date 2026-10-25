package com.iptv.player.ui.components

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * Demande le focus en réessayant brièvement : l'élément ciblé peut ne pas être encore attaché
 * (Dialog composé dans sa propre fenêtre, item de liste paresseuse pas encore mesuré).
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
