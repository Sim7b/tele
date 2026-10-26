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

    /** Streams by preference; streams of the same quality keep the server order (stable sort). */
    fun sort(streams: List<StreamSource>): List<StreamSource> {
        val ranks = order ?: return streams
        return streams.sortedBy { ranks.indexOf(StreamQuality.of(it)) }
    }
}
