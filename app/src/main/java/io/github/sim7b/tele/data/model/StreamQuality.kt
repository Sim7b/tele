package io.github.sim7b.tele.data.model

import androidx.annotation.StringRes
import io.github.sim7b.tele.R

/**
 * Video quality of a stream, known without opening it: the resolution measured by Dispatcharr when the
 * stream last went through its proxy, otherwise the quality written in the provider's stream name.
 */
enum class StreamQuality {
    UHD, FHD, HD, SD, UNKNOWN;

    companion object {
        fun fromHeight(height: Int): StreamQuality = when {
            height >= 1800 -> UHD
            height >= 900 -> FHD
            height >= 650 -> HD
            height > 0 -> SD
            else -> UNKNOWN
        }

        fun fromTags(tags: List<String>): StreamQuality = when {
            "4K" in tags -> UHD
            "FHD" in tags -> FHD
            "HD" in tags -> HD
            "SD" in tags -> SD
            else -> UNKNOWN
        }

        /** The measured resolution wins over the name, which providers do not always keep accurate. */
        fun of(stream: StreamSource): StreamQuality =
            stream.measuredHeight?.let(::fromHeight) ?: fromTags(stream.qualityTags)

        /** Height of a Dispatcharr resolution such as "1920x1080". */
        fun parseHeight(resolution: String?): Int? =
            resolution?.substringAfter('x', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }
}

/**
 * Qualities favored when the app picks a channel's stream by itself (and the order of the stream picker).
 * Unknown qualities come right after the preferred ones; [SERVER_ORDER] keeps the Dispatcharr order.
 */
enum class QualityPreference(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    private val order: List<StreamQuality>?
) {
    SERVER_ORDER(R.string.quality_server_order, R.string.quality_server_order_description, null),
    BEST(
        R.string.quality_best, R.string.quality_best_description,
        listOf(StreamQuality.UHD, StreamQuality.FHD, StreamQuality.HD, StreamQuality.UNKNOWN, StreamQuality.SD)
    ),
    FHD_MAX(
        R.string.quality_fhd_max, R.string.quality_fhd_max_description,
        listOf(StreamQuality.FHD, StreamQuality.HD, StreamQuality.UNKNOWN, StreamQuality.SD, StreamQuality.UHD)
    ),
    HD_MAX(
        R.string.quality_hd_max, R.string.quality_hd_max_description,
        listOf(StreamQuality.HD, StreamQuality.SD, StreamQuality.UNKNOWN, StreamQuality.FHD, StreamQuality.UHD)
    );

    val reordersStreams: Boolean get() = order != null

    /**
     * Streams of a Dispatcharr channel to play one after the other in automatic mode, the ones that failed lately
     * (Télé API plugin) last; null to play the channel itself (Dispatcharr picks the stream and fails over). In
     * server order, only when the first stream of the server failed lately: Dispatcharr would try it first again,
     * and wait for its startup timeout (8 s by default) before the next one.
     */
    fun automaticOrder(streams: List<StreamSource>): List<StreamSource>? {
        if (streams.isEmpty()) return null
        if (!reordersStreams && !streams.first().recentFailures) return null
        val (failing, healthy) = sort(streams).partition { it.recentFailures }
        if (!reordersStreams && healthy.isEmpty()) return null
        return healthy + failing
    }

    /** Streams by preference; streams of the same quality keep the server order (stable sort). */
    fun sort(streams: List<StreamSource>): List<StreamSource> {
        val ranks = order ?: return streams
        return streams.sortedBy { ranks.indexOf(StreamQuality.of(it)) }
    }
}

/**
 * Picture limit after the connection proved too weak: two stalls of a playing channel within [STALL_WINDOW_MS]
 * lower the limit one step below the quality that stalled ([QualityCap.onStall]), for [CAP_MS]; the streams above
 * it then come after the others ([apply]). A further pair of stalls lowers it again.
 */
class QualityCap(private val clock: () -> Long) {
    private val stalls = ArrayDeque<Long>()
    private var cap: StreamQuality? = null
    private var capUntil = 0L

    /** The limit now; null without one. */
    fun current(): StreamQuality? = cap?.takeIf { clock() < capUntil }

    /** A stall of a channel playing at [playing]: true when the limit was just lowered (worth another stream). */
    fun onStall(playing: StreamQuality): Boolean {
        val now = clock()
        stalls.addLast(now)
        while (stalls.isNotEmpty() && now - stalls.first() > STALL_WINDOW_MS) stalls.removeFirst()
        if (stalls.size < STALLS) return false
        stalls.clear()
        val below = when (minOf(playing, current() ?: StreamQuality.UHD, compareBy { rank(it) })) {
            StreamQuality.UHD -> StreamQuality.FHD
            StreamQuality.FHD -> StreamQuality.HD
            StreamQuality.HD -> StreamQuality.SD
            else -> return false
        }
        cap = below
        capUntil = now + CAP_MS
        return true
    }

    /** [streams] with the ones above the limit last, in their order. */
    fun apply(streams: List<StreamSource>): List<StreamSource> {
        val limit = current() ?: return streams
        val (fitting, above) = streams.partition { rank(StreamQuality.of(it)) <= rank(limit) }
        return fitting + above
    }

    companion object {
        const val STALLS = 2
        const val STALL_WINDOW_MS = 90_000L
        const val CAP_MS = 30 * 60_000L

        // Picture sizes from the smallest; an unknown quality is taken as an HD one
        private fun rank(quality: StreamQuality): Int = when (quality) {
            StreamQuality.SD -> 0
            StreamQuality.HD, StreamQuality.UNKNOWN -> 1
            StreamQuality.FHD -> 2
            StreamQuality.UHD -> 3
        }
    }
}
