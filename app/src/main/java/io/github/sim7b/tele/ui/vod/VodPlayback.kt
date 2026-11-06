package io.github.sim7b.tele.ui.vod

import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.subtitles.SubtitleQuery
import io.github.sim7b.tele.data.vod.VodDetails
import io.github.sim7b.tele.data.vod.VodEpisode
import io.github.sim7b.tele.data.vod.VodRows
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.data.vod.VodVersion

/**
 * A movie or an episode to play: its file (and the other files to try), where to start, and what the
 * player shows of it (title, episode, summary, poster and image).
 */
data class VodPlayRequest(
    val titleKey: String,
    val episodeKey: String?,
    val label: String,
    val sourceId: String,
    val url: String,
    val fallbacks: List<String>,
    val startMs: Long,
    val title: String = label,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val plot: String? = null,
    val posterUrl: String? = null,
    // Still of the episode, else the backdrop of the title
    val imageUrl: String? = null,
    // Ids of the title (of the series for an episode), to look its subtitles up
    val year: Int? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null
) {
    /** Key of its position and of its downloaded subtitles. */
    val itemKey: String get() = VodRows.progressKey(titleKey, episodeKey)

    val subtitleQuery: SubtitleQuery
        get() = SubtitleQuery(title = title, year = year, tmdbId = tmdbId, imdbId = imdbId, season = season, episode = episode)
}

/** Subtitles of [episode] of [title] (null: the movie). */
internal fun subtitleQuery(title: VodTitle, episode: VodEpisode?): SubtitleQuery =
    SubtitleQuery(
        title = title.title,
        year = title.year,
        tmdbId = title.tmdbId,
        imdbId = title.imdbId,
        season = episode?.season,
        episode = episode?.number
    )

/** Request playing [episode] (null: the movie) of [title] from [chosen], else the first of [files]. */
internal fun playRequest(
    title: VodTitle,
    titleKey: String,
    episode: VodEpisode?,
    files: List<VodVersion>,
    chosen: VodVersion?,
    startMs: Long
): VodPlayRequest? {
    val file = chosen ?: files.firstOrNull() ?: return null
    return VodPlayRequest(
        titleKey = titleKey,
        episodeKey = episode?.key,
        label = if (episode == null) title.title else "${title.title} · ${episodeLabelPlain(episode)}",
        sourceId = file.sourceId,
        url = file.url,
        fallbacks = files.filter { it !== file }.map { it.url },
        startMs = startMs,
        title = title.title,
        season = episode?.season,
        episode = episode?.number,
        episodeTitle = episode?.title?.takeIf { it.isNotBlank() },
        plot = episode?.plot?.takeIf { it.isNotBlank() } ?: title.plot,
        posterUrl = title.posterUrl,
        imageUrl = episode?.stillUrl ?: title.backdropUrl,
        year = title.year,
        tmdbId = title.tmdbId,
        imdbId = title.imdbId
    )
}

internal fun episodeLabelPlain(episode: VodEpisode): String =
    "S${episode.season}E${episode.number}" + if (episode.title.isNotBlank()) " · ${episode.title}" else ""

/** Episodes in watching order: seasons in order, the specials (season 0) last. */
internal fun watchingOrder(episodes: List<VodEpisode>): List<VodEpisode> =
    episodes.sortedWith(compareBy<VodEpisode> { if (it.season == 0) Int.MAX_VALUE else it.season }.thenBy { it.number })

/** Episode after the one keyed [key] in watching order (null after the last one). */
internal fun episodeAfter(episodes: List<VodEpisode>, key: String): VodEpisode? {
    val ordered = watchingOrder(episodes)
    val index = ordered.indexOfFirst { it.key == key }
    return if (index < 0) null else ordered.getOrNull(index + 1)
}

/**
 * Episode offered at the end of [current] (null for a movie or the last episode), resumed where it was
 * left unless it was finished.
 */
internal fun nextPlayRequest(current: VodPlayRequest, details: VodDetails?, progress: Map<String, VodProgress>): VodPlayRequest? {
    val key = current.episodeKey ?: return null
    val episodes = details?.seasons?.flatMap { it.episodes }.orEmpty()
    val next = episodeAfter(episodes, key) ?: return null
    val start = progress[VodRows.progressKey(current.titleKey, next.key)]?.takeIf { !it.finished }?.positionMs ?: 0L
    return playRequest(details!!.title, current.titleKey, next, next.versions, null, start)
}

/**
 * Seek of a remote press: 10 s back or 30 s forward; held, the arrow moves by steps that grow
 * (the key repeats about 20 times a second).
 */
internal fun seekStep(forward: Boolean, repeatCount: Int): Long {
    val ms = when {
        repeatCount == 0 -> if (forward) 30_000L else 10_000L
        repeatCount < 20 -> 10_000L
        repeatCount < 60 -> 30_000L
        else -> 60_000L
    }
    return if (forward) ms else -ms
}

/**
 * Usual name of a picture size: "1080p" for 1920x1036 (cropped films keep their width), "720p" for 1280x718.
 */
internal fun videoFormatLabel(width: Int?, height: Int?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    return when {
        width >= 3600 || height >= 2000 -> "4K"
        width >= 2400 || height >= 1300 -> "1440p"
        width >= 1800 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        else -> "${height}p"
    }
}

/** The next episode card shows in the last [UP_NEXT_LEAD_MS] of an episode (its credits, roughly). */
internal fun inCredits(positionMs: Long, durationMs: Long): Boolean =
    durationMs > UP_NEXT_LEAD_MS * 4 && durationMs - positionMs in 0..UP_NEXT_LEAD_MS

internal const val UP_NEXT_LEAD_MS = 30_000L

/**
 * Where "Skip intro" jumps from [positionMs] of an episode: to the end of the intro learned for its series
 * ([learnedEndMs]), shown until a little before it; else [DEFAULT_INTRO_SKIP_MS] further, shown in the
 * first minutes only. Null when not offered (a movie-length check keeps it off short clips).
 */
internal fun introSkipTarget(positionMs: Long, durationMs: Long, learnedEndMs: Long?): Long? {
    if (durationMs < INTRO_MIN_DURATION_MS || positionMs < INTRO_SHOW_FROM_MS) return null
    if (learnedEndMs != null) return learnedEndMs.takeIf { positionMs < it - INTRO_MARGIN_MS }
    return (positionMs + DEFAULT_INTRO_SKIP_MS).takeIf { positionMs < INTRO_DEFAULT_WINDOW_MS }
}

/**
 * End of the intro told by a jump forward from [fromMs] to [toMs] in the opening minutes of an episode
 * (the user skipping it by hand), null for any other jump.
 */
internal fun learnedIntroEnd(fromMs: Long, toMs: Long, durationMs: Long): Long? =
    toMs.takeIf {
        durationMs >= INTRO_MIN_DURATION_MS && fromMs < INTRO_WINDOW_MS && toMs - fromMs >= INTRO_MIN_JUMP_MS && toMs <= INTRO_WINDOW_MS
    }

private const val INTRO_MIN_DURATION_MS = 12 * 60_000L
private const val INTRO_SHOW_FROM_MS = 5_000L
private const val INTRO_MARGIN_MS = 3_000L
private const val INTRO_DEFAULT_WINDOW_MS = 2 * 60_000L
private const val INTRO_WINDOW_MS = 5 * 60_000L
private const val INTRO_MIN_JUMP_MS = 20_000L
internal const val DEFAULT_INTRO_SKIP_MS = 85_000L
