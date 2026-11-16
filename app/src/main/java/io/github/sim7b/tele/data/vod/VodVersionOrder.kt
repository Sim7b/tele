package io.github.sim7b.tele.data.vod

import java.util.Locale

/**
 * Language of a movie or series from another country than the TV: dubbed in the language of the TV, or in its
 * original language with subtitles in the language of the TV.
 */
enum class AudioPreference { DUBBED, ORIGINAL }

/**
 * Tracks a movie or episode starts with when none was chosen for it: the [audio] language preferred, and
 * subtitles turned on in [subtitles] (null: the subtitles of the stream are left as they are, forced ones included).
 */
data class DefaultTracks(val audio: String?, val subtitles: String?)

/** Order of the files of a movie or an episode: the first one is played when none is chosen. */
enum class VersionOrder {
    // The language of the TV first (audio, then subtitles), then the best picture
    LANGUAGE_FIRST,

    // The best picture first, then the language of the TV
    QUALITY_FIRST,

    // As the providers list them
    AS_LISTED
}

/**
 * Ranking of the files of a title by what the providers and the Télé API plugin tell of them: the audio tracks
 * and the language tags of their names (VF, MULTI, VOSTFR), and their quality (4K, HDR, theater copies last).
 */
object VodVersionOrder {

    /**
     * [details] with its files, and the files of each episode, in [order] for a TV in [language]; with
     * [AudioPreference.ORIGINAL] and a title from elsewhere ([original] language), the original version with
     * subtitles in [language] first.
     */
    fun apply(
        details: VodDetails,
        order: VersionOrder,
        language: String,
        preference: AudioPreference = AudioPreference.DUBBED,
        original: String? = null
    ): VodDetails {
        if (order == VersionOrder.AS_LISTED) return details
        return details.copy(
            versions = sort(details.versions, order, language, preference, original),
            seasons = details.seasons.map { season ->
                season.copy(episodes = season.episodes.map { it.copy(versions = sort(it.versions, order, language, preference, original)) })
            }
        )
    }

    fun sort(
        versions: List<VodVersion>,
        order: VersionOrder,
        language: String,
        preference: AudioPreference = AudioPreference.DUBBED,
        original: String? = null
    ): List<VodVersion> {
        if (versions.size < 2 || order == VersionOrder.AS_LISTED) return versions
        val wantsOriginal = wantsOriginal(preference, language, original)
        val byLanguage = compareByDescending<VodVersion> {
            if (wantsOriginal) originalScore(it, language, original!!) else languageScore(it, language)
        }
        val byQuality = compareByDescending<VodVersion> { qualityScore(it) }
        // Stable: files equal on both stay in the order of the providers
        return versions.sortedWith(if (order == VersionOrder.LANGUAGE_FIRST) byLanguage.then(byQuality) else byQuality.then(byLanguage))
    }

    /** The original version is wanted: [preference] says so and the title comes from a country of another language. */
    internal fun wantsOriginal(preference: AudioPreference, language: String, original: String?): Boolean {
        if (preference != AudioPreference.ORIGINAL || original == null) return false
        val wanted = iso3(original) ?: return false
        return wanted != iso3(language)
    }

    /**
     * For the original version with subtitles in [language]: 2 for the original audio (its track, or VO / VOST /
     * MULTI in the name), plus 1 for subtitles in [language]; a version dubbed in [language] only gets 0.
     */
    internal fun originalScore(version: VodVersion, language: String, original: String): Int {
        val wantedAudio = iso3(original)
        val tv = iso3(language)
        val tags = version.languageTags.map { it.uppercase(Locale.ROOT) }
        val originalAudio = version.audio.any { iso3(it.language) == wantedAudio } ||
            tags.any { it == "VO" || it.startsWith("VOST") || it == "MULTI" }
        val subtitled = version.subtitles.any { iso3(it) == tv } ||
            (tv == "fra" && "VOSTFR" in tags) || (tv == "eng" && "VOSTEN" in tags)
        return (if (originalAudio) 2 else 0) + (if (subtitled) 1 else 0)
    }

    /**
     * Tracks to start with when none was chosen for the title: the original audio with subtitles in [language]
     * when the original version is wanted, else the audio in [language] (the stream's own choice when absent).
     */
    fun defaultTracks(preference: AudioPreference, language: String, original: String?): DefaultTracks =
        if (wantsOriginal(preference, language, original)) DefaultTracks(original, subtitles = language)
        else DefaultTracks(language, subtitles = null)

    /** 3: audio in [language], 2: MULTI (the local language and the original one), 1: subtitles in it, 0: neither. */
    internal fun languageScore(version: VodVersion, language: String): Int {
        val wanted = iso3(language) ?: return 0
        if (version.audio.any { iso3(it.language) == wanted }) return 3
        val tags = version.languageTags.map { it.uppercase(Locale.ROOT) }
        if (wanted == "fra" && tags.any { it in FRENCH_AUDIO }) return 3
        if ("MULTI" in tags) return 2
        if (version.subtitles.any { iso3(it) == wanted }) return 1
        if ((wanted == "fra" && "VOSTFR" in tags) || (wanted == "eng" && "VOSTEN" in tags)) return 1
        return 0
    }

    /** The picture: theater copies last, then SD < 720p < 1080p < 4K, HDR / Dolby Vision a little above. */
    internal fun qualityScore(version: VodVersion): Int {
        val label = version.qualityLabel?.uppercase(Locale.ROOT).orEmpty()
        if (label.startsWith("CAM")) return -1
        val tier = when {
            "4K" in label -> 40
            "1080P" in label -> 30
            "720P" in label -> 20
            "SD" in label -> 10
            else -> 15
        }
        return tier + (if ("HDR" in label || "DOLBY VISION" in label) 5 else 0)
    }

    private val FRENCH_AUDIO = setOf("VF", "VFF", "VFQ", "VFI", "TRUEFRENCH", "FRENCH")

    // ISO 639-2 bibliographic codes of the providers ("fre", "ger") -> the terminology ones of Locale ("fra", "deu")
    private val BIBLIOGRAPHIC = mapOf(
        "fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "cze" to "ces", "gre" to "ell", "per" to "fas",
        "rum" to "ron", "alb" to "sqi", "arm" to "hye", "baq" to "eus", "geo" to "kat", "ice" to "isl", "mac" to "mkd",
        "may" to "msa", "slo" to "slk", "wel" to "cym", "bur" to "mya", "tib" to "bod", "mao" to "mri"
    )

    /** ISO 639-2 code of a language ("fr", "fre", "fra", "French" are all "fra"); null when unknown. */
    internal fun iso3(code: String?): String? {
        val value = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it.length <= 12 } ?: return null
        BIBLIOGRAPHIC[value]?.let { return it }
        if (value.length == 2 || value.length == 3) {
            return try {
                Locale(value).isO3Language.takeIf { it.isNotEmpty() } ?: value
            } catch (e: java.util.MissingResourceException) {
                value
            }
        }
        // A name ("French", "Français")
        return Locale.getAvailableLocales().firstOrNull { locale ->
            locale.getDisplayLanguage(Locale.ENGLISH).equals(value, ignoreCase = true) ||
                locale.getDisplayLanguage(locale).equals(value, ignoreCase = true)
        }?.isO3Language?.takeIf { it.isNotEmpty() }
    }
}
