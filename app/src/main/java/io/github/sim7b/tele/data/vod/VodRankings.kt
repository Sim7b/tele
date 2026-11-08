package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Streaming services whose catalog is listed (TMDB watch provider ids), in the country of the TV. Their own
 * productions: the series of their TMDB [networkIds] (the first one gives the logo, a wordmark on a transparent
 * background) and the movies of their studios, found on TMDB by their [studios] names.
 */
enum class StreamingPlatform(val tmdbId: Int, val label: String, val networkIds: List<Int>, val studios: List<String>) {
    NETFLIX(8, "Netflix", listOf(213), listOf("Netflix")),
    PRIME_VIDEO(119, "Prime Video", listOf(1024), listOf("Amazon MGM Studios", "Amazon Studios")),
    DISNEY_PLUS(337, "Disney+", listOf(2739), listOf("Disney+")),
    APPLE_TV_PLUS(350, "Apple TV+", listOf(2552), listOf("Apple Original Films", "Apple Studios")),
    CANAL_PLUS(381, "Canal+", listOf(285), listOf("Canal+", "Studiocanal")),
    MAX(1899, "Max", listOf(3186, 49), listOf("HBO Max", "HBO Films")),
    PARAMOUNT_PLUS(531, "Paramount+", listOf(4330), listOf("Paramount+"));

    val networkId: Int get() = networkIds.first()
}

