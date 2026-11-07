package io.github.sim7b.tele.data.vod

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** A category of movies or series of a source ([id]: Xtream category id, Dispatcharr "name|type", M3U group). */
data class VodCategory(val kind: VodKind, val id: String, val name: String) {
    val key: String get() = "${kind.name}:$id"
}

/** What the name of a category tells: its country and its languages, estimated ("FR | VOSTFR"). */
data class VodCategoryHint(val country: String?, val languages: VodLanguages, val themes: Set<String> = emptySet())

/**
 * Categories of a source to load: [all] (new ones included), or the ones of [keys] only. [known]: the
 * categories the source had when they were chosen (or the suggestions dismissed), so that the ones added
 * since can be suggested. Without a selection, nothing is loaded: the user chooses first.
 */
data class VodSelection(val all: Boolean, val keys: Set<String> = emptySet(), val known: Set<String> = emptySet()) {
    fun includes(category: VodCategory): Boolean = all || category.key in keys

    companion object {
        val ALL = VodSelection(all = true)
        // Nothing loaded from the source
        val NONE = VodSelection(all = false)
    }
}

/**
 * The choice of the categories to load on the first opening: by country and audio / subtitle language
 * (read from their names), then category by category.
 */
object VodCategories {

    // Codes that are not countries in category names (Tuvalu, Sudan, Argentina, Afghanistan: "TV", "SD", "AR"
    // for Arabic, "AF" for Africa)
    private val NOT_COUNTRIES = setOf("TV", "SD", "AR", "AF", "HD", "VO", "ST", "UHD", "FHD", "VF", "VFQ")
    private val TOKENS = Regex("""[^\p{L}\p{N}]+""")
    private val DIACRITICS = Regex("""\p{Mn}+""")

