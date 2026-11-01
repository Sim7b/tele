package io.github.sim7b.tele.playback

/**
 * Choices for a stream played on a Chromecast. The Chromecast fetches the stream itself: no API key, no
 * custom User-Agent (the key never goes into the URL), and its receiver reads HLS, DASH and the usual files
 * better than raw MPEG-TS.
 */
object CastMedia {

    const val HLS = "application/x-mpegURL"
    const val DASH = "application/dash+xml"
    const val TS = "video/mp2t"
    const val MP4 = "video/mp4"

    /** Type of the stream at [url], read from its extension; [live] streams without one are MPEG-TS. */
    fun mimeType(url: String, live: Boolean): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val extension = path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "")
        return when {
            extension == "m3u8" || path.contains(".m3u8") -> HLS
            extension == "mpd" -> DASH
            extension == "ts" || path.contains("/proxy/ts/") -> TS
            extension == "mp4" || extension == "m4v" || extension == "mov" -> MP4
            extension == "mkv" -> "video/x-matroska"
            extension == "webm" -> "video/webm"
            extension == "avi" -> "video/x-msvideo"
            extension == "mp3" -> "audio/mpeg"
            extension == "aac" -> "audio/aac"
            live -> TS
            else -> MP4
        }
    }

    /**
     * HLS or DASH over plain HTTP: the receiver of the Chromecast is an HTTPS page, it refuses to fetch them
     * (mixed content). A file over HTTP is read by its video element, it plays.
     */
    fun insecureAdaptive(url: String, live: Boolean): Boolean =
        url.startsWith("http://", ignoreCase = true) && mimeType(url, live).let { it == HLS || it == DASH }

    /**
     * [urls] in the order to try them on a Chromecast: the HLS and DASH variants first (an Xtream channel
     * offers the same stream in .ts and .m3u8), the others after, those it refuses ([insecureAdaptive]) last,
     * each group in its original order.
     */
    fun castOrder(urls: List<String>, live: Boolean): List<String> =
        urls.distinct().sortedBy { url ->
            when {
                insecureAdaptive(url, live) -> 2
                mimeType(url, live) == HLS || mimeType(url, live) == DASH -> 0
                else -> 1
            }
        }
}
