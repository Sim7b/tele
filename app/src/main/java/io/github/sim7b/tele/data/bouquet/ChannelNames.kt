package io.github.sim7b.tele.data.bouquet

import io.github.sim7b.tele.data.remote.StreamNames
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Normalization of provider channel and category names, to recognize the same channel or category
 * across providers: "FR | TF1 FHD", "TF1 ᴴᴰ" and "TF1 HEVC" all give the key "tf1"; "Sport",
 * "FR | Sports" and "France Sport" all give the category key "sport". Digits are kept, so that
 * France 2 and France 3 never match.
 */
class ChannelNames(homeTokens: Set<String>) {

    // Country / language words of the user's country, removed from the names ("fr", "france", "french"...)
    private val home = homeTokens.map { fold(it) }.filter { it.isNotEmpty() }.toSet()
    private val homePattern = home.joinToString("|") { Regex.escape(it) }

    // "FR: ", "FR | ", "|FR| ", "FRANCE - " at the start of a name
    private val homePrefix = if (home.isEmpty()) null else Regex("""^\W*(?:$homePattern)\W*[:|\-–»>]+\s*""", RegexOption.IGNORE_CASE)
    // Short country codes ("fr", "fra") as separate words, anywhere ("TF1 FR", "|FR| TF1")
    private val homeCodes = home.filter { it.length <= 3 }.toSet()

    // Keys by name: the displayed channels are rebuilt on every guide refresh (every 30 s)
    private val channelKeys = ConcurrentHashMap<String, String>()

    /** Comparison key of a channel name: lowercase letters and digits, without quality nor country. */
    fun channelKey(name: String): String = channelKeys.getOrPut(name) { words(name, removeAllHomeWords = false).joinToString("") }

    /** Readable channel name without quality nor country prefix ("FR | TF1 FHD" -> "TF1"). */
    fun channelDisplay(name: String): String = cleanDisplay(name, removeAllHomeWords = false)

    /** Name of one version of a channel, quality kept, country removed ("FR | TF1 FHD" -> "TF1 FHD"). */
    fun versionDisplay(name: String): String =
        stripDecorations(name).split(Regex("""\s+"""))
            .filter { it.isNotEmpty() && fold(it) !in homeCodes }
            .joinToString(" ")
            .ifEmpty { name.trim() }

    /** Comparison key of a category: also without country words and plurals ("France Sports" -> "sport"). */
    fun categoryKey(name: String): String =
        words(name, removeAllHomeWords = true).joinToString("") { singular(it) }

    /** Readable category name without country nor separators ("FR | Sport" -> "Sport"). */
    fun categoryDisplay(name: String): String = cleanDisplay(name, removeAllHomeWords = true)

    private fun words(name: String, removeAllHomeWords: Boolean): List<String> {
        val base = fold(stripDecorations(name))
        return base.split(SEPARATORS)
            .filter { it.isNotEmpty() && it !in QUALITY_WORDS && !QUALITY_PATTERN.matches(it) }
            .filterNot { it in homeCodes || (removeAllHomeWords && it in home) }
    }

    private fun cleanDisplay(name: String, removeAllHomeWords: Boolean): String {
        val text = stripDecorations(name)
        val kept = text.split(Regex("""\s+""")).filter { word ->
            val folded = fold(word)
            folded.isNotEmpty() || word.any { it.isLetterOrDigit() || it == '+' || it == '&' }
        }.filterNot { word ->
            val folded = fold(word)
            folded in QUALITY_WORDS || QUALITY_PATTERN.matches(folded) || folded in homeCodes ||
                (removeAllHomeWords && folded in home)
        }
        return kept.joinToString(" ").trim().trim('-', ':', '|', '·').trim().ifEmpty { name.trim() }
    }

    /**
     * Superscripts, country prefix ("FR | "), bracketed notes ("(1080p)", "[Not 24/7]") and decorative
     * symbols removed. The prefix goes first: its "|" separator is also a decoration.
     */
    private fun stripDecorations(name: String): String {
        val plain = StreamNames.normalize(name)
        val withoutPrefix = homePrefix?.let { plain.replace(it, "") } ?: plain
        return withoutPrefix.replace(BRACKETS, " ").replace(FRAME_RATE, " ").replace(DECORATIONS, " ")
    }

    companion object {
        private val DIACRITICS = Regex("""\p{Mn}+""")
        private val SEPARATORS = Regex("""[^\p{L}\p{N}]+""")
        private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*]""")
        private val DECORATIONS = Regex("""[◉●○◆◇★☆✪✦▶►■□|]+""")
        // "50 FPS", "60fps": a frame rate, not part of the name
        private val FRAME_RATE = Regex("""\b\d{2}\s?fps\b""", RegexOption.IGNORE_CASE)
        private val QUALITY_WORDS = setOf(
            "fhd", "uhd", "hd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "x265", "x264", "hdr", "sdr",
            "raw", "backup", "bkp", "vip", "fps", "low", "high"
        )
        // 1080p, 720p, 2160p, 1080i, 50fps, 60fps...
        private val QUALITY_PATTERN = Regex("""\d{3,4}[pi]|\d{2}fps""")

        /** Lowercase, without accents; "+" spelled "plus" and "&" spelled "et" (Canal+ = Canal Plus). */
        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .lowercase(Locale.ROOT)
                .replace("+", " plus ")
                .replace("&", " et ")
                .trim()

        private fun singular(word: String): String =
            if (word.length > 3 && (word.endsWith('s') || word.endsWith('x'))) word.dropLast(1) else word

        /** Country and language words of [locale] ("fr", "fra", "france", "french", "francais"). */
        fun homeTokens(locale: Locale): Set<String> = buildSet {
            fun addSafe(value: () -> String) = try {
                value().takeIf { it.isNotBlank() }?.let { add(it) }
            } catch (_: Exception) {
                null
            }
            addSafe { locale.country }
            addSafe { locale.isO3Country }
            addSafe { locale.getDisplayCountry(locale) }
            addSafe { locale.getDisplayCountry(Locale.ENGLISH) }
            addSafe { locale.language }
            addSafe { locale.getDisplayLanguage(locale) }
            addSafe { locale.getDisplayLanguage(Locale.ENGLISH) }
        }
    }
}
