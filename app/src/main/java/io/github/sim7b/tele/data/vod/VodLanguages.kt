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
 * count in uppercase only, so that "It" or "De Gaulle" are never read as languages.
 *
 * What the playlists write:
 * - dubbed versions: VF, VFF, VFQ, TRUEFRENCH, DUBLADO, DUBLAJ, LEKTOR, "ENGLISH DUB", "GERMAN DL";
 * - subtitled versions keep their original audio, the country of the group is then the language of the
 *   subtitles: VOSTFR, VOST (French acronym: French when nothing else tells), VOSTA / VOSTEN (English),
 *   VOSE (Spanish), LEGENDADO (Portuguese), ALTYAZILI (Turkish), NAPISY (Polish), "SUB FR", "ENG SUBS";
 * - MULTI / DUAL: the local language and the original one; MULTI-SUB / MULTISUB: several subtitle languages
 *   (not several audio tracks), the local one most likely among them; VO: the original audio only;
 * - written in Arabic, or an Arabic group in Latin letters ("ARABIC MOVIES", "AR | FILMS"): foreign movies and
 *   series subtitled in Arabic, so the original audio (English when nothing tells) with Arabic subtitles,
 *   unless dubbed (مدبلج, "ARABIC DUBBED") or Arabic content (عربي, مصري, خليجي).
 */
object VodLanguageGuess {

    private val TOKENS = Regex("""[^\p{L}\p{N}]+""")
    private val DIACRITICS = Regex("""\p{Mn}+""")

    // Versions dubbed in a language
    private val DUBBED = mapOf(
        "VF" to "fr", "VFF" to "fr", "VFQ" to "fr", "VFI" to "fr", "VF2" to "fr", "VFB" to "fr", "VOF" to "fr",
        "TRUEFRENCH" to "fr", "QUEBEC" to "fr",
        "DUBLADO" to "pt", "DUBLADOS" to "pt", "DUBLADA" to "pt", "DUBLADAS" to "pt", "DUBLAGEM" to "pt",
        "DUBLAJ" to "tr", "DUBLAJLI" to "tr", "LEKTOR" to "pl", "DOPPIATO" to "it", "DOPPIATI" to "it",
        "DOBLADO" to "es", "DOBLADOS" to "es", "DOBLADA" to "es", "SYNCHRO" to "de"
    )

    // Versions subtitled in a language (original audio)
    private val SUBTITLED = mapOf(
        "VOSTFR" to "fr", "VOSTF" to "fr", "SUBFRENCH" to "fr", "STFR" to "fr", "SUBFR" to "fr", "FRSUB" to "fr",
        "FASTSUB" to "fr", "SME" to "fr", "STSME" to "fr",
        "VOSTA" to "en", "VOSTEN" to "en", "VOSTENG" to "en", "VOSTANG" to "en", "ENGSUB" to "en",
        "ENGSUBS" to "en", "SUBENG" to "en", "SUBBEDENG" to "en",
        "VOSE" to "es", "SUBESP" to "es", "SUBTITULADO" to "es", "SUBTITULADA" to "es",
        "SUBTITULADOS" to "es", "SUBTITULADAS" to "es",
        "LEGENDADO" to "pt", "LEGENDADOS" to "pt", "LEGENDADA" to "pt", "LEGENDADAS" to "pt", "ALTYAZI" to "tr", "ALTYAZILI" to "tr", "NAPISY" to "pl",
        "SUBITA" to "it", "SOTTOTITOLATO" to "it", "GERSUB" to "de", "UNTERTITEL" to "de", "NLSUB" to "nl"
    )

    // Words before a language ("SUB FR", "VOST EN", "DUB PL") or after it ("ENG SUBS", "ENGLISH DUB")
    private val SUB_WORDS = setOf("SUB", "SUBS", "SUBBED", "SUBTITLE", "SUBTITLES", "SUBTITLED", "ST", "VOST", "HARDSUB", "HARDSUBS")
    private val DUB_WORDS = setOf("DUB", "DUBBED", "DUBBING", "DL")

    // Subtitled in the local language, whatever it is: the country of the group ("FR | FILMS VOST")
    private val LOCAL_SUBTITLES = setOf("VOST", "SUBBED", "SUBTITLED", "HARDSUB", "HARDSUBS")
    private val MULTI_SUBTITLES = setOf("MULTISUB", "MULTISUBS", "MULTISUBTITLES")
    private val MULTI_AUDIO = setOf("MULTI", "DUAL")
    private val ORIGINAL = setOf("VO", "VOA", "ORIGINAL")