    // Country names and codes (ISO alpha-2 and alpha-3, English and French names in one word) -> alpha-2 code
    private val COUNTRIES: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        Locale.getISOCountries().forEach { code ->
            val locale = Locale("", code)
            map[code] = code
            try {
                map.putIfAbsent(locale.isO3Country, code)
            } catch (e: Exception) {
                // No alpha-3 code
            }
            listOf(Locale.ENGLISH, Locale.FRENCH).forEach { language ->
                val name = normalize(locale.getDisplayCountry(language))
                if (name.isNotEmpty() && name.none { it == ' ' }) map.putIfAbsent(name, code)
            }
        }
        map["UK"] = "GB"
        NOT_COUNTRIES.forEach { map.remove(it) }
        map
    }

    /** Pseudo-country of the categories named after a studio or a platform ("NETFLIX", "DISNEY+", "MARVEL"). */
    const val STUDIOS = "#studios"
    /** Pseudo-country of the other categories whose name tells no country. */
    const val NO_COUNTRY = "#none"

    // Studios, platforms and channels that name categories without a country (whole words of the name)
    private val STUDIO_WORDS = setOf(
        "NETFLIX", "DISNEY", "AMAZON", "PRIME", "APPLE", "HBO", "MAX", "PARAMOUNT", "PEACOCK", "HULU", "STARZ",
        "SHOWTIME", "CANAL", "OCS", "MARVEL", "DC", "PIXAR", "DREAMWORKS", "WARNER", "UNIVERSAL", "SONY", "LIONSGATE",
        "MGM", "A24", "CRUNCHYROLL", "SKY", "BBC", "DISCOVERY", "NATGEO", "MUBI", "SHUDDER", "AMC", "FOX", "VIAPLAY",
        "STAN", "HOTSTAR", "ZEE5", "SHAHID", "OSN", "ANIMATION", "GHIBLI", "LUCASFILM", "MIRAMAX", "BLUMHOUSE", "A&E"
    )

    /** Country and languages read from the name of a category; without a country, a studio / platform or none. */
    fun hint(name: String, estimator: VodLanguageGuess.Estimator): VodCategoryHint =
        VodCategoryHint(country(name) ?: if (isStudio(name)) STUDIOS else NO_COUNTRY, estimator.estimate("", emptyList(), name), themes(name))

    /** Themes of a category: adult, anime, religious. Adult ones are only loaded when that theme is chosen. */
    const val ADULT = "#adult"
    const val ANIME = "#anime"
    const val RELIGIOUS = "#religious"
    val THEMES = listOf(ANIME, RELIGIOUS, ADULT)

    private val ADULT_WORDS = setOf("XXX", "ADULT", "ADULTS", "ADULTE", "ADULTES", "PORN", "PORNO", "EROTIC", "EROTIQUE", "EROTICA", "HENTAI", "XXL18")
    // "18+", "+18" (not inside a number)
    private val ADULT_MARK = Regex("""(?<!\d)18\s*\+|\+\s*18(?!\d)""")
    private val ANIME_WORDS = setOf("ANIME", "ANIMES", "MANGA", "MANGAS", "JAPANIMATION", "CRUNCHYROLL", "SHONEN", "SHOUNEN")
    private val RELIGIOUS_WORDS = setOf(
        "RELIGION", "RELIGIONS", "RELIGIOUS", "RELIGIEUX", "RELIGIEUSE", "RELIGIEUSES", "CHRISTIAN", "CHRETIEN", "CHRETIENS",
        "BIBLE", "BIBLICAL", "BIBLIQUE", "ISLAM", "ISLAMIC", "ISLAMIQUE", "CORAN", "QURAN", "CATHOLIC", "CATHOLIQUE", "GOSPEL",
        "JESUS", "EVANGELIQUE", "EVANGELICAL", "SPIRITUAL", "SPIRITUEL", "FAITH", "TORAH", "JEWISH", "JUIF"
    )

    /** The themes told by a category name (whole words: "XXX", "18+", "ANIME", "MANGA", "RELIGION", "BIBLE"...). */
    fun themes(name: String): Set<String> {
        val words = name.split(TOKENS).map { normalize(it) }.toSet()
        return buildSet {
            if (words.any { it in ADULT_WORDS } || ADULT_MARK.containsMatchIn(name)) add(ADULT)
            if (words.any { it in ANIME_WORDS }) add(ANIME)
            if (words.any { it in RELIGIOUS_WORDS }) add(RELIGIOUS)
        }
    }

    /** The name holds a studio or a platform as a word ("NETFLIX SERIES", "Disney+", "|MARVEL|"). */
    fun isStudio(name: String): Boolean = name.split(TOKENS).any { normalize(it) in STUDIO_WORDS }

    /**
     * Country of a category name: its first word that is a country code in uppercase ("FR | FILMS",
     * "|BE| SERIES") or a country name ("FRANCE"), null when it tells none.
     */
    fun country(name: String): String? {
        name.split(TOKENS).forEach { word ->
            if (word.length in 2..3 && word.all { it in 'A'..'Z' }) COUNTRIES[word]?.let { return it }
            val normalized = normalize(word)
            if (normalized.length > 3) COUNTRIES[normalized]?.let { return it }
        }
        return null
    }

    /**
     * Keys of the [categories] matching the choice: each criterion given (country, audio, subtitles) must
     * match, none given: every category; plus the categories of the [themes] chosen (only them when no other
     * criterion is given). Adult categories only when that theme is chosen.
     */
    fun matching(
        categories: List<VodCategory>,
        hints: Map<String, VodCategoryHint>,
        countries: Set<String>,
        audio: Set<String>,
        subtitles: Set<String>,
        themes: Set<String> = emptySet()
    ): Set<String> = categories.filter { category ->
        val hint = hints[category.key]
        val categoryThemes = hint?.themes.orEmpty()
        val byCriteria = (countries.isEmpty() || hint?.country in countries) &&
            (audio.isEmpty() || hint?.languages?.audio.orEmpty().any { it in audio }) &&
            (subtitles.isEmpty() || hint?.languages?.subtitles.orEmpty().any { it in subtitles })
        val byTheme = categoryThemes.any { it in themes }
        val otherCriteria = countries.isNotEmpty() || audio.isNotEmpty() || subtitles.isNotEmpty()
        (ADULT !in categoryThemes || ADULT in themes) && when {
            themes.isEmpty() -> byCriteria
            !otherCriteria -> byTheme
            else -> byCriteria || byTheme
        }
    }.mapTo(LinkedHashSet()) { it.key }

    /**
     * Categories of [categories] added since [selection] was made that look like the chosen ones: same
     * country or same audio language (any new one when the chosen ones tell neither). None when every
     * category is loaded, or when the categories known then were not recorded.
     */
    fun suggestions(categories: List<VodCategory>, selection: VodSelection): List<VodCategory> {
        if (selection.all || selection.known.isEmpty() || selection.keys.isEmpty()) return emptyList()
        val added = categories.filter { it.key !in selection.known && it.key !in selection.keys }
        if (added.isEmpty()) return emptyList()
        val estimator = VodLanguageGuess.Estimator()
        val chosen = categories.filter { it.key in selection.keys }.map { hint(it.name, estimator) }
        val countries = chosen.mapNotNullTo(HashSet()) { it.country }
        val audio = chosen.flatMapTo(HashSet()) { it.languages.audio }
        // Adult ones only once some were chosen
        val adultChosen = chosen.any { ADULT in it.themes }
        val allowed = added.filter { adultChosen || ADULT !in themes(it.name) }
        if (countries.isEmpty() && audio.isEmpty()) return allowed
        return allowed.filter { category ->
            val hint = hint(category.name, estimator)
            hint.country in countries || hint.languages.audio.any { it in audio }
        }
    }

    fun toJson(selections: Map<String, VodSelection>): String = JSONObject().apply {
        selections.forEach { (sourceId, selection) ->
            put(sourceId, JSONObject().apply {
                put("all", selection.all)
                put("keys", JSONArray(selection.keys.toList()))
                if (selection.known.isNotEmpty()) put("known", JSONArray(selection.known.toList()))
            })
        }
    }.toString()

    /** Selections saved by [toJson]; broken ones are skipped. */
    fun fromJson(json: String?): Map<String, VodSelection> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            root.keys().asSequence().mapNotNull { sourceId ->
                val item = root.optJSONObject(sourceId) ?: return@mapNotNull null
                fun keys(name: String) = item.optJSONArray(name)?.let { array ->
                    (0 until array.length()).mapNotNullTo(LinkedHashSet()) { array.optString(it).takeIf { key -> key.isNotEmpty() } }
                }.orEmpty()
                sourceId to VodSelection(item.optBoolean("all", false), keys("keys"), keys("known"))
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").uppercase(Locale.ROOT).trim()
}
