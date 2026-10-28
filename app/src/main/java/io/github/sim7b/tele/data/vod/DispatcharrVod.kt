package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.optStringOrNull
import io.github.sim7b.tele.data.remote.DispatcharrClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
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

    private const val MAX_ITEMS = 100_000
    private const val PROGRESS_INTERVAL_MS = 4_000L
}
