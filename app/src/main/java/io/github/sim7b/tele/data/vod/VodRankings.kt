package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale

/** Streaming services whose catalog is listed (TMDB watch provider ids), in the country of the TV. */
enum class StreamingPlatform(val tmdbId: Int, val label: String) {
    NETFLIX(8, "Netflix"),
    PRIME_VIDEO(119, "Prime Video"),
    DISNEY_PLUS(337, "Disney+"),
    APPLE_TV_PLUS(350, "Apple TV+"),
    CANAL_PLUS(381, "Canal+"),
    MAX(1899, "Max"),
    PARAMOUNT_PLUS(531, "Paramount+")
}

/**
 * TMDB lists shown on the movies and series home, limited to the titles of the library; [platform] lists are the
 * most popular titles of a streaming service, [pages] of 20 titles are read.
 */
enum class Ranking(val path: String, val kind: VodKind?, val platform: StreamingPlatform? = null, val pages: Int = 15) {
    TRENDING("trending/all/week", null),
    POPULAR_MOVIES("movie/popular", VodKind.MOVIE),
    POPULAR_SERIES("tv/popular", VodKind.SERIES),
    TOP_MOVIES("movie/top_rated", VodKind.MOVIE),
    TOP_SERIES("tv/top_rated", VodKind.SERIES),
    NOW_PLAYING("movie/now_playing", VodKind.MOVIE),
    UPCOMING("movie/upcoming", VodKind.MOVIE),
    ON_THE_AIR("tv/on_the_air", VodKind.SERIES),
    AIRING_TODAY("tv/airing_today", VodKind.SERIES, pages = 5),
    NETFLIX_MOVIES("discover/movie", VodKind.MOVIE, StreamingPlatform.NETFLIX, 10),
    NETFLIX_SERIES("discover/tv", VodKind.SERIES, StreamingPlatform.NETFLIX, 10),
    PRIME_VIDEO_MOVIES("discover/movie", VodKind.MOVIE, StreamingPlatform.PRIME_VIDEO, 10),
    PRIME_VIDEO_SERIES("discover/tv", VodKind.SERIES, StreamingPlatform.PRIME_VIDEO, 10),
    DISNEY_PLUS_MOVIES("discover/movie", VodKind.MOVIE, StreamingPlatform.DISNEY_PLUS, 10),
    DISNEY_PLUS_SERIES("discover/tv", VodKind.SERIES, StreamingPlatform.DISNEY_PLUS, 10),
    APPLE_TV_PLUS_MOVIES("discover/movie", VodKind.MOVIE, StreamingPlatform.APPLE_TV_PLUS, 10),
    APPLE_TV_PLUS_SERIES("discover/tv", VodKind.SERIES, StreamingPlatform.APPLE_TV_PLUS, 10),
    CANAL_PLUS_MOVIES("discover/movie", VodKind.MOVIE, StreamingPlatform.CANAL_PLUS, 10),
    CANAL_PLUS_SERIES("discover/tv", VodKind.SERIES, StreamingPlatform.CANAL_PLUS, 10),
    MAX_MOVIES("discover/movie", VodKind.MOVIE, StreamingPlatform.MAX, 10),
    MAX_SERIES("discover/tv", VodKind.SERIES, StreamingPlatform.MAX, 10),
    PARAMOUNT_PLUS_MOVIES("discover/movie", VodKind.MOVIE, StreamingPlatform.PARAMOUNT_PLUS, 10),
    PARAMOUNT_PLUS_SERIES("discover/tv", VodKind.SERIES, StreamingPlatform.PARAMOUNT_PLUS, 10)
}

/** An entry of a TMDB list. */
data class RankedItem(val kind: VodKind, val tmdbId: String, val title: String, val originalTitle: String?, val year: Int?)

/**
 * TMDB rankings (a few pages each: the library only has part of them) and their matching to the library
 * titles: by TMDB id (given by the providers or found by the index), else by title and year.
 */
object VodRankings {

    suspend fun fetch(key: String, locale: Locale = Locale.getDefault()): Map<Ranking, List<RankedItem>> = withContext(Dispatchers.IO) {
        val language = locale.toLanguageTag()
        val region = locale.country.takeIf { it.length == 2 }
        coroutineScope {
            Ranking.entries.map { ranking ->
                async {
                    ranking to pages(ranking, key, language, region).distinctBy { it.kind to it.tmdbId }
                }
            }.awaitAll().toMap()
        }
    }

    /** The entries of a list, page after page, up to its [Ranking.pages] or its last page. */
    private suspend fun pages(ranking: Ranking, key: String, language: String, region: String?): List<RankedItem> {
        val items = ArrayList<RankedItem>()
        var page = 1
        while (page <= ranking.pages) {
            val json = try {
                VodMetadata.tmdbGet(ranking.path, key, language) {
                    query(ranking, page, region).forEach { (name, value) -> addQueryParameter(name, value) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: break
            items += VodMetadata.objects(json.optJSONArray("results")).mapNotNull { item(it, ranking.kind) }
            if (page >= json.optInt("total_pages", 0)) break
            page++
        }
        return items
    }

    /**
     * Parameters of a page of a list: the country of the TV for the movies in theaters and the catalogs of the
     * streaming services (United States when the TV has no country), their titles by popularity.
     */
    internal fun query(ranking: Ranking, page: Int, region: String?): List<Pair<String, String>> {
        val params = mutableListOf("page" to page.toString())
        if (ranking == Ranking.NOW_PLAYING || ranking == Ranking.UPCOMING) region?.let { params += "region" to it }
        ranking.platform?.let { platform ->
            params += "with_watch_providers" to platform.tmdbId.toString()
            params += "watch_region" to (region ?: DEFAULT_REGION)
            // Included in the subscription, not rented or bought there
            params += "with_watch_monetization_types" to "flatrate"
            params += "sort_by" to "popularity.desc"
        }
        return params
    }

    private const val DEFAULT_REGION = "US"

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
