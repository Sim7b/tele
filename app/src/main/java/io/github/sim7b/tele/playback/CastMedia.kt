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

    private const val DISPATCHARR_STREAM = "/proxy/ts/stream/"
    private val OUTPUT_FORMAT = Regex("[?&](?:output_format|output)=([A-Za-z0-9]+)")

    private fun dispatcharrFormat(url: String): String? = OUTPUT_FORMAT.find(url.substringBefore('#'))?.groupValues?.get(1)?.lowercase()

    /**
     * The same Dispatcharr channel in the formats a Chromecast reads: fragmented MP4 (one continuous file,
     * remuxed by Dispatcharr without re-encoding) and HLS, before the raw MPEG-TS it does not read. Dispatcharr
     * serves them without a key (as the MPEG-TS) and allows any origin. Other URLs are returned as they are.
     */
    fun castVariants(url: String): List<String> {
        if (!url.contains(DISPATCHARR_STREAM, ignoreCase = true) || dispatcharrFormat(url) != null) return listOf(url)
        val separator = if (url.contains('?')) "&" else "?"
        return listOf("${url}${separator}output_format=fmp4", "${url}${separator}output_format=m3u8", url)
    }

    /**
     * Type of the stream at [url], read from its extension (or the output format asked of Dispatcharr);
     * [live] streams without one are MPEG-TS.
     */
    fun mimeType(url: String, live: Boolean): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val extension = path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "")
        return when {
            path.contains(DISPATCHARR_STREAM) -> when (dispatcharrFormat(url)) {
                "fmp4", "mp4" -> MP4
                "hls", "m3u8" -> HLS
                else -> TS
            }
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
     * [urls] in the order to try them on a Chromecast, each with its [castVariants]: the HLS and DASH
     * variants first (an Xtream channel offers the same stream in .ts and .m3u8), then the MP4 files, then
     * the others, those it refuses ([insecureAdaptive]) last; each group in its original order.
     */
    fun castOrder(urls: List<String>, live: Boolean): List<String> =
        urls.flatMap { castVariants(it) }.distinct().sortedBy { url ->
            val type = mimeType(url, live)
            when {
                insecureAdaptive(url, live) -> 3
                type == HLS || type == DASH -> 0
                type == MP4 -> 1
                else -> 2
            }
        }
}
