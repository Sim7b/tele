package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale

/** TMDB lists shown on the movies and series home, limited to the titles of the library. */
enum class Ranking(val path: String, val kind: VodKind?) {
    TRENDING("trending/all/week", null),
    POPULAR_MOVIES("movie/popular", VodKind.MOVIE),
    POPULAR_SERIES("tv/popular", VodKind.SERIES),
    TOP_MOVIES("movie/top_rated", VodKind.MOVIE),
    TOP_SERIES("tv/top_rated", VodKind.SERIES),
    NOW_PLAYING("movie/now_playing", VodKind.MOVIE),
    UPCOMING("movie/upcoming", VodKind.MOVIE)
}

/** An entry of a TMDB list. */
data class RankedItem(val kind: VodKind, val tmdbId: String, val title: String, val originalTitle: String?, val year: Int?)

/**
 * TMDB rankings (a few pages each: the library only has part of them) and their matching to the library
 * titles: by TMDB id (given by the providers or found by the index), else by title and year.
 */
object VodRankings {

    private const val PAGES = 5

    suspend fun fetch(key: String, locale: Locale = Locale.getDefault()): Map<Ranking, List<RankedItem>> = withContext(Dispatchers.IO) {
        val language = locale.toLanguageTag()
        val region = locale.country.takeIf { it.length == 2 }
        coroutineScope {
            Ranking.entries.map { ranking ->
                async {
                    ranking to (1..PAGES).flatMap { page ->
                        try {
                            val json = VodMetadata.tmdbGet(ranking.path, key, language) {
                                addQueryParameter("page", page.toString())
                                // In-theater and upcoming movies of the TV's country
                                if (ranking == Ranking.NOW_PLAYING || ranking == Ranking.UPCOMING) region?.let { addQueryParameter("region", it) }
                            }
                            VodMetadata.objects(json?.optJSONArray("results")).mapNotNull { item(it, ranking.kind) }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            emptyList()
                        }
                    }.distinctBy { it.kind to it.tmdbId }
                }
            }.awaitAll().toMap()
        }
    }

    private fun item(obj: org.json.JSONObject, kind: VodKind?): RankedItem? {
        val itemKind = kind ?: when (obj.optString("media_type")) {
            "movie" -> VodKind.MOVIE
            "tv" -> VodKind.SERIES
            else -> return null
        }
        val id = obj.optStringOrNull("id") ?: return null
        return RankedItem(
            kind = itemKind,
            tmdbId = id,
            title = obj.optStringOrNull("title") ?: obj.optStringOrNull("name") ?: return null,
            originalTitle = obj.optStringOrNull("original_title") ?: obj.optStringOrNull("original_name"),
            year = VodNames.yearOf(obj.optStringOrNull("release_date") ?: obj.optStringOrNull("first_air_date"))
        )
    }

    /** Library titles by TMDB id and by title, to find the entries of a TMDB list. */
    class Matcher(titles: List<VodTitle>, facts: Map<String, TmdbFacts>) {
        private val byId = HashMap<String, VodTitle>()
        private val byName = HashMap<String, VodTitle>()
        private val byNameOnly = HashMap<String, VodTitle?>()

        init {
            titles.forEach { title ->
                val id = title.tmdbId ?: facts[title.key]?.tmdbId
                if (id != null) byId.putIfAbsent(idKey(title.kind, id), title)
                byName.putIfAbsent(nameKey(title.kind, title.title, title.year), title)
                val only = nameKey(title.kind, title.title, null)
                // Without a year, only a title that is unique in the library
                byNameOnly[only] = if (byNameOnly.containsKey(only)) null else title
            }
        }

        fun match(item: RankedItem): VodTitle? =
            byId[idKey(item.kind, item.tmdbId)]
                ?: byName[nameKey(item.kind, item.title, item.year)]
                ?: item.originalTitle?.let { byName[nameKey(item.kind, it, item.year)] }
                ?: byNameOnly[nameKey(item.kind, item.title, null)]

        fun byTmdbId(kind: VodKind, id: String): VodTitle? = byId[idKey(kind, id)]

        private fun idKey(kind: VodKind, id: String) = "${kind.name}:$id"
        private fun nameKey(kind: VodKind, title: String, year: Int?) = "${kind.name}:${VodNames.key(title)}:${year ?: ""}"
    }
}