/** A streaming service opened on its screen: its titles by popularity, and its own productions. */
data class PlatformCatalog(val popular: List<RankedItem>, val originals: List<RankedItem> = emptyList())

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
    PARAMOUNT_PLUS_SERIES("discover/tv", VodKind.SERIES, StreamingPlatform.PARAMOUNT_PLUS, 10);

    companion object {
        /** The list of the titles of [platform] for [kind]. */
        fun of(platform: StreamingPlatform, kind: VodKind): Ranking? = entries.firstOrNull { it.platform == platform && it.kind == kind }
    }
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

    /**
     * The catalog of [platform] for [kind] opened on its own screen: more of it than its row on the home
     * ([PLATFORM_CATALOG_PAGES] pages), by popularity.
     */
    suspend fun platformCatalog(
        platform: StreamingPlatform,
        kind: VodKind,
        key: String,
        locale: Locale = Locale.getDefault()
    ): PlatformCatalog = withContext(Dispatchers.IO) {
        val region = locale.country.takeIf { it.length == 2 }
        val language = locale.toLanguageTag()
        val path = if (kind == VodKind.SERIES) "discover/tv" else "discover/movie"
        coroutineScope {
            val originals = async { originals(platform, kind, key, language) }
            val popular = pages(path, kind, PLATFORM_CATALOG_PAGES, key, language, parallel = CATALOG_PARALLEL_PAGES) {
                platformQuery(platform, it, region)
            }.distinctBy { it.kind to it.tmdbId }
            PlatformCatalog(popular, originals.await())
        }
    }

    /**
     * The productions of [platform], by popularity, wherever they are watched: its series (made by its networks),
     * its movies (made by its studios). Empty when TMDB knows none.
     */
    private suspend fun originals(platform: StreamingPlatform, kind: VodKind, key: String, language: String): List<RankedItem> {
        val filter = if (kind == VodKind.SERIES) {
            "with_networks" to platform.networkIds.joinToString("|")
        } else {
            val ids = studioIds(platform, key, language).ifEmpty { return emptyList() }
            "with_companies" to ids.joinToString("|")
        }
        val path = if (kind == VodKind.SERIES) "discover/tv" else "discover/movie"
        return pages(path, kind, ORIGINALS_PAGES, key, language, parallel = CATALOG_PARALLEL_PAGES) { page ->
            listOf("page" to page.toString(), filter, "sort_by" to "popularity.desc")
        }.distinctBy { it.kind to it.tmdbId }
    }

    // TMDB ids of the studios of each platform, looked up once
    private val studioIdsCache = java.util.concurrent.ConcurrentHashMap<StreamingPlatform, List<Int>>()

    private suspend fun studioIds(platform: StreamingPlatform, key: String, language: String): List<Int> {
        studioIdsCache[platform]?.let { return it }
        val ids = platform.studios.flatMap { name ->
            val json = try {
                VodMetadata.tmdbGet("search/company", key, language) { addQueryParameter("query", name) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            json?.let { companiesNamed(it, name) }.orEmpty()
        }.distinct()
        if (ids.isNotEmpty()) studioIdsCache[platform] = ids
        return ids
    }

    /** The companies of [json] (a TMDB company search) named exactly [name] (case aside): never a namesake. */
    internal fun companiesNamed(json: org.json.JSONObject, name: String): List<Int> =
        VodMetadata.objects(json.optJSONArray("results"))
            .filter { it.optString("name").equals(name, ignoreCase = true) }
            .mapNotNull { company -> company.optInt("id", -1).takeIf { it > 0 } }
            .take(MAX_STUDIOS_PER_NAME)

    private const val ORIGINALS_PAGES = 10
    private const val MAX_STUDIOS_PER_NAME = 3

    /**
     * Logos of the streaming services (the wordmarks of their TMDB network, transparent), by platform; the ones
     * TMDB has none for are missing.
     */
    suspend fun platformLogos(key: String, locale: Locale = Locale.getDefault()): Map<StreamingPlatform, String> = withContext(Dispatchers.IO) {
        coroutineScope {
            StreamingPlatform.entries.map { platform ->
                async {
                    val json = try {
                        VodMetadata.tmdbGet("network/${platform.networkId}", key, locale.toLanguageTag()) {}
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    json?.let { logoOf(it) }?.let { platform to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /** The logo of [json] (a TMDB network): a PNG (Android draws no SVG), null when it has none. */
    internal fun logoOf(json: org.json.JSONObject): String? =
        json.optStringOrNull("logo_path")?.takeIf { LOGO_PATH.matches(it) }?.let { "${VodMetadata.TMDB_IMAGES}w300$it" }

    // A TMDB image file ("/abc123.png"), never a URL or a path out of the images
    private val LOGO_PATH = Regex("""/[A-Za-z0-9_-]{1,64}\.png""")
    private const val PLATFORM_CATALOG_PAGES = 40
    private const val CATALOG_PARALLEL_PAGES = 8

    /** The entries of a list, page after page, up to its [Ranking.pages] or its last page. */
    private suspend fun pages(ranking: Ranking, key: String, language: String, region: String?): List<RankedItem> =
        pages(ranking.path, ranking.kind, ranking.pages, key, language) { query(ranking, it, region) }

    internal suspend fun pages(
        path: String,
        kind: VodKind?,
        maxPages: Int,
        key: String,
        language: String,
        // Pages read at the same time: the lists of the home are already all read at once
        parallel: Int = 1,
        params: (page: Int) -> List<Pair<String, String>>
    ): List<RankedItem> {
        suspend fun read(page: Int) = try {
            VodMetadata.tmdbGet(path, key, language) {
                params(page).forEach { (name, value) -> addQueryParameter(name, value) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        // The first page tells how many there are; the others are read [parallel] at a time, in order
        val first = read(1) ?: return emptyList()
        val items = ArrayList<RankedItem>()
        items += VodMetadata.objects(first.optJSONArray("results")).mapNotNull { item(it, kind) }
        val last = lastPage(first.optInt("total_pages", 0), maxPages)
        for (batch in (2..last).chunked(parallel.coerceAtLeast(1))) {
            val pages = coroutineScope { batch.map { async { read(it) } }.awaitAll() }
            for (json in pages) {
                // A page missing (error): the next ones would only be out of order
                json ?: return items
                items += VodMetadata.objects(json.optJSONArray("results")).mapNotNull { item(it, kind) }
            }
        }
        return items
    }

    /** Last page to read of a list of [totalPages] (TMDB serves 500 at most), up to [maxPages]. */
    internal fun lastPage(totalPages: Int, maxPages: Int): Int = totalPages.coerceAtMost(maxPages).coerceAtMost(TMDB_MAX_PAGES)

    private const val TMDB_MAX_PAGES = 500

    /**
     * Parameters of a page of a list: the country of the TV for the movies in theaters and the catalogs of the
     * streaming services (United States when the TV has no country), their titles by popularity.
     */
    internal fun query(ranking: Ranking, page: Int, region: String?): List<Pair<String, String>> {
        val platform = ranking.platform ?: return buildList {
            add("page" to page.toString())
            if (ranking == Ranking.NOW_PLAYING || ranking == Ranking.UPCOMING) region?.let { add("region" to it) }
        }
        return platformQuery(platform, page, region)
    }

    /** Parameters of a page of the catalog of [platform], its titles by popularity. */
    internal fun platformQuery(platform: StreamingPlatform, page: Int, region: String?): List<Pair<String, String>> = listOf(
        "page" to page.toString(),
        "with_watch_providers" to platform.tmdbId.toString(),
        "watch_region" to (region ?: DEFAULT_REGION),
        // Included in the subscription, not rented or bought there
        "with_watch_monetization_types" to "flatrate",
        "sort_by" to "popularity.desc"
    )

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