    // Language names, in English and in their own language
    private val WORDS = mapOf(
        "FRENCH" to "fr", "FRANCAIS" to "fr", "ENGLISH" to "en", "ANGLAIS" to "en",
        "GERMAN" to "de", "DEUTSCH" to "de", "ALLEMAND" to "de",
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

    // Codes next to a subtitle or dub word, in any case ("sub fr", "VOST ENG"): ISO 639-2 ones too
    private val CODES_NEXT_TO_TAGS = CODES + mapOf("FRE" to "fr", "ESP" to "es", "POR" to "pt", "JPN" to "ja", "KOR" to "ko")

    // Arabic stems (after "ال" / "و" and hamza variants are removed): Arabic content, and original languages
    private val ARABIC_CONTENT = listOf("عرب", "مصر", "خليج", "سوري", "لبنان", "مغرب", "عراق", "جزائر", "تونس")
    private val ARABIC_ORIGINS = listOf(
        "ترك" to "tr", "هند" to "hi", "كوري" to "ko", "يابان" to "ja", "انمي" to "ja", "صين" to "zh",
        "اجنب" to "en", "امريك" to "en", "انجليز" to "en", "فرنس" to "fr", "اسبان" to "es"
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
            // A name written in Arabic tells through its words ("مترجم", "مدبلج"), and its script itself
            val fromArabic = if (hasArabic(name)) readArabic(name) else NOTHING
            val reading = fromGroup + fromName + fromTags + fromArabic
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

    /** Tags of [VodNames]: VF, VFQ, VOSTFR, VOSTEN, MULTI, MULTISUB, VO. */
    private fun fromTags(tags: List<String>): Reading {
        if (tags.isEmpty()) return NOTHING
        val subtitles = HashSet<String>(1)
        if ("VOSTFR" in tags) subtitles.add("fr")
        if ("VOSTEN" in tags) subtitles.add("en")
        return Reading(
            explicitAudio = if (tags.any { it == "VF" || it == "VFQ" }) setOf("fr") else emptySet(),
            subtitles = subtitles,
            multi = "MULTI" in tags,
            multiSubtitles = "MULTISUB" in tags,
            original = "VO" in tags
        )
    }

    /** What the words of a text say; [codes] are its words that may be country or language codes. */
    internal data class Reading(
        val explicitAudio: Set<String> = emptySet(),
        val countryAudio: Set<String> = emptySet(),
        val subtitles: Set<String> = emptySet(),
        // MULTI / DUAL: the local language and the original one
        val multi: Boolean = false,
        // VOST, SUBBED: subtitled in the local language (French when nothing tells, for VOST)
        val localSubtitles: Boolean = false,
        val localSubtitlesDefault: Set<String> = emptySet(),
        // MULTI-SUB: several subtitle languages
        val multiSubtitles: Boolean = false,
        // VO: the original audio only
        val original: Boolean = false,
        // Written in Arabic: original audio ([arabicOrigins], else English) with Arabic subtitles, unless dubbed
        val arabic: Boolean = false,
        val arabicDubbed: Boolean = false,
        val arabicOrigins: Set<String> = emptySet()
    ) {
        operator fun plus(other: Reading) = if (other == NOTHING) this else if (this == NOTHING) other else Reading(
            explicitAudio + other.explicitAudio,
            countryAudio + other.countryAudio,
            subtitles + other.subtitles,
            multi || other.multi,
            localSubtitles || other.localSubtitles,
            localSubtitlesDefault + other.localSubtitlesDefault,
            multiSubtitles || other.multiSubtitles,
            original || other.original,
            arabic || other.arabic,
            arabicDubbed || other.arabicDubbed,
            arabicOrigins + other.arabicOrigins
        )
    }

    private fun read(text: String, codes: Set<String>): Reading {
        val words = normalize(text).split(TOKENS).filter { it.isNotEmpty() }
        val explicit = HashSet<String>(2)
        val subtitles = HashSet<String>(1)
        // Codes read as the language of a subtitle or a dub: not the country of the group
        val tagCodes = HashSet<String>(1)
        var multi = false
        var local = false
        val localDefault = HashSet<String>(1)
        var multiSubtitles = false
        var original = false
        // "ARABIC", "ARABE": an Arabic group, read like one written in Arabic
        var arabicGroup = false
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val next = words.getOrNull(i + 1)
            val nextLanguage = next?.let { WORDS[it] ?: CODES_NEXT_TO_TAGS[it] }
            val language = WORDS[word] ?: CODES_NEXT_TO_TAGS[word]
            when {
                // "MULTI SUB", "MULTI-SUBS": several subtitle languages, not several audio tracks
                word == "MULTI" && (next == "SUB" || next == "SUBS" || next == "SUBTITLES") -> {
                    multiSubtitles = true
                    i++
                }
                word in MULTI_SUBTITLES -> multiSubtitles = true
                // "SUB FR", "VOST EN", "ST FR", "HARDSUB ENG"
                word in SUB_WORDS && nextLanguage != null -> {
                    subtitles.add(nextLanguage)
                    tagCodes.add(next)
                    i++
                }
                // "DUB PL", "DUBBED EN"
                word in DUB_WORDS && word != "DL" && nextLanguage != null -> {
                    explicit.add(nextLanguage)
                    tagCodes.add(next)
                    i++
                }
                // "ENG SUBS", "ENGLISH SUBTITLES", "FR SUB"
                language != null && next != null && next in SUB_WORDS -> {
                    subtitles.add(language)
                    tagCodes.add(word)
                    i++
                }
                // "ENGLISH DUB", "GERMAN DL" (dual language: German and the original)
                language != null && next != null && next in DUB_WORDS -> {
                    explicit.add(language)
                    if (next == "DL") multi = true
                    tagCodes.add(word)
                    i++
                }
                word in DUBBED -> explicit.add(DUBBED.getValue(word))
                word in SUBTITLED -> subtitles.add(SUBTITLED.getValue(word))
                word in LOCAL_SUBTITLES -> {
                    local = true
                    if (word == "VOST") localDefault.add("fr")
                }
                word in MULTI_AUDIO -> multi = true
                word in ORIGINAL -> original = true
                else -> WORDS[word]?.let { if (it == "ar") arabicGroup = true else explicit.add(it) }
            }
            i++
        }
        val country = codes.filter { it !in tagCodes }.mapNotNull { CODES[it] }.toMutableSet()
        // "AR | FILMS": the Arabic country code too
        if (country.remove("ar")) arabicGroup = true
        val arabic = if (hasArabic(text)) readArabic(text) else NOTHING
        return Reading(
            explicit, country, subtitles, multi, local, localDefault, multiSubtitles, original, arabic = arabicGroup
        ) + arabic
    }

    private fun hasArabic(text: String): Boolean = text.any { it in '\u0600'..'\u06FF' }

    /** Arabic words of a text: "مترجم" (subtitled), "مدبلج" (dubbed), the origin ("تركي", "هندي", "عربي"). */
    private fun readArabic(text: String): Reading {
        var dubbed = false
        var arabicContent = false
        val origins = HashSet<String>(1)
        val subtitles = HashSet<String>(1)
        // Normalized: the hamza and the short vowels are combining marks, removed with the diacritics
        for (raw in normalize(text).split(TOKENS)) {
            if (raw.isEmpty() || !hasArabic(raw)) continue
            val word = arabicStem(raw)
            when {
                word.startsWith("مدبلج") -> dubbed = true
                word.startsWith("مترجم") -> subtitles.add("ar")
                ARABIC_CONTENT.any { word.startsWith(it) } -> arabicContent = true
                else -> ARABIC_ORIGINS.firstOrNull { word.startsWith(it.first) }?.let { origins.add(it.second) }
            }
        }
        return Reading(
            explicitAudio = if (arabicContent) setOf("ar") else emptySet(),
            subtitles = subtitles,
            arabic = true,
            arabicDubbed = dubbed,
            arabicOrigins = origins
        )
    }

    /** An Arabic word without its hamza variants, final ta marbuta and "ال" / "و" / "ب" prefixes. */
    internal fun arabicStem(word: String): String {
        var w = word.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').replace("ـ", "")
        for (prefix in listOf("وال", "بال", "ال", "و")) {
            if (w.startsWith(prefix) && w.length - prefix.length >= 3) {
                w = w.substring(prefix.length)
                break
            }
        }
        return w
    }

    private fun combine(reading: Reading): VodLanguages {
        val audio = LinkedHashSet<String>(reading.explicitAudio)
        val subtitles = LinkedHashSet<String>(reading.subtitles)
        if (reading.localSubtitles) subtitles.addAll(reading.countryAudio.ifEmpty { reading.localSubtitlesDefault })
        // Several subtitle languages: the local one most likely among them
        if (reading.multiSubtitles) subtitles.addAll(reading.countryAudio)
        val subtitled = reading.subtitles.isNotEmpty() || reading.localSubtitles || reading.multiSubtitles
        // A subtitled or original version keeps its original audio: the country of the group is not its language.
        // MULTI: the local language and the original one
        if (reading.multi || (!subtitled && !reading.original)) audio.addAll(reading.countryAudio)
        if (reading.arabic) {
            when {
                reading.arabicDubbed -> audio.add("ar")
                // Arabic content (عربي), or the Arabic of a group: no subtitles to tell
                "ar" in audio -> Unit
                else -> {
                    // Foreign movies and series subtitled in Arabic: English when nothing tells the origin
                    if (reading.arabicOrigins.isNotEmpty()) audio.addAll(reading.arabicOrigins) else if (audio.isEmpty()) audio.add("en")
                    subtitles.add("ar")
                }
            }
        }
        return VodLanguages(audio, subtitles)
    }

    private fun uppercaseWords(text: String): Set<String> =
        text.split(TOKENS).filter { it.length in 2..3 && it.all { c -> c in 'A'..'Z' } }.toSet()

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").uppercase(Locale.ROOT)
}
