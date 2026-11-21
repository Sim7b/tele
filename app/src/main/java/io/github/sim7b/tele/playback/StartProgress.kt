package io.github.sim7b.tele.playback

import io.github.sim7b.tele.data.remote.ChannelStartState

/** What a channel that takes time to start is waiting for, shown under the spinner. */
enum class StartStep { PREPARING, CONNECTING, PROVIDER_SLOW, SWITCHING, STARTING, WAITING }

/**
 * The step of a slow start, from what Dispatcharr says of the channel (Télé API plugin) or, without it, from the
 * time spent: nothing for a quick start ([QUIET_MS]), so a zap shows no text.
 */
object StartProgress {
    const val QUIET_MS = 2_000L
    const val SLOW_MS = 6_000L
    // The provider sent nothing for so long after the connection: slow, Dispatcharr may switch soon
    private const val PROVIDER_SLOW_S = 4.0
    // A switch to the next stream stays shown this long, even when the next one starts at once
    const val SWITCH_SHOWN_MS = 4_000L

    /** [previousStream]: the stream the server played at the previous answer (a change: the previous one failed). */
    fun step(state: ChannelStartState?, previousStream: Long?, elapsedMs: Long): StartStep? {
        if (elapsedMs < QUIET_MS) return null
        if (state?.state == null) return if (elapsedMs >= SLOW_MS) StartStep.WAITING else null
        if (previousStream != null && state.stream != null && state.stream != previousStream) return StartStep.SWITCHING
        return when (state.state) {
            "initializing" -> StartStep.PREPARING
            "connecting" ->
                if (state.chunks == 0 && (state.sinceS ?: 0.0) >= PROVIDER_SLOW_S) StartStep.PROVIDER_SLOW else StartStep.CONNECTING
            "buffering" -> StartStep.PROVIDER_SLOW
            "waiting_for_clients", "active" -> StartStep.STARTING
            else -> if (elapsedMs >= SLOW_MS) StartStep.WAITING else null
        }
    }
}
