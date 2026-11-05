package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.optStringOrNull
import io.github.sim7b.tele.data.remote.DispatcharrClient
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.time.OffsetDateTime

/**
 * Movies and series of a Dispatcharr server (/api/vod/, filled by its Xtream accounts), played through
 * its proxy (/proxy/vod/movie|episode/{uuid}) with the API key, like the live channels.
 */
object DispatcharrVod {

    /**
     * Movies and series together (hundreds of pages): [onProgress] receives what is already there every
     * [PROGRESS_INTERVAL_MS], so a first load shows the library while it fills.
     */
    suspend fun loadCatalog(
        config: SourceConfig,
        client: DispatcharrClient,
        maxItems: Int = MAX_ITEMS,
        onProgress: suspend (List<VodEntry>) -> Unit = {}
    ): List<VodEntry> = coroutineScope {
        val movies = ArrayList<VodEntry>()
        val series = ArrayList<VodEntry>()
        fun snapshot() = synchronized(movies) { ArrayList(movies) } + synchronized(series) { ArrayList(series) }
        val progress = launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                onProgress(snapshot())
            }
        }
        try {
            listOf(
                async { client.forEachVodItem("movies", maxItems) { item -> entry(config, client, VodKind.MOVIE, item)?.let { synchronized(movies) { movies.add(it) } } } },
                async { client.forEachVodItem("series", maxItems) { item -> entry(config, client, VodKind.SERIES, item)?.let { synchronized(series) { series.add(it) } } } }
            ).awaitAll()
        } finally {
            progress.cancel()
        }
        snapshot()
    }

    /**
     * [entries] with their playlist group, which the lists do not give: looked up for the groups telling
     * a language (a second pass over the server, run after the catalog is shown). Null when unavailable.
     */
    suspend fun withGroups(client: DispatcharrClient, entries: List<VodEntry>): List<VodEntry>? {
        val groups = try {
            groups(client)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Categories unavailable: ${e.javaClass.simpleName}")
            return null
        }
        return entries.map { entry -> entry.withGroup(groups[groupKey(entry)]) }
    }

    /** Groups found by a previous [withGroups], kept on [entries] until the next one ends. */
    fun keepGroups(entries: List<VodEntry>, previous: List<VodEntry>?): List<VodEntry> {
        if (previous.isNullOrEmpty()) return entries
        val groups = previous.mapNotNull { entry -> entry.category?.let { groupKey(entry) to it } }.toMap()
        if (groups.isEmpty()) return entries
        return entries.map { entry -> entry.withGroup(groups[groupKey(entry)]) }
    }

    private fun groupKey(entry: VodEntry) = (if (entry.kind == VodKind.MOVIE) "movie:" else "series:") + entry.providerId

    private fun VodEntry.withGroup(group: String?) = if (group == null || group == category) this else copy(category = group)

    /**
     * Group of the movies and series whose group tells a language ("FR | VOSTFR", "ENGLISH MOVIES"):
     * the categories are listed, then the titles of each of those, by "type:id".
     */
    /** Categories of movies and series of the server ("type", "name"), in the order of the server. */
    private suspend fun listCategories(client: DispatcharrClient): Set<Pair<String, String>> {
        val categories = LinkedHashSet<Pair<String, String>>()
        var page = 1
        while (page <= MAX_CATEGORY_PAGES) {
            var count = 0
            var added = 0
            val fetched = client.forEachVodObject("categories/?page_size=$CATEGORY_PAGE_SIZE&page=$page") { item ->
                count++
                val name = item.optStringOrNull("name") ?: return@forEachVodObject
                val type = item.optStringOrNull("category_type") ?: return@forEachVodObject
                if (categories.add(type to name)) added++
            }
            // A list served whole comes back the same on the next page: nothing new, done
            if (!fetched || added == 0 || count < CATEGORY_PAGE_SIZE) break
            page++
        }
        return categories
    }

    /** Categories of movies and series of the server, to choose what to load. */
    suspend fun categoriesOf(client: DispatcharrClient): List<VodCategory> =
        listCategories(client).mapNotNull { (type, name) ->
            val kind = when (type) {
                "movie" -> VodKind.MOVIE
                "series" -> VodKind.SERIES
                else -> return@mapNotNull null
            }
            VodCategory(kind, "$name|$type", name)
        }

    /**
     * Movies and series of the chosen categories only, a few categories at a time, each with its category
     * as group: no second pass for the groups. [onProgress] receives what is already there after each one.
     */
    suspend fun loadCategories(
        config: SourceConfig,
        client: DispatcharrClient,
        selection: VodSelection,
        maxItems: Int = MAX_ITEMS,
        onProgress: suspend (List<VodEntry>) -> Unit = {}
    ): List<VodEntry> = coroutineScope {
        val chosen = categoriesOf(client).filter { selection.includes(it) }
        val entries = ArrayList<VodEntry>()
        val seen = HashSet<String>()
        val progressLock = Mutex()
        // Last list given to [onProgress]: returned as is when complete, so that it is not built again
        var lastSnapshot: List<VodEntry>? = null
        // Largest page served so far: the page size the server really uses
        val largestPage = AtomicInteger(0)
        val pages = AtomicInteger(0)
        val startedAt = SystemClock.elapsedRealtime()
        val gate = Semaphore(LOAD_PARALLEL)
        chosen.map { category ->
            async {
                gate.withPermit {
                    val list = if (category.kind == VodKind.MOVIE) "movies" else "series"
                    val filter = URLEncoder.encode(category.id, "UTF-8")
                    var page = 1
                    while (page <= MAX_ITEMS / LOAD_PAGE_SIZE) {
                        var count = 0
                        val fetched = client.forEachVodObject("$list/?category=$filter&page_size=$LOAD_PAGE_SIZE&page=$page") { item ->
                            count++
                            val entry = entry(config, client, category.kind, item)?.copy(category = category.name) ?: return@forEachVodObject
                            synchronized(entries) {
                                // A title listed in several chosen categories: once, with the first one
                                if (entries.size < maxItems && seen.add(groupKey(entry))) entries.add(entry)
                            }
                        }
                        // The server may serve smaller pages than asked: only a page fuller than none of the
                        // pages seen yet can be followed by another one (a last page is the only partial one)
                        pages.incrementAndGet()
                        val fullest = largestPage.updateAndGet { maxOf(it, count) }
                        if (!fetched || count == 0 || count < fullest) break
                        page++
                    }
                }
                // One at a time: the library is built again from it
                progressLock.withLock {
                    val snapshot = synchronized(entries) { ArrayList(entries) }
                    lastSnapshot = snapshot
                    onProgress(snapshot)
                }
            }
        }.awaitAll()
        Log.i(TAG, "${chosen.size} categories in ${pages.get()} pages of up to ${largestPage.get()} in ${SystemClock.elapsedRealtime() - startedAt} ms")
        lastSnapshot?.takeIf { it.size == entries.size } ?: synchronized(entries) { ArrayList(entries) }
    }

    private suspend fun groups(client: DispatcharrClient): Map<String, String> = coroutineScope {
        val startedAt = SystemClock.elapsedRealtime()
        val categories = listCategories(client)
        val telling = categories.filter { (type, name) ->
            (type == "movie" || type == "series") && !VodLanguageGuess.estimate("", emptyList(), name).isEmpty
        }
        val groups = ConcurrentHashMap<String, String>()
        val gate = Semaphore(CATEGORY_PARALLEL)
        telling.map { (type, name) ->
            async {
                gate.withPermit {
                    // One page at a time: most categories hold a few pages
                    val list = if (type == "movie") "movies" else "series"
                    val category = URLEncoder.encode("$name|$type", "UTF-8")
                    var page = 1
                    while (page <= MAX_ITEMS / GROUP_PAGE_SIZE) {
                        var count = 0
                        val fetched = client.forEachVodObject("$list/?category=$category&page_size=$GROUP_PAGE_SIZE&page=$page") { item ->
                            count++
                            item.optStringOrNull("id")?.let { groups.putIfAbsent("$type:$it", name) }
                        }
                        if (!fetched || count < GROUP_PAGE_SIZE) break
                        page++
                    }
                }
            }
        }.awaitAll()
        Log.i(TAG, "Groups of ${groups.size} titles from ${telling.size} of ${categories.size} categories in ${SystemClock.elapsedRealtime() - startedAt} ms")
        groups
    }

    private fun entry(config: SourceConfig, client: DispatcharrClient, kind: VodKind, item: JSONObject): VodEntry? {
        val id = item.optStringOrNull("id") ?: return null
        val name = item.optStringOrNull("name") ?: return null
        val parsed = VodNames.parse(name)
        val list = if (kind == VodKind.MOVIE) "movies" else "series"
        return VodEntry(
            sourceId = config.id,
            kind = kind,
            providerId = id,
            name = name,
            title = parsed.title,
            year = item.optInt("year", 0).takeIf { it > 0 } ?: parsed.year,
            // Poster: the cached VOD logo; wide image: the image endpoint of the title (served without the key)
            posterUrl = logoId(item)?.let { client.vodImageUrl("vodlogos/$it/cache/") },
            backdropUrl = client.vodImageUrl("$list/$id/image/?kind=backdrop"),
            plot = VodLibrary.shortPlot(item.optStringOrNull("description")),
            genres = VodLibrary.splitGenres(item.optStringOrNull("genre")),
            rating = VodLibrary.rating(item.optStringOrNull("rating")),
            tmdbId = item.optStringOrNull("tmdb_id")?.takeIf { it.isNotBlank() && it != "0" },
            imdbId = item.optStringOrNull("imdb_id")?.takeIf { it.isNotBlank() },
            addedAt = item.optStringOrNull("created_at")?.let { date ->
                try {
                    OffsetDateTime.parse(date).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    null
                }
            },
            qualityTags = parsed.qualityTags,
            languageTags = parsed.languageTags,
            uuid = item.optStringOrNull("uuid")
        )
    }

    /** Id of the VOD logo of a list item: an id, or an object with its id. */
    private fun logoId(item: JSONObject): String? = when (val logo = item.opt("logo")) {
        is JSONObject -> logo.optStringOrNull("id")
        is Number -> logo.toString().takeIf { logo.toLong() > 0 }
        is String -> logo.takeIf { it.isNotBlank() && it != "null" }
        else -> null
    }

    suspend fun movieDetails(config: SourceConfig, client: DispatcharrClient, entry: VodEntry): VodDetailsPart {
        val info = client.getVodObject("movies/${entry.providerId}/provider-info/")
        val media = ProviderMedia.from(info)
        val uuid = info?.optStringOrNull("uuid") ?: entry.uuid
        val duration = info?.optInt("duration_secs", 0)?.takeIf { it > 0 }
        return VodDetailsPart(
            plot = info?.optStringOrNull("plot") ?: info?.optStringOrNull("description"),
            backdropUrl = ProviderMedia.firstImage(info?.opt("backdrop_path")),
            posterUrl = info?.optStringOrNull("cover_big")?.takeIf { it.startsWith("http") },
            genres = VodLibrary.splitGenres(info?.optStringOrNull("genre")),
            director = info?.optStringOrNull("director"),
            cast = XtreamVod.splitPeople(info?.optStringOrNull("actors")),
            tmdbId = info?.optStringOrNull("tmdb_id")?.takeIf { it.isNotBlank() && it != "0" },
            durationSecs = duration,
            versions = listOfNotNull(uuid?.let { version(config, entry, client.vodStreamUrl("movie", it), entry.name, media, duration) })
        )
    }

    suspend fun seriesDetails(config: SourceConfig, client: DispatcharrClient, entry: VodEntry): VodDetailsPart {
        val info = client.getVodObject("series/${entry.providerId}/provider-info/")
        val episodes = ArrayList<VodEpisode>()
        info?.optJSONObject("episodes")?.let { bySeason ->
            bySeason.keys().forEach { seasonKey ->
                val list = bySeason.optJSONArray(seasonKey) ?: return@forEach
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.let { episode(config, client, entry, it, seasonKey.toIntOrNull()) }?.let(episodes::add)
                }
            }
        }
        if (episodes.isEmpty()) {
            client.forEachVodObject("series/${entry.providerId}/episodes/") { item ->
                episode(config, client, entry, item, null)?.let(episodes::add)
            }
        }
        return VodDetailsPart(
            plot = info?.optStringOrNull("description"),
            backdropUrl = ProviderMedia.firstImage(info?.opt("backdrop_path")),
            posterUrl = info?.optJSONObject("cover")?.optStringOrNull("url")?.takeIf { it.startsWith("http") },
            genres = VodLibrary.splitGenres(info?.optStringOrNull("genre")),
            tmdbId = info?.optStringOrNull("tmdb_id")?.takeIf { it.isNotBlank() && it != "0" },
            episodes = episodes
        )
    }

    private fun episode(config: SourceConfig, client: DispatcharrClient, entry: VodEntry, item: JSONObject, seasonKey: Int?): VodEpisode? {
        val uuid = item.optStringOrNull("uuid") ?: return null
        val season = item.optInt("season_number", seasonKey ?: 0)
        val number = item.optInt("episode_number", 0)
        val title = item.optStringOrNull("title") ?: item.optStringOrNull("name") ?: ""
        val duration = item.optInt("duration_secs", 0).takeIf { it > 0 }
        return VodEpisode(
            key = XtreamVod.episodeKey(season, number),
            season = season,
            number = number,
            title = if (VodNames.parse(title).season != null) "" else title,
            plot = item.optStringOrNull("plot") ?: item.optStringOrNull("description"),
            durationSecs = duration,
            stillUrl = item.optStringOrNull("movie_image")?.takeIf { it.startsWith("http") },
            airDate = item.optStringOrNull("air_date"),
            versions = listOf(version(config, entry, client.vodStreamUrl("episode", uuid), title.ifBlank { entry.name }, ProviderMedia.from(null), duration))
        )
    }

    private fun version(config: SourceConfig, entry: VodEntry, url: String, name: String, media: ProviderMedia, duration: Int?) = VodVersion(
        sourceId = config.id,
        sourceName = config.name,
        url = url,
        name = name,
        resolution = media.resolution,
        videoCodec = media.videoCodec,
        hdr = media.hdr,
        bitrateKbps = media.bitrateKbps,
        qualityTags = entry.qualityTags,
        audio = media.audio,
        languageTags = entry.languageTags,
        subtitles = media.subtitles,
        durationSecs = duration
    )

    private const val TAG = "DispatcharrVod"
    private const val MAX_ITEMS = 100_000
    private const val CATEGORY_PAGE_SIZE = 500
    private const val MAX_CATEGORY_PAGES = 20
    private const val CATEGORY_PARALLEL = 4
    private const val GROUP_PAGE_SIZE = 100
    // Loading the chosen categories: big pages (the server serves 500), a few categories at a time
    private const val LOAD_PAGE_SIZE = 500
    private const val LOAD_PARALLEL = 6
    private const val PROGRESS_INTERVAL_MS = 4_000L
}
