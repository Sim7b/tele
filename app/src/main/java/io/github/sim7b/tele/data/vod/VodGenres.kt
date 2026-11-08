package io.github.sim7b.tele.data.vod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/**
 * Main genres opened on their own screen, with their TMDB genre ids for the movies and the series (null: TMDB has
 * no such genre for that kind; the series join action and adventure, science fiction and fantasy).
 */
enum class VodGenre(val movieId: Int?, val seriesId: Int?) {
    ACTION(28, 10759),
    ADVENTURE(12, null),
    ANIMATION(16, 16),
    COMEDY(35, 35),
    CRIME(80, 80),
    DOCUMENTARY(99, 99),
    DRAMA(18, 18),
    FAMILY(10751, 10751),
    FANTASY(14, null),
    HORROR(27, null),
    MYSTERY(9648, 9648),
    ROMANCE(10749, null),
    SCIENCE_FICTION(878, 10765),
    THRILLER(53, null),
    WAR(10752, 10768),
    KIDS(null, 10762);

    fun tmdbId(kind: VodKind): Int? = if (kind == VodKind.SERIES) seriesId else movieId

    companion object {
        /** The genres that exist for [kind]. */
        fun of(kind: VodKind): List<VodGenre> = entries.filter { it.tmdbId(kind) != null }
    }
}

/** A genre opened on its screen: its titles by popularity, the best of the last years and of all time. */
data class GenreCatalog(
    val popular: List<RankedItem> = emptyList(),
    val recentBest: List<RankedItem> = emptyList(),
    val allTimeBest: List<RankedItem> = emptyList()
) {
    val isEmpty: Boolean get() = popular.isEmpty() && recentBest.isEmpty() && allTimeBest.isEmpty()
}

/** The lists of a genre on TMDB. */
enum class GenreList(val pages: Int) { POPULAR(20), RECENT_BEST(10), ALL_TIME_BEST(20) }

object VodGenres {

    /** Years counted as recent for the best of the last years. */
    private const val RECENT_YEARS = 3L
    // Votes needed to be ranked: enough to keep out the titles rated by a handful of people
    private const val RECENT_MOVIE_VOTES = 150
    private const val RECENT_SERIES_VOTES = 50
    private const val ALL_TIME_MOVIE_VOTES = 1500
    private const val ALL_TIME_SERIES_VOTES = 300
    private const val PARALLEL_PAGES = 6

    suspend fun catalog(
        genre: VodGenre,
        kind: VodKind,
        key: String,
        locale: Locale = Locale.getDefault(),
        today: LocalDate = LocalDate.now()
    ): GenreCatalog = withContext(Dispatchers.IO) {
        val id = genre.tmdbId(kind) ?: return@withContext GenreCatalog()
        val path = if (kind == VodKind.SERIES) "discover/tv" else "discover/movie"
        val language = locale.toLanguageTag()
        coroutineScope {
            val lists = GenreList.entries.associateWith { list ->
                async {
                    VodRankings.pages(path, kind, list.pages, key, language, parallel = PARALLEL_PAGES) { page ->
                        query(id, kind, list, page, today)
                    }.distinctBy { it.kind to it.tmdbId }
                }
            }
            GenreCatalog(
                popular = lists.getValue(GenreList.POPULAR).await(),
                recentBest = lists.getValue(GenreList.RECENT_BEST).await(),
                allTimeBest = lists.getValue(GenreList.ALL_TIME_BEST).await()
            )
        }
    }

    /** Parameters of a page of [list] for the genre [genreId]: the best are rated by enough people. */
    internal fun query(genreId: Int, kind: VodKind, list: GenreList, page: Int, today: LocalDate): List<Pair<String, String>> {
        val series = kind == VodKind.SERIES
        val params = mutableListOf("page" to page.toString(), "with_genres" to genreId.toString())
        when (list) {
            GenreList.POPULAR -> params += "sort_by" to "popularity.desc"
            GenreList.RECENT_BEST -> {
                params += "sort_by" to "vote_average.desc"
                params += "vote_count.gte" to (if (series) RECENT_SERIES_VOTES else RECENT_MOVIE_VOTES).toString()
                params += (if (series) "first_air_date.gte" else "primary_release_date.gte") to today.minusYears(RECENT_YEARS).toString()
            }
            GenreList.ALL_TIME_BEST -> {
                params += "sort_by" to "vote_average.desc"
                params += "vote_count.gte" to (if (series) ALL_TIME_SERIES_VOTES else ALL_TIME_MOVIE_VOTES).toString()
            }
        }
        return params
    }
}
