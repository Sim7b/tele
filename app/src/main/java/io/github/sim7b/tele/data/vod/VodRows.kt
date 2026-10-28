package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.storage.VodProgress

/**
 * A row of the movies and series home: its kind gives the heading, [name] names a genre or a
 * collection row; [ranked] rows show their position (TMDB trends).
 */
data class VodRow(
    val kind: Kind,
    val titles: List<VodTitle>,
    val name: String? = null,
    val ranked: Boolean = false,
    // Collection rows: the collection the titles can be removed from
    val collectionId: String? = null
) {
    enum class Kind {
        CONTINUE, TRENDING, MY_LIST, COLLECTION, RECENT_MOVIES, RECENT_SERIES, POPULAR_MOVIES, POPULAR_SERIES,
        NOW_PLAYING, UPCOMING, TOP_MOVIES, TOP_SERIES, GENRE
    }
}

/** Rows of the movies and series home, from the library, the TMDB rankings and the user's choices. */
object VodRows {

    private const val ROW_SIZE = 40
    private const val TRENDING_SIZE = 20
    private const val GENRE_ROWS = 6

    fun build(
        titles: List<VodTitle>,
        progress: Map<String, VodProgress>,
        myList: List<String>,
        collections: List<VodCollection> = emptyList(),
        rankings: Map<Ranking, List<RankedItem>> = emptyMap(),
        matcher: VodRankings.Matcher? = null,
        hidden: Set<VodRow.Kind> = emptySet()
    ): List<VodRow> {
        if (titles.isEmpty()) return emptyList()
        val byKey = titles.associateBy { it.key }
        val movies = titles.filter { it.kind == VodKind.MOVIE }
        val series = titles.filter { it.kind == VodKind.SERIES }
        fun ranked(ranking: Ranking, size: Int = ROW_SIZE): List<VodTitle> =
            if (matcher == null) emptyList()
            else rankings[ranking].orEmpty().mapNotNull { matcher.match(it) }.distinctBy { it.key }.take(size)
        val rows = ArrayList<VodRow>()

        // Started and not finished, the latest first; a series once, from its latest episode
        val started = progress.values
            .filter { !it.finished && it.positionMs > 0 }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { byKey[it.titleKey] }
            .distinctBy { it.key }
        rows += VodRow(VodRow.Kind.CONTINUE, started.take(ROW_SIZE))
        rows += VodRow(VodRow.Kind.TRENDING, ranked(Ranking.TRENDING, TRENDING_SIZE), ranked = true)
        rows += VodRow(VodRow.Kind.MY_LIST, myList.mapNotNull { byKey[it] }.take(ROW_SIZE))
        collections.forEach { collection ->
            rows += VodRow(VodRow.Kind.COLLECTION, collection.titleKeys.mapNotNull { byKey[it] }.take(ROW_SIZE), collection.name, collectionId = collection.id)
        }
        rows += VodRow(VodRow.Kind.RECENT_MOVIES, recent(movies))
        rows += VodRow(VodRow.Kind.RECENT_SERIES, recent(series))
        rows += VodRow(VodRow.Kind.POPULAR_MOVIES, ranked(Ranking.POPULAR_MOVIES))
        rows += VodRow(VodRow.Kind.POPULAR_SERIES, ranked(Ranking.POPULAR_SERIES))
        rows += VodRow(VodRow.Kind.NOW_PLAYING, ranked(Ranking.NOW_PLAYING))
        rows += VodRow(VodRow.Kind.UPCOMING, ranked(Ranking.UPCOMING))
        // TMDB best rated when available, else the providers' ratings
        rows += VodRow(VodRow.Kind.TOP_MOVIES, ranked(Ranking.TOP_MOVIES).ifEmpty { best(movies.filter { it.rating != null }) })
        rows += VodRow(VodRow.Kind.TOP_SERIES, ranked(Ranking.TOP_SERIES).ifEmpty { best(series.filter { it.rating != null }) })
        VodLibrary.topGenres(titles, GENRE_ROWS).forEach { genre ->
            rows += VodRow(VodRow.Kind.GENRE, best(titles.filter { genre in it.genres }), genre)
        }
        return rows.filter { it.titles.isNotEmpty() && it.kind !in hidden }
    }

    /** Latest additions; without dates (M3U), the order of the playlist. */
    private fun recent(titles: List<VodTitle>): List<VodTitle> =
        titles.sortedByDescending { it.addedAt ?: 0L }.take(ROW_SIZE)

    private fun best(titles: List<VodTitle>): List<VodTitle> =
        titles.sortedWith(compareByDescending<VodTitle> { it.rating ?: 0.0 }.thenByDescending { it.year ?: 0 }).take(ROW_SIZE)

    /** Title shown at the top of the home: the latest movie or series with a wide image. */
    fun featured(titles: List<VodTitle>): VodTitle? =
        titles.filter { it.backdropUrl != null }.maxByOrNull { it.addedAt ?: 0L }
            ?: titles.maxByOrNull { it.addedAt ?: 0L }

    /** Key of the playback position of a movie, or of an episode of a series. */
    fun progressKey(titleKey: String, episodeKey: String?): String =
        if (episodeKey == null) titleKey else "$titleKey#$episodeKey"
}
