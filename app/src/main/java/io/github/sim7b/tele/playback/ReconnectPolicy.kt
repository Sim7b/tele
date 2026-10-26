package io.github.sim7b.tele.playback

/**
 * Retries of a failed stream. A stream that never started gets a few quick attempts (Dispatcharr
 * fails over to a backup stream on the server side); a stream that dropped during playback, usually a
 * provider or network hiccup, gets more attempts, spaced out up to [MAX_DELAY_MS].
 */
object ReconnectPolicy {
    const val START_ATTEMPTS = 3
    const val PLAYBACK_ATTEMPTS = 6
    private const val MAX_DELAY_MS = 8_000L

    fun maxAttempts(hasPlayed: Boolean) = if (hasPlayed) PLAYBACK_ATTEMPTS else START_ATTEMPTS

    /** Delay before the retry number [attempt] (0-based): 1 s, 2 s, 4 s, then 8 s. */
    fun delayMs(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 3)).coerceAtMost(MAX_DELAY_MS)
}
