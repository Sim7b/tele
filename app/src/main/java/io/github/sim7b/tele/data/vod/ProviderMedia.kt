package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Technical description of a file as Xtream panels (and Dispatcharr, which relays them) give it in
 * get_vod_info / get_series_info: "video" and "audio" objects in the ffprobe format, "bitrate" in kb/s.
 */
internal data class ProviderMedia(
    val resolution: String?,
    val videoCodec: String?,
    val hdr: Boolean,
    val bitrateKbps: Int?,
    val audio: List<VodAudio>,
    val subtitles: List<String>
) {
    companion object {
        fun from(info: JSONObject?): ProviderMedia {
            val video = info?.optJSONObject("video")
            val width = video?.optInt("width", 0) ?: 0
            val height = video?.optInt("height", 0) ?: 0
            val transfer = video?.optStringOrNull("color_transfer").orEmpty()
            return ProviderMedia(
                resolution = resolution(width, height),
                videoCodec = codec(video?.optStringOrNull("codec_name")),
                hdr = transfer == "smpte2084" || transfer == "arib-std-b67",
                bitrateKbps = info?.optInt("bitrate", 0)?.takeIf { it > 0 },
                audio = objects(info?.opt("audio")).map { audio ->
                    VodAudio(
                        language = audio.optJSONObject("tags")?.optStringOrNull("language")?.takeIf { it != "und" },
                        codec = codec(audio.optStringOrNull("codec_name")),
                        channels = audio.optInt("channels", 0).takeIf { it > 0 }
                    )
                }.distinct(),
                subtitles = objects(info?.opt("subtitles") ?: info?.opt("subtitle")).mapNotNull { subtitle ->
                    subtitle.optJSONObject("tags")?.optStringOrNull("language") ?: subtitle.optStringOrNull("language")
                }.distinct()
            )
        }

        /** "2160p" / "1080p"... by width first: cinema formats are wide and less tall. */
        fun resolution(width: Int, height: Int): String? = when {
            width <= 0 || height <= 0 -> null
            width >= 3200 || height >= 2000 -> "2160p"
            width >= 1700 || height >= 1000 -> "1080p"
            width >= 1200 || height >= 700 -> "720p"
            else -> "${height}p"
        }

        fun codec(name: String?): String? = when (name?.lowercase()) {
            null, "" -> null
            "hevc", "h265" -> "HEVC"
            "h264", "avc" -> "H.264"
            "av1" -> "AV1"
            "mpeg4" -> "MPEG-4"
            "eac3" -> "E-AC3"
            "ac3" -> "AC3"
            "aac" -> "AAC"
            "dts" -> "DTS"
            "truehd" -> "TrueHD"
            "mp3" -> "MP3"
            else -> name.uppercase()
        }

        /** A single object or an array of objects. */
        private fun objects(value: Any?): List<JSONObject> = when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            else -> emptyList()
        }

        /** Image URL from a backdrop_path field (a string, or an array of strings). */
        fun firstImage(value: Any?): String? = when (value) {
            is String -> value.takeIf { it.startsWith("http") }
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { value.optString(it).takeIf { url -> url.startsWith("http") } }
            else -> null
        }
    }
}
