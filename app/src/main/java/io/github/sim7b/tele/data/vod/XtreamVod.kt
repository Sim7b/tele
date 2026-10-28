package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.optStringOrNull
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.remote.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Movies and series of an Xtream Codes account: lists (get_vod_streams, get_series, streamed: accounts
 * list tens of thousands of titles) and details (get_vod_info, get_series_info).
 */
object XtreamVod {

    suspend fun loadCatalog(config: SourceConfig, maxItems: Int = MAX_ITEMS): List<VodEntry> = withContext(Dispatchers.IO) {
        XtreamClient.authenticate(config).getOrThrow()
        // Categories first (a few hundred): each entry is built once, with its category
        val (movieNames, seriesNames) = coroutineScope {
            val movies = async { categories(config, "get_vod_categories") }
            val series = async { categories(config, "get_series_categories") }
            movies.await() to series.await()
        }
        val entries = ArrayList<VodEntry>()
        XtreamClient.forEachObject(XtreamClient.apiUrl(config, "get_vod_streams"), config) { item ->
            if (entries.size < maxItems) movie(config, item, movieNames)?.let(entries::add)
        }
        XtreamClient.forEachObject(XtreamClient.apiUrl(config, "get_series"), config) { item ->
            if (entries.size < maxItems) series(config, item, seriesNames)?.let(entries::add)
        }
        entries.trimToSize()
        entries
    }

