package io.github.sim7b.tele.data.vod

import java.text.Normalizer
import java.util.Locale

/** What a provider name tells: "FR | Dune: Part Two (2024) [4K] MULTI" -> Dune: Part Two, 2024, 4K, MULTI. */
data class ParsedVodName(
    val title: String,
    val year: Int?,
    val qualityTags: List<String>,
    val languageTags: List<String>,
    val season: Int? = null,
    val episode: Int? = null
)

/**
 * Reading of provider movie and series names, whatever the playlist conventions: country prefix,
 * year in brackets or not, quality and language tags, season and episode numbers.
 */
object VodNames {

    // "FR | ", "|FR| ", "FR: ", "[FR] ", "FR - ", "VOD FR - " at the start of a name: uppercase codes only,
    // titles such as "It: Chapter Two" or "Ma vie - ..." keep their first word
    private val PREFIX = Regex("""^\s*(?:\[[A-Z]{2,4}]|\|?\s*[A-Z]{2,3}(?:\s+[A-Z]{2,3})?\s*(?:\||:|\s[-–]))\s*""")
    private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})\b""")
    private val YEAR = Regex("""(?<![\d])(19[2-9]\d|20[0-4]\d)(?![\d])""")
    private val BRACKETS = Regex("""[(\[{]([^)\]}]*)[)\]}]""")
    private val SEPARATORS = Regex("""[\s._]+""")
    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val COUNTRY = Regex("""[A-Z]{2}""")

    private val QUALITY = linkedMapOf(
        "2160P" to "4K", "4K" to "4K", "UHD" to "4K", "1080P" to "1080p", "FHD" to "1080p", "720P" to "720p",
        "HD" to "HD", "SD" to "SD", "HDR" to "HDR", "HDR10" to "HDR", "DV" to "Dolby Vision", "HEVC" to "HEVC",
        "H265" to "HEVC", "X265" to "HEVC", "H264" to "H.264", "X264" to "H.264", "3D" to "3D",
        // Recorded in theater: worth knowing before playing
        "CAM" to "CAM", "HDCAM" to "CAM", "TS" to "TS", "HDTS" to "TS", "TELESYNC" to "TS", "TELECINE" to "TS",
        "SCREENER" to "SCR", "SCR" to "SCR", "DVDSCR" to "SCR"
    )
    private val LANGUAGE = linkedMapOf(
        "MULTI" to "MULTI", "MULTI-SUB" to "MULTI", "TRUEFRENCH" to "VF", "FRENCH" to "VF", "VF" to "VF", "VFF" to "VF",
        "VFQ" to "VFQ", "VFI" to "VF", "VO" to "VO", "VOST" to "VOSTFR", "VOSTFR" to "VOSTFR", "SUBFRENCH" to "VOSTFR"
    )

    fun parse(raw: String): ParsedVodName {
        var text = raw.replace(' ', ' ').trim()
        repeat(2) { text = text.replace(PREFIX, "") }

        val quality = LinkedHashSet<String>()
        val language = LinkedHashSet<String>()
        var season: Int? = null
        var episode: Int? = null
        EPISODE.find(text)?.let { match ->
            season = match.groupValues[1].toInt()
            episode = match.groupValues[2].toInt()
            // "The Bear S01E02 FHD VF": the tags after the number belong to the file
            text.substring(match.range.last + 1).replace(BRACKETS, " $1 ").split(SEPARATORS).forEach { tag(it, quality, language) }
            text = text.substring(0, match.range.first)
        }
        // Tags inside brackets ("[4K]", "(MULTI)"), the year included, are removed from the title
        var year: Int? = null
        text = BRACKETS.replace(text) { match ->
            val inner = match.groupValues[1].trim()
            val words = inner.split(SEPARATORS).filter { it.isNotEmpty() }
            when {
                YEAR.matches(inner) -> {
                    year = inner.toInt()
                    " "
                }
                words.isNotEmpty() && words.all { tag(it, quality, language) } -> " "
                // Country of the version: "(US)", "[KR]"
                COUNTRY.matches(inner) -> " "
                else -> match.value
            }
        }

        // Trailing tags and year: "Dune Part Two 2024 FHD VF"
        val words = text.trim().split(SEPARATORS).filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1) {
            val last = words.last().trim('-', '|', ':')
            when {
                last.isEmpty() -> words.removeAt(words.lastIndex)
                tag(last, quality, language) -> words.removeAt(words.lastIndex)
                year == null && YEAR.matches(last) -> {
                    year = last.toInt()
                    words.removeAt(words.lastIndex)
                }
                else -> break
            }
        }
        val title = words.joinToString(" ").trim().trim('-', '|', ':', '–').trim().ifEmpty { raw.trim() }
        return ParsedVodName(title, year, quality.toList(), language.toList(), season, episode)
    }

    private fun tag(word: String, quality: MutableSet<String>, language: MutableSet<String>): Boolean {
        val upper = word.uppercase(Locale.ROOT)
        QUALITY[upper]?.let { quality.add(it); return true }
        LANGUAGE[upper]?.let { language.add(it); return true }
        return false
    }

    /** Comparison key of a title: lowercase letters and digits, without accents nor articles' punctuation. */
    fun key(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .filter { it.isLetterOrDigit() }

    /**
     * Grouping key of a title: its TMDB id when known, else its title, and its year for a movie (remakes
     * share their title; episode files of a series seldom carry its year).
     */
    fun groupKey(kind: VodKind, title: String, year: Int?, tmdbId: String?): String {
        val prefix = if (kind == VodKind.MOVIE) "m" else "s"
        return tmdbId?.takeIf { it.isNotBlank() && it != "0" }?.let { "$prefix:tmdb:$it" }
            ?: if (kind == VodKind.MOVIE) "$prefix:${key(title)}:${year ?: ""}" else "$prefix:${key(title)}:"
    }

    /** Year at the start of a date ("2024-03-01", "2024"). */
    fun yearOf(date: String?): Int? = date?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
}
