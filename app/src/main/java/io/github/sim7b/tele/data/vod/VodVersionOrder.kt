package io.github.sim7b.tele.data.vod

import java.util.Locale

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

    /** [details] with its files, and the files of each episode, in [order] for a TV in [language]. */
    fun apply(details: VodDetails, order: VersionOrder, language: String): VodDetails {
        if (order == VersionOrder.AS_LISTED) return details
        return details.copy(
            versions = sort(details.versions, order, language),
            seasons = details.seasons.map { season ->
                season.copy(episodes = season.episodes.map { it.copy(versions = sort(it.versions, order, language)) })
            }
        )
    }

    fun sort(versions: List<VodVersion>, order: VersionOrder, language: String): List<VodVersion> {
        if (versions.size < 2 || order == VersionOrder.AS_LISTED) return versions
        val byLanguage = compareByDescending<VodVersion> { languageScore(it, language) }
        val byQuality = compareByDescending<VodVersion> { qualityScore(it) }
        // Stable: files equal on both stay in the order of the providers
        return versions.sortedWith(if (order == VersionOrder.LANGUAGE_FIRST) byLanguage.then(byQuality) else byQuality.then(byLanguage))
    }

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
