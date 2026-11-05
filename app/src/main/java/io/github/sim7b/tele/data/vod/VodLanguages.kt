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
        // Name prefixes ("FR", "EN"), tags and their combinations take a few dozen values: read once each
        private val prefixes = HashMap<String, Reading>()
        private val tagReadings = HashMap<List<String>, Reading>()
        private val combined = HashMap<Reading, VodLanguages>()

        fun estimate(name: String, tags: List<String>, group: String?): VodLanguages {
            val fromGroup = group?.let { groups.getOrPut(it) { read(it, codes = uppercaseWords(it)) } } ?: NOTHING
            val prefix = if (mayHavePrefix(name)) namePrefix(name) else null
            val fromName = if (prefix == null) NOTHING else prefixes.getOrPut(prefix) { read(prefix, codes = uppercaseWords(prefix)) }
            val fromTags = if (tags.isEmpty()) NOTHING else tagReadings.getOrPut(tags) { fromTags(tags) }
            val reading = fromGroup + fromName + fromTags
            return if (reading == NOTHING) EMPTY else combined.getOrPut(reading) { combine(reading) }
        }

        /** A prefix starts with an uppercase letter or "|" (after spaces): most names are skipped without the regex. */
        private fun mayHavePrefix(name: String): Boolean {
            val first = name.firstOrNull { !it.isWhitespace() } ?: return false
            return first == '|' || first in 'A'..'Z'
        }
    }

    /**
     * Prefix of a name: up to three uppercase codes of 2 to 4 letters, joined by a space, "-" or "/", before
     * "|", ":" or "- " ("FR | ", "|EN| ", "VOD FR - ", "BE-FR - "); the longest one. Read by hand: a regex
     * on every name of a library of tens of thousands costs seconds on a TV.
     */
    internal fun namePrefix(name: String): String? {
        var i = 0
        while (i < name.length && name[i].isWhitespace()) i++
        if (i < name.length && name[i] == '|') {
            i++
            while (i < name.length && name[i].isWhitespace()) i++
        }
        val start = i
        // Ends of the codes read, in order
        val ends = IntArray(3)
        var count = 0
        var position = start
        while (count < 3) {
            var p = position
            if (count > 0) {
                if (p >= name.length || (name[p] != ' ' && name[p] != '-' && name[p] != '/')) break
                p++
            }
            val wordStart = p
            while (p < name.length && name[p] in 'A'..'Z' && p - wordStart < 5) p++
            if (p - wordStart !in 2..4 || (p < name.length && name[p] in 'A'..'Z')) break
            ends[count++] = p
            position = p
        }
        for (k in count - 1 downTo 0) {
            var p = ends[k]
            while (p < name.length && name[p].isWhitespace()) p++
            if (p >= name.length) continue
            val c = name[p]
            if (c == '|' || c == ':' || ((c == '-' || c == '–') && p + 1 < name.length && name[p + 1].isWhitespace())) {
                return name.substring(start, ends[k])
            }
        }
        return null
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
        operator fun plus(other: Reading) = if (other == NOTHING) this else if (this == NOTHING) other else Reading(
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