    private suspend fun categories(config: SourceConfig, action: String): Map<String, String> {
        val names = HashMap<String, String>()
        try {
            XtreamClient.forEachObject(XtreamClient.apiUrl(config, action), config) {
                it.optStringOrNull("category_name")?.let { name -> names[it.optString("category_id")] = name }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Categories are only a hint
        }
        return names
    }

    private fun movie(config: SourceConfig, item: JSONObject, categories: Map<String, String>): VodEntry? {
        val id = item.optStringOrNull("stream_id") ?: return null
        val name = item.optStringOrNull("name") ?: return null
        val parsed = VodNames.parse(name)
        val extension = item.optStringOrNull("container_extension") ?: "mp4"
        return VodEntry(
            sourceId = config.id,
            kind = VodKind.MOVIE,
            providerId = id,
            name = name,
            title = parsed.title,
            year = item.optStringOrNull("year")?.toIntOrNull() ?: parsed.year,
            posterUrl = item.optStringOrNull("stream_icon")?.takeIf { it.startsWith("http") },
            rating = VodLibrary.rating(item.optStringOrNull("rating")) ?: VodLibrary.rating(item.optStringOrNull("rating_5based"), fiveBased = true),
            tmdbId = (item.optStringOrNull("tmdb") ?: item.optStringOrNull("tmdb_id"))?.takeIf { it != "0" },
            addedAt = item.optStringOrNull("added")?.toLongOrNull()?.times(1000),
            category = item.optStringOrNull("category_id")?.let(categories::get),
            // The file URL (with the credentials) is built when the sheet opens: not kept for 50,000 titles
            qualityTags = parsed.qualityTags,
            languageTags = parsed.languageTags,
            containerExtension = extension
        )
    }

    private fun series(config: SourceConfig, item: JSONObject, categories: Map<String, String>): VodEntry? {
        val id = item.optStringOrNull("series_id") ?: return null
        val name = item.optStringOrNull("name") ?: return null
        val parsed = VodNames.parse(name)
        return VodEntry(
            sourceId = config.id,
            kind = VodKind.SERIES,
            providerId = id,
            name = name,
            title = parsed.title,
            year = VodNames.yearOf(item.optStringOrNull("releaseDate") ?: item.optStringOrNull("release_date")) ?: parsed.year,
            posterUrl = item.optStringOrNull("cover")?.takeIf { it.startsWith("http") },
            backdropUrl = ProviderMedia.firstImage(item.opt("backdrop_path")),
            plot = VodLibrary.shortPlot(item.optStringOrNull("plot")),
            genres = VodLibrary.splitGenres(item.optStringOrNull("genre")),
            rating = VodLibrary.rating(item.optStringOrNull("rating")) ?: VodLibrary.rating(item.optStringOrNull("rating_5based"), fiveBased = true),
            tmdbId = (item.optStringOrNull("tmdb") ?: item.optStringOrNull("tmdb_id"))?.takeIf { it != "0" },
            addedAt = item.optStringOrNull("last_modified")?.toLongOrNull()?.times(1000),
            category = item.optStringOrNull("category_id")?.let(categories::get),
            qualityTags = parsed.qualityTags,
            languageTags = parsed.languageTags
        )
    }

    /** Details of a movie entry: texts, cast and the file with its tracks. */
    suspend fun movieDetails(config: SourceConfig, entry: VodEntry): VodDetailsPart = withContext(Dispatchers.IO) {
        val root = JSONObject(XtreamClient.get(XtreamClient.apiUrl(config, "get_vod_info").newBuilder().addQueryParameter("vod_id", entry.providerId).build(), config))
        val info = root.optJSONObject("info")
        val media = ProviderMedia.from(info)
        val extension = root.optJSONObject("movie_data")?.optStringOrNull("container_extension") ?: entry.containerExtension ?: "mp4"
        val duration = info?.optInt("duration_secs", 0)?.takeIf { it > 0 }
        VodDetailsPart(
            plot = info?.optStringOrNull("plot") ?: info?.optStringOrNull("description"),
            backdropUrl = ProviderMedia.firstImage(info?.opt("backdrop_path")),
            posterUrl = info?.optStringOrNull("movie_image") ?: info?.optStringOrNull("cover_big"),
            genres = VodLibrary.splitGenres(info?.optStringOrNull("genre")),
            director = info?.optStringOrNull("director"),
            cast = splitPeople(info?.optStringOrNull("cast") ?: info?.optStringOrNull("actors")),
            tmdbId = info?.optStringOrNull("tmdb_id")?.takeIf { it != "0" },
            durationSecs = duration,
            versions = listOf(
                version(config, entry, fileUrl(config, "movie", entry.providerId, extension), entry.name, media, duration)
            )
        )
    }

    /** Details of a series entry: texts and its episodes with their files. */
    suspend fun seriesDetails(config: SourceConfig, entry: VodEntry): VodDetailsPart = withContext(Dispatchers.IO) {
        val root = JSONObject(XtreamClient.get(XtreamClient.apiUrl(config, "get_series_info").newBuilder().addQueryParameter("series_id", entry.providerId).build(), config))
        val info = root.optJSONObject("info")
        val episodes = ArrayList<VodEpisode>()
        root.optJSONObject("episodes")?.let { bySeason ->
            bySeason.keys().forEach { seasonKey ->
                val list = bySeason.optJSONArray(seasonKey) ?: return@forEach
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optStringOrNull("id") ?: continue
                    val season = item.optInt("season", seasonKey.toIntOrNull() ?: 0)
                    val number = item.optInt("episode_num", i + 1)
                    val episodeInfo = item.optJSONObject("info")
                    val media = ProviderMedia.from(episodeInfo)
                    val extension = item.optStringOrNull("container_extension") ?: "mp4"
                    val title = item.optStringOrNull("title") ?: entry.name
                    val duration = episodeInfo?.optInt("duration_secs", 0)?.takeIf { it > 0 }
                    episodes.add(
                        VodEpisode(
                            key = episodeKey(season, number),
                            season = season,
                            number = number,
                            title = VodNames.parse(title).let { parsed -> if (parsed.season != null) "" else title },
                            plot = episodeInfo?.optStringOrNull("plot"),
                            durationSecs = duration,
                            stillUrl = episodeInfo?.optStringOrNull("movie_image")?.takeIf { it.startsWith("http") },
                            airDate = episodeInfo?.optStringOrNull("releasedate") ?: episodeInfo?.optStringOrNull("air_date"),
                            versions = listOf(version(config, entry, fileUrl(config, "series", id, extension), title, media, duration))
                        )
                    )
                }
            }
        }
        VodDetailsPart(
            plot = info?.optStringOrNull("plot"),
            backdropUrl = ProviderMedia.firstImage(info?.opt("backdrop_path")),
            posterUrl = info?.optStringOrNull("cover"),
            genres = VodLibrary.splitGenres(info?.optStringOrNull("genre")),
            director = info?.optStringOrNull("director"),
            cast = splitPeople(info?.optStringOrNull("cast")),
            tmdbId = info?.optStringOrNull("tmdb")?.takeIf { it != "0" },
            episodes = episodes
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
        qualityTags = VodNames.parse(name).qualityTags.ifEmpty { entry.qualityTags },
        audio = media.audio,
        languageTags = VodNames.parse(name).languageTags.ifEmpty { entry.languageTags },
        subtitles = media.subtitles,
        durationSecs = duration
    )

    private fun fileUrl(config: SourceConfig, type: String, id: String, extension: String): String =
        XtreamClient.base(config).newBuilder()
            .addPathSegment(type)
            .addPathSegment(config.username)
            .addPathSegment(config.password)
            .addPathSegment("$id.${extension.filter { it.isLetterOrDigit() }.ifEmpty { "mp4" }}")
            .build()
            .toString()

    internal fun splitPeople(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(MAX_CAST).orEmpty()

    fun episodeKey(season: Int, number: Int) = "S${season}E$number"

    private const val MAX_ITEMS = 300_000
    private const val MAX_CAST = 12
}

/** What a source tells about a title when its sheet opens (to be merged with the other sources). */
data class VodDetailsPart(
    val plot: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val genres: List<String> = emptyList(),
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val tmdbId: String? = null,
    val durationSecs: Int? = null,
    val versions: List<VodVersion> = emptyList(),
    val episodes: List<VodEpisode> = emptyList()
)
