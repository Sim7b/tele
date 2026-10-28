package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.parser.M3uEntry
import io.github.sim7b.tele.data.parser.M3uParser
import io.github.sim7b.tele.data.remote.M3uClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Movies and series of an M3U playlist: entries with a movie / series path or a video file extension.
 * The playlist only gives names: title, year, quality and languages are read from them, and each episode
 * ("The Bear S03E02") is an entry of its series.
 */
object M3uVod {

    suspend fun loadCatalog(config: SourceConfig): List<VodEntry> = withContext(Dispatchers.IO) {
        toEntries(config.id, M3uClient.download(config, vod = true).entries)
    }

    internal fun toEntries(sourceId: String, entries: List<M3uEntry>): List<VodEntry> = entries.map { entry ->
        val parsed = VodNames.parse(entry.name)
        val series = M3uParser.isSeriesEpisode(entry.url, entry.name)
        VodEntry(
            sourceId = sourceId,
            kind = if (series) VodKind.SERIES else VodKind.MOVIE,
            providerId = entry.url,
            name = entry.name,
            title = parsed.title,
            year = parsed.year,
            // The logo of an episode is usually the series poster
            posterUrl = entry.logo?.takeIf { it.startsWith("http") },
            category = entry.group,
            streamUrl = entry.url,
            qualityTags = parsed.qualityTags,
            languageTags = parsed.languageTags,
            season = if (series) parsed.season ?: 1 else null,
            episode = if (series) parsed.episode ?: 0 else null
        )
    }

    /** Episodes of a series from its M3U entries (one per episode), files of the same episode together. */
    fun episodes(sourceName: (String) -> String, entries: List<VodEntry>): List<VodEpisode> =
        entries.filter { it.season != null && it.streamUrl != null }
            .groupBy { it.season!! to it.episode!! }
            .map { (number, files) ->
                VodEpisode(
                    key = XtreamVod.episodeKey(number.first, number.second),
                    season = number.first,
                    number = number.second,
                    title = "",
                    versions = files.map { file -> version(sourceName(file.sourceId), file) }
                )
            }

    /** The file of an M3U movie or episode: all the playlist tells is in its name. */
    fun version(sourceName: String, entry: VodEntry) = VodVersion(
        sourceId = entry.sourceId,
        sourceName = sourceName,
        url = entry.streamUrl.orEmpty(),
        name = entry.name,
        qualityTags = entry.qualityTags,
        languageTags = entry.languageTags
    )
}
