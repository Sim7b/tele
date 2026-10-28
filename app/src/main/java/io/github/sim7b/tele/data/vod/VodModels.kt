package io.github.sim7b.tele.data.vod

enum class VodKind { MOVIE, SERIES }

/**
 * A movie or a series as listed by one source, with what its list gives (Xtream get_vod_streams /
 * get_series, Dispatcharr /api/vod/, M3U entries). Details (tracks, episodes) are loaded on demand.
 *
 * [providerId] identifies it at its source: Xtream stream / series id, Dispatcharr id, M3U stream URL
 * (movies) or series name (series). [streamUrl] is known in advance for M3U and Xtream movies.
 */
data class VodEntry(
    val sourceId: String,
    val kind: VodKind,
    val providerId: String,
    val name: String,
    val title: String,
    val year: Int?,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val addedAt: Long? = null,
    val category: String? = null,
    val streamUrl: String? = null,
    val qualityTags: List<String> = emptyList(),
    val languageTags: List<String> = emptyList(),
    // Dispatcharr: uuid of the movie for its proxy URL
    val uuid: String? = null,
    val containerExtension: String? = null,
    // M3U: one entry per episode of a series, grouped into the series by the library
    val season: Int? = null,
    val episode: Int? = null
)

/**
 * A title of the library: the entries of every source that are the same movie or series
 * (same TMDB id, else same title and year), shown once.
 */
data class VodTitle(
    val key: String,
    val kind: VodKind,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val genres: List<String>,
    val rating: Double?,
    val tmdbId: String?,
    val imdbId: String?,
    val addedAt: Long?,
    val entries: List<VodEntry>
)

/** An audio track as described by the provider. */
data class VodAudio(val language: String?, val codec: String?, val channels: Int?)

/**
 * A playable file of a movie or an episode at one source, with what the provider says of it:
 * quality, audio tracks, subtitles. Lists are empty when the provider gives nothing.
 */
data class VodVersion(
    val sourceId: String,
    val sourceName: String,
    val url: String,
    val name: String,
    val resolution: String? = null,
    val videoCodec: String? = null,
    val hdr: Boolean = false,
    val bitrateKbps: Int? = null,
    val qualityTags: List<String> = emptyList(),
    val audio: List<VodAudio> = emptyList(),
    val languageTags: List<String> = emptyList(),
    val subtitles: List<String> = emptyList(),
    val durationSecs: Int? = null
) {
    /** Quality label: "2160p HEVC HDR", "1080p", else the tags of the name ("4K", "FHD"). */
    val qualityLabel: String?
        get() = listOfNotNull(resolution, videoCodec, "HDR".takeIf { hdr }).joinToString(" ").ifBlank { null }
            ?: qualityTags.joinToString(" ").ifBlank { null }
}

data class VodEpisode(
    val key: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String? = null,
    val durationSecs: Int? = null,
    val stillUrl: String? = null,
    val airDate: String? = null,
    val versions: List<VodVersion> = emptyList()
)

data class VodSeason(val number: Int, val episodes: List<VodEpisode>)

/** What a title's sheet shows: the title completed by the providers (and TVmaze / TMDB), its files or episodes. */
data class VodDetails(
    val title: VodTitle,
    val durationSecs: Int? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val versions: List<VodVersion> = emptyList(),
    val seasons: List<VodSeason> = emptyList()
)
