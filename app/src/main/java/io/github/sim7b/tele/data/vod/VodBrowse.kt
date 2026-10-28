package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

enum class VodSort { POPULARITY, TITLE, YEAR, RATING, ADDED }

/** Filters of the Movies and Series tabs; null fields do not filter. */
data class VodFilter(
    val country: String? = null,
    val studio: Studio? = null,
    val genre: String? = null,
    val decade: Int? = null,
    val sort: VodSort = VodSort.POPULARITY
) {
    val active: Boolean get() = country != null || studio != null || genre != null || decade != null
}

/** Values of each filter among the titles of a tab, with their number of titles, most frequent first. */
data class VodFilterOptions(
    val countries: List<Pair<String, Int>> = emptyList(),
    val studios: List<Pair<Studio, Int>> = emptyList(),
    val genres: List<Pair<String, Int>> = emptyList(),
    val decades: List<Pair<Int, Int>> = emptyList()
)

/**
 * Browsing of the library: filters (countries and studios come from the TMDB index), sorting and the
 * search by title. Pure functions, run off the main thread on tens of thousands of titles.
 */
object VodBrowse {

    private const val MAX_STUDIOS = 80

    fun filter(titles: List<VodTitle>, kind: VodKind, filter: VodFilter, facts: Map<String, TmdbFacts>, locale: Locale = Locale.getDefault()): List<VodTitle> {
        val kept = titles.filter { title ->
            if (title.kind != kind) return@filter false
            val known = facts[title.key]
            (filter.country == null || known?.countries?.contains(filter.country) == true) &&
                (filter.studio == null || known?.studios?.any { it.id == filter.studio.id } == true) &&
                (filter.genre == null || filter.genre in title.genres) &&
                (filter.decade == null || title.year?.let { it / 10 * 10 } == filter.decade)
        }
        return sort(kept, filter.sort, facts, locale)
    }

    fun sort(titles: List<VodTitle>, sort: VodSort, facts: Map<String, TmdbFacts>, locale: Locale = Locale.getDefault()): List<VodTitle> = when (sort) {
        VodSort.TITLE -> {
            val collator = Collator.getInstance(locale)
            titles.map { collator.getCollationKey(it.title) to it }.sortedBy { it.first }.map { it.second }
        }
        // Without TMDB facts (no key, not indexed yet), the provider rating
        VodSort.POPULARITY -> titles.sortedWith(
            compareByDescending<VodTitle> { facts[it.key]?.popularity ?: 0.0 }.thenByDescending { it.rating ?: 0.0 }
        )
        VodSort.YEAR -> titles.sortedByDescending { it.year ?: 0 }
        VodSort.RATING -> titles.sortedByDescending { facts[it.key]?.vote?.takeIf { v -> v > 0 } ?: it.rating ?: 0.0 }
        VodSort.ADDED -> titles.sortedByDescending { it.addedAt ?: 0L }
    }

    fun options(titles: List<VodTitle>, kind: VodKind, facts: Map<String, TmdbFacts>): VodFilterOptions {
        val ofKind = titles.filter { it.kind == kind }
        fun <T> counted(values: Sequence<T>) = values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        return VodFilterOptions(
            countries = counted(ofKind.asSequence().flatMap { facts[it.key]?.countries.orEmpty() }),
            studios = counted(ofKind.asSequence().flatMap { facts[it.key]?.studios.orEmpty() }).take(MAX_STUDIOS),
            genres = counted(ofKind.asSequence().flatMap { it.genres }),
            decades = counted(ofKind.asSequence().mapNotNull { title -> title.year?.let { it / 10 * 10 } }).sortedByDescending { it.first }
        )
    }

    /** Lowercase words without accents ("Le Comte de Monte-Cristo" -> le comte de monte cristo). */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()

    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Search index: the normalized title of each title (built once per library). */
    fun index(titles: List<VodTitle>): List<Pair<String, VodTitle>> = titles.map { " " + normalize(it.title) to it }

    /**
     * Titles containing every word of [query] (a word may be the start of a title word): titles starting
     * with the query first, then by popularity.
     */
    fun search(index: List<Pair<String, VodTitle>>, query: String, facts: Map<String, TmdbFacts>, limit: Int = 200): List<VodTitle> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val start = " " + words.joinToString(" ")
        return index.asSequence()
            .filter { (text, _) -> words.all { text.contains(" $it") } }
            .map { (text, title) -> Triple(title, text.startsWith(start), facts[title.key]?.popularity ?: title.rating ?: 0.0) }
            .sortedWith(compareByDescending<Triple<VodTitle, Boolean, Double>> { it.second }.thenByDescending { it.third })
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** A person found on TMDB and the titles of the library they took part in. */
    data class PersonCredits(val name: String, val photoUrl: String?, val titles: List<VodTitle>)

    /**
     * People named like [query] (actors, directors) and their movies and series that the library has:
     * TMDB person search, then their combined credits matched with [matcher].
     */
    suspend fun people(query: String, key: String, matcher: VodRankings.Matcher, locale: Locale = Locale.getDefault()): List<PersonCredits> =
        withContext(Dispatchers.IO) {
            val language = locale.toLanguageTag()
            try {
                val results = VodMetadata.tmdbGet("search/person", key, language) { addQueryParameter("query", query) }
                    ?.optJSONArray("results")
                val persons = VodMetadata.objects(results).take(MAX_PEOPLE)
                coroutineScope {
                    persons.map { person ->
                        async {
                            val id = person.optStringOrNull("id") ?: return@async null
                            val credits = VodMetadata.tmdbGet("person/$id/combined_credits", key, language) {} ?: return@async null
                            val titles = (VodMetadata.objects(credits.optJSONArray("cast")) + VodMetadata.objects(credits.optJSONArray("crew")))
                                .mapNotNull { credit ->
                                    val kind = when (credit.optString("media_type")) {
                                        "movie" -> VodKind.MOVIE
                                        "tv" -> VodKind.SERIES
                                        else -> return@mapNotNull null
                                    }
                                    credit.optStringOrNull("id")?.let { matcher.byTmdbId(kind, it) }
                                }
                                .distinctBy { it.key }
                            if (titles.isEmpty()) null
                            else PersonCredits(
                                name = person.optString("name"),
                                photoUrl = person.optStringOrNull("profile_path")?.let { "${VodMetadata.TMDB_IMAGES}w185$it" },
                                titles = titles
                            )
                        }
                    }.awaitAll().filterNotNull()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        }

    private const val MAX_PEOPLE = 3
}
