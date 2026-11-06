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
data class VodCategoryHint(val country: String?, val languages: VodLanguages)

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

    /** Country and languages read from the name of a category. */
    fun hint(name: String, estimator: VodLanguageGuess.Estimator): VodCategoryHint =
        VodCategoryHint(country(name), estimator.estimate("", emptyList(), name))

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
     * match; none given: every category.
     */
    fun matching(
        categories: List<VodCategory>,
        hints: Map<String, VodCategoryHint>,
        countries: Set<String>,
        audio: Set<String>,
        subtitles: Set<String>
    ): Set<String> = categories.filter { category ->
        val hint = hints[category.key]
        (countries.isEmpty() || hint?.country in countries) &&
            (audio.isEmpty() || hint?.languages?.audio.orEmpty().any { it in audio }) &&
            (subtitles.isEmpty() || hint?.languages?.subtitles.orEmpty().any { it in subtitles })
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
        if (countries.isEmpty() && audio.isEmpty()) return added
        return added.filter { category ->
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
