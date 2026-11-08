package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.remote.StreamNames

/**
 * Quality of a file of a movie or an episode, from what is known of it: the resolution measured by the server,
 * else the tags of its title, else its file name ("Dune.2021.2160p.HDR.x265.mkv"). "4K HEVC HDR", "1080p", "SD",
 * "CAM" (filmed in a theater); null when nothing tells it.
 */
object VodQuality {

    fun label(version: VodVersion): String? {
        val nameTags = StreamNames.qualityTags(version.name)
        val tags = version.qualityTags + nameTags
        val tier = version.resolution?.let(::tier) ?: tierOfTags(tags)
        val codec = version.videoCodec ?: "HEVC".takeIf { "HEVC" in tags }
        val upperName = " ${version.name.uppercase()} "
        val dynamic = when {
            DOLBY_VISION.containsMatchIn(upperName) -> "Dolby Vision"
            version.hdr || HDR.containsMatchIn(upperName) -> "HDR"
            else -> null
        }
        // Filmed in a theater: said first, it matters more than the resolution
        val theater = "CAM".takeIf { THEATER.containsMatchIn(" ${tags.joinToString(" ").uppercase()} $upperName ") }
        return listOfNotNull(theater, tier, codec, dynamic).distinct().joinToString(" ").ifBlank { null }
    }

    /** "2160p" -> "4K", "1080p", "720p", lower -> "SD". */
    private fun tier(resolution: String): String? {
        val lines = Regex("""(\d{3,4})""").find(resolution)?.value?.toIntOrNull() ?: return resolution.ifBlank { null }
        return when {
            lines >= 2000 -> "4K"
            lines >= 1000 -> "1080p"
            lines >= 700 -> "720p"
            else -> "SD"
        }
    }

    private fun tierOfTags(tags: List<String>): String? = when {
        "4K" in tags -> "4K"
        "FHD" in tags -> "1080p"
        "HD" in tags -> "720p"
        "SD" in tags -> "SD"
        else -> null
    }

    private val DOLBY_VISION = Regex("""(?<![A-Z0-9])(DV|DOVI|DOLBY[ ._-]?VISION)(?![A-Z0-9])""")
    private val HDR = Regex("""(?<![A-Z0-9])HDR(10\+?)?(?![A-Z0-9])""")
    private val THEATER = Regex("""(?<![A-Z0-9])(CAM|HDCAM|TELESYNC|HDTS)(?![A-Z0-9])""")
}
