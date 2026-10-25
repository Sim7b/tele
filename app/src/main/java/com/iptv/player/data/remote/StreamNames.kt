package com.iptv.player.data.remote

import com.iptv.player.data.optStringOrNull
import org.json.JSONObject

/**
 * Formats provider stream names ("VIP: TF1 ᴴᴰ", "FR - TF1 FHD ◉") for display on TV.
 */
object StreamNames {

    // Superscript letters often used by providers (ᴴᴰ, ᴿᴬᵂ, ᵁᴴᴰ...)
    private val SUPERSCRIPTS = mapOf(
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G', 'ᴴ' to 'H', 'ᴵ' to 'I',
        'ᴶ' to 'J', 'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P',
        'ᴿ' to 'R', 'ᵀ' to 'T', 'ᵁ' to 'U', 'ᵂ' to 'W', 'ˢ' to 'S', 'ᶠ' to 'F', 'ᵖ' to 'P',
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6',
        '⁷' to '7', '⁸' to '8', '⁹' to '9', 'ᵏ' to 'K'
    )

    private val DECORATIONS = Regex("""[◉●○◆◇★☆✪✦▶►■□|]+""")

    private fun normalize(raw: String): String =
        raw.map { SUPERSCRIPTS[it] ?: it }.joinToString("")

    /** Readable name: superscripts converted, decorative symbols removed. */
    fun clean(raw: String): String =
        normalize(raw)
            .replace(DECORATIONS, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('-', ':')
            .trim()

    /** Quality tags inferred from the name (4K, FHD, HD, SD, HEVC, RAW, 50 FPS). */
    fun qualityTags(raw: String): List<String> {
        val name = " ${normalize(raw).uppercase()} "
        val tags = mutableListOf<String>()
        fun has(pattern: String) = Regex("""(?<![A-Z0-9])(?:$pattern)(?![A-Z0-9])""").containsMatchIn(name)

        when {
            has("4K|UHD|2160P?") -> tags += "4K"
            has("FHD|1080P?") -> tags += "FHD"
            has("HD|720P?") -> tags += "HD"
            has("SD|576P?|480P?") -> tags += "SD"
        }
        if (has("HEVC|H\\.?265|X265")) tags += "HEVC"
        if (has("RAW")) tags += "RAW"
        Regex("""(?<![0-9])(50|60)\s?FPS""").find(name)?.let { tags += "${it.groupValues[1]} FPS" }
        return tags
    }

    /** Summary of the statistics measured by Dispatcharr (if available). */
    fun describeStats(stats: JSONObject): String? {
        val parts = listOfNotNull(
            stats.optStringOrNull("resolution"),
            stats.optStringOrNull("video_codec")?.uppercase(),
            stats.optStringOrNull("source_fps")?.let { "$it FPS" }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
