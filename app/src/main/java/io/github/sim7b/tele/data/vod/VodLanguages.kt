package io.github.sim7b.tele.data.vod

import java.text.Normalizer
import java.util.Locale

/** Audio and subtitle languages of a file (ISO 639-1 codes: "fr", "en"), as far as its names tell. */
data class VodLanguages(val audio: Set<String> = emptySet(), val subtitles: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = audio.isEmpty() && subtitles.isEmpty()

    operator fun plus(other: VodLanguages): VodLanguages =
        if (other.isEmpty) this else if (isEmpty) other else VodLanguages(audio + other.audio, subtitles + other.subtitles)
}

/**
 * Estimation of the languages of a movie or an episode from its playlist group ("FR | FILMS VOSTFR",
 * "|EN| NETFLIX", "ARABIC MOVIES") and from its name ("FR - Dune (2024) MULTI"): the whole group, but only
 * the prefix and the recognized tags of a name ("The French Connection" is no French version). Country codes
 * count in uppercase only, so that "It" or "De Gaulle" are never read as languages. Subtitled versions
 * (VOSTFR) keep their original audio.
 */
object VodLanguageGuess {

    private val TOKENS = Regex("""[^\p{L}\p{N}]+""")
    private val DIACRITICS = Regex("""\p{Mn}+""")
    // Prefix of a name: up to three uppercase codes before "|", ":", "-" ("FR | ", "|EN| ", "VOD FR - "),
    // separated by spaces (no nested repetition: uppercase titles must not make it backtrack)
    private val NAME_PREFIX = Regex("""^\s*\|?\s*([A-Z]{2,4}(?:\s[A-Z]{2,4}){0,2})\s*(?:\||:|[-–]\s)""")

    // French versions: dubbed, or subtitled with the original audio
    private val FRENCH_AUDIO = setOf("VF", "VFF", "VFQ", "VFI", "VF2", "FRENCH", "TRUEFRENCH", "FRANCAIS", "QUEBEC")
    private val FRENCH_SUBTITLES = setOf("VOSTFR", "VOSTF", "SUBFRENCH", "STFR")

    // Language names, in English and in their own language
    private val WORDS = mapOf(
        "ENGLISH" to "en", "ANGLAIS" to "en", "GERMAN" to "de", "DEUTSCH" to "de", "ALLEMAND" to "de",
        "SPANISH" to "es", "ESPANOL" to "es", "CASTELLANO" to "es", "LATINO" to "es", "ESPAGNOL" to "es",
        "ITALIAN" to "it", "ITALIANO" to "it", "ITALIEN" to "it", "PORTUGUESE" to "pt", "PORTUGUES" to "pt",
        "BRAZIL" to "pt", "BRASIL" to "pt", "ARABIC" to "ar", "ARABE" to "ar", "TURKISH" to "tr", "TURK" to "tr",
        "TURKCE" to "tr", "DUTCH" to "nl", "NEDERLANDS" to "nl", "POLISH" to "pl", "POLSKI" to "pl",
        "RUSSIAN" to "ru", "HINDI" to "hi", "KOREAN" to "ko", "JAPANESE" to "ja", "CHINESE" to "zh",
        "GREEK" to "el", "ROMANIAN" to "ro", "ALBANIAN" to "sq", "PERSIAN" to "fa", "FARSI" to "fa"
    )

    // Uppercase codes of a group or of a name prefix: country or language
    private val CODES = mapOf(
        "FR" to "fr", "FRA" to "fr", "QC" to "fr", "EN" to "en", "ENG" to "en", "UK" to "en", "GB" to "en",
        "US" to "en", "USA" to "en", "DE" to "de", "GER" to "de", "ES" to "es", "SPA" to "es", "IT" to "it",
        "ITA" to "it", "PT" to "pt", "BR" to "pt", "AR" to "ar", "TR" to "tr", "NL" to "nl", "PL" to "pl",
        "RU" to "ru", "GR" to "el", "RO" to "ro", "AL" to "sq", "KR" to "ko", "JP" to "ja"
    )

