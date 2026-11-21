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
    // Before the single new attempt of a stream refused for lack of a free connection
    const val NO_FREE_CONNECTION_DELAY_MS = 4_000L

    fun maxAttempts(hasPlayed: Boolean) = if (hasPlayed) PLAYBACK_ATTEMPTS else START_ATTEMPTS

    /** Delay before the retry number [attempt] (0-based): 1 s, 2 s, 4 s, then 8 s. */
    fun delayMs(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 3)).coerceAtMost(MAX_DELAY_MS)

    /**
     * Loading time after which the stream is taken as frozen: a channel that played gets [PLAYING_STALL_MS],
     * a start [START_STALL_MS] (Dispatcharr may first try several provider streams), a movie more. A Dispatcharr
     * channel ([dispatcharr]) answers only once its provider filled its buffer: often 10 s, sometimes more.
     */
    fun stallTimeoutMs(live: Boolean, hasPlayed: Boolean, dispatcharr: Boolean = false): Long = when {
        !hasPlayed && dispatcharr -> DISPATCHARR_START_STALL_MS
        !hasPlayed -> START_STALL_MS
        live -> PLAYING_STALL_MS
        else -> VOD_STALL_MS
    }

    const val START_STALL_MS = 20_000L
    const val DISPATCHARR_START_STALL_MS = 40_000L

    /**
     * Wait for the first bytes of a Dispatcharr channel: the server sends nothing until its provider filled the
     * buffer. A shorter wait asked the channel again, which on an account limited to one stream ended the first
     * request on the server, then refused the second one (503): the channel never started.
     */
    const val DISPATCHARR_FIRST_BYTES_S = 30L

    /** A live channel served by the proxy of a Dispatcharr server. */
    fun isDispatcharrLive(url: String): Boolean = url.contains("/proxy/ts/")
    const val PLAYING_STALL_MS = 12_000L
    const val VOD_STALL_MS = 30_000L
}
