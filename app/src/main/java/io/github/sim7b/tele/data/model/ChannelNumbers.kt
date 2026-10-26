package io.github.sim7b.tele.data.model

/**
 * Channel numbers typed on the remote: the number given by the source (Dispatcharr, Xtream, M3U
 * "tvg-chno"), or the 1-based position in the list when the source numbers none of its channels.
 */
object ChannelNumbers {

    const val MAX_DIGITS = 4

    fun find(channels: List<Channel>, number: Int): Channel? {
        if (number <= 0) return null
        return if (channels.any { it.number != null }) {
            channels.firstOrNull { it.number == number }
        } else {
            channels.getOrNull(number - 1)
        }
    }

    /** Typed digits with [digit] appended; restarts from [digit] once [MAX_DIGITS] are reached. */
    fun append(typed: String, digit: Int): String =
        if (typed.length >= MAX_DIGITS) digit.toString() else typed + digit
}