    /** Languages of a file from its [name], the language [tags] read in it ([VodNames]) and its playlist [group]. */
    fun estimate(name: String, tags: List<String>, group: String?): VodLanguages = Estimator().estimate(name, tags, group)

    /**
     * Estimation over a whole library: the reading of each group is kept, thousands of titles share a
     * few hundred groups. Not thread safe.
     */
    class Estimator {
        private val groups = HashMap<String, Reading>()

        fun estimate(name: String, tags: List<String>, group: String?): VodLanguages {
            val fromGroup = group?.let { groups.getOrPut(it) { read(it, codes = uppercaseWords(it)) } } ?: NOTHING
            val prefix = NAME_PREFIX.find(name)?.groupValues?.get(1)
            val fromName = if (prefix == null) NOTHING else read(prefix, codes = uppercaseWords(prefix))
            val reading = fromGroup + fromName + fromTags(tags)
            return if (reading == NOTHING) EMPTY else combine(reading)
        }
    }

    private val NOTHING = Reading()
    private val EMPTY = VodLanguages()

    /** Tags of [VodNames]: VF, VFQ, VOSTFR, MULTI, VO. */
    private fun fromTags(tags: List<String>): Reading {
        if (tags.isEmpty()) return NOTHING
        return Reading(
            explicitAudio = if (tags.any { it == "VF" || it == "VFQ" }) setOf("fr") else emptySet(),
            subtitles = if ("VOSTFR" in tags) setOf("fr") else emptySet(),
            multi = "MULTI" in tags
        )
    }

    /** What the words of a text say; [codes] are its words that may be country or language codes. */
    internal data class Reading(
        val explicitAudio: Set<String> = emptySet(),
        val countryAudio: Set<String> = emptySet(),
        val subtitles: Set<String> = emptySet(),
        val multi: Boolean = false
    ) {
        operator fun plus(other: Reading) = if (other == Reading()) this else if (this == Reading()) other else Reading(
            explicitAudio + other.explicitAudio,
            countryAudio + other.countryAudio,
            subtitles + other.subtitles,
            multi || other.multi
        )
    }

    private fun read(text: String, codes: Set<String>): Reading {
        val explicit = HashSet<String>(2)
        val subtitles = HashSet<String>(1)
        var multi = false
        val words = normalize(text).split(TOKENS)
        words.forEach { word ->
            when {
                word.isEmpty() -> Unit
                word in FRENCH_AUDIO -> explicit.add("fr")
                word in FRENCH_SUBTITLES -> subtitles.add("fr")
                word == "MULTI" -> multi = true
                else -> WORDS[word]?.let(explicit::add)
            }
        }
        // "VOST FR", "SUB FR", "ST FR": French subtitles
        words.zipWithNext().forEach { (a, b) -> if ((a == "VOST" || a == "SUB" || a == "ST") && b in setOf("FR", "FRA", "FRENCH")) subtitles.add("fr") }
        val country = codes.mapNotNull { CODES[it] }.toSet()
        return Reading(explicit, country, subtitles, multi)
    }

    private fun combine(reading: Reading): VodLanguages {
        val audio = LinkedHashSet<String>(reading.explicitAudio)
        // A subtitled version keeps its original audio: the country of the group is not its language
        if (reading.subtitles.isEmpty()) audio.addAll(reading.countryAudio)
        // MULTI: French and original audio in French playlists
        if (reading.multi && (reading.countryAudio.contains("fr") || reading.explicitAudio.contains("fr"))) audio.add("fr")
        return VodLanguages(audio, reading.subtitles)
    }

    private fun uppercaseWords(text: String): Set<String> =
        text.split(TOKENS).filter { it.length in 2..3 && it.all { c -> c in 'A'..'Z' } }.toSet()

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").uppercase(Locale.ROOT)
}
