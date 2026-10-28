package io.github.sim7b.tele.data.vod

import android.os.SystemClock
import android.util.Log
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.remote.DispatcharrClient
import io.github.sim7b.tele.data.storage.SettingsRepository
import io.github.sim7b.tele.data.toUiText
import io.github.sim7b.tele.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The library as shown: titles (and by key, and the one shown first, built off the main thread with
 * them: tens of thousands of titles), whether sources are still loading, the first source error.
 */
data class VodLibraryState(
    val titles: List<VodTitle> = emptyList(),
    val byKey: Map<String, VodTitle> = emptyMap(),
    val featured: VodTitle? = null,
    val loading: Boolean = false,
    val error: UiText? = null,
    val loaded: Boolean = false
)

/**
 * Movies and series of the enabled sources: catalogs shown from the device at once, reloaded from the
 * sources when older than [MAX_AGE_MS] (or on request), and the sheets built on demand from every source
 * that has the title, completed by TMDB / TVmaze.
 */
class VodRepository(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) {
    private val _library = MutableStateFlow(VodLibraryState())
    val library: StateFlow<VodLibraryState> = _library.asStateFlow()

    // TMDB rankings (with a key), fetched with the library and kept [RANKINGS_MAX_AGE_MS]
    private val _rankings = MutableStateFlow<Map<Ranking, List<RankedItem>>>(emptyMap())
    val rankings: StateFlow<Map<Ranking, List<RankedItem>>> = _rankings.asStateFlow()
    private var rankingsAt = 0L
    private var rankingsJob: Job? = null

    // Background TMDB indexing of the titles (countries, studios, popularity)
    val index get() = settings.vodIndex
    private var indexJob: Job? = null

    // Entries by source (replaced as a whole), and the sources they were loaded for
    private val catalogs = ConcurrentHashMap<String, List<VodEntry>>()
    private val errors = ConcurrentHashMap<String, UiText>()
    private var loadedFor: List<SourceConfig> = emptyList()
    private var loadJob: Job? = null
    private val dispatcharrClients = ConcurrentHashMap<String, DispatcharrClient>()

    // Sheets already built (a few dozen: users go back and forth between a sheet and the library)
    private val details = object : LinkedHashMap<String, VodDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VodDetails>?) = size > MAX_DETAILS
    }

    /** Loads the library of the enabled sources, if not done yet for them. */
    fun open() {
        val configs = settings.enabledConfigs.value
        if (configs == loadedFor && (_library.value.loaded || loadJob?.isActive == true)) return
        load(configs, force = false)
    }

    /**
     * Frees the library (tens of MB for 50,000 titles) once the movies and series screens are left for a
     * while; the next opening reads the catalogs saved on the device again.
     */
    fun release() {
        loadJob?.cancel()
        loadJob = null
        indexJob?.cancel()
        indexJob = null
        loadedFor = emptyList()
        catalogs.clear()
        errors.clear()
        synchronized(details) { details.clear() }
        _library.value = VodLibraryState()
    }

    /** Rebuilds the library from the catalogs in memory (a display setting changed). */
    fun reapply() {
        if (catalogs.isEmpty()) return
        scope.launch { publish(loading = loadJob?.isActive == true) }
    }

    /** Reloads every catalog from its source. */
    fun refresh() {
        load(settings.enabledConfigs.value, force = true)
    }

    private fun load(configs: List<SourceConfig>, force: Boolean) {
        loadJob?.cancel()
        loadedFor = configs
        synchronized(details) { details.clear() }
        catalogs.keys.retainAll(configs.map { it.id }.toSet())
        errors.clear()
        _library.value = _library.value.copy(loading = true, error = null)
        loadJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                coroutineScope {
                    configs.map { config -> async { loadSource(config, force) } }.awaitAll()
                }
            } finally {
                if (loadJob === coroutineContext[Job]) {
                    publish(loading = false)
                    Log.i(TAG, "Library of ${catalogs.values.sumOf { it.size }} entries in ${SystemClock.elapsedRealtime() - startedAt} ms")
                    startTmdbWork()
                }
            }
        }
    }

    /** With a TMDB key: rankings (when stale) and the indexing of the titles not known yet. */
    private fun startTmdbWork() {
        val key = settings.tmdbKey.value.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        if (rankingsJob?.isActive != true && (now - rankingsAt > RANKINGS_MAX_AGE_MS || _rankings.value.isEmpty())) {
            rankingsJob = scope.launch {
                val fetched = VodRankings.fetch(key)
                if (fetched.values.any { it.isNotEmpty() }) {
                    _rankings.value = fetched
                    rankingsAt = System.currentTimeMillis()
                }
            }
        }
        indexJob?.cancel()
        val titles = _library.value.titles
        indexJob = scope.launch { index.index(titles, key) }
    }

    private suspend fun loadSource(config: SourceConfig, force: Boolean) {
        val readStart = SystemClock.elapsedRealtime()
        val cached = withContext(Dispatchers.IO) { settings.getVodCatalog(config.id) }
        Log.i(TAG, "Catalog of a ${config.type} source read from the device: ${cached?.entries?.size ?: 0} entries in ${SystemClock.elapsedRealtime() - readStart} ms")
        if (cached != null) {
            catalogs[config.id] = cached.entries
            publish(loading = true)
        }
        val fresh = cached != null && System.currentTimeMillis() - cached.savedAt in 0..MAX_AGE_MS
        if (fresh && !force) return
        try {
            val entries = when (config.type) {
                SourceType.XTREAM -> XtreamVod.loadCatalog(config)
                SourceType.M3U -> M3uVod.loadCatalog(config)
                SourceType.DISPATCHARR -> DispatcharrVod.loadCatalog(config, dispatcharr(config)) { partial ->
                    // First load (nothing on the device yet): the library fills as the pages arrive
                    if (cached == null && partial.isNotEmpty()) {
                        catalogs[config.id] = partial
                        publish(loading = true)
                    }
                }
            }
            catalogs[config.id] = entries
            errors.remove(config.id)
            publish(loading = true)
            withContext(Dispatchers.IO) { settings.saveVodCatalog(config.id, System.currentTimeMillis(), entries) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The catalog of the device stays shown
            Log.w(TAG, "Catalog of a ${config.type} source unavailable: ${e.javaClass.simpleName}")
            errors[config.id] = UiText.Res(R.string.error_with_source, config.name, e.toUiText())
        }
    }

    private suspend fun publish(loading: Boolean) {
        val order = loadedFor.map { it.id }
        val entries = order.flatMap { catalogs[it].orEmpty() }
        val buildStart = SystemClock.elapsedRealtime()
        val hideTheater = settings.vodDisplay.value.hideTheater
        val (titles, byKey, featured) = withContext(Dispatchers.Default) {
            val titles = VodLibrary.build(entries).let { all -> if (hideTheater) all.filterNot { VodDisplay.isTheaterCopy(it) } else all }
            Triple(titles, titles.associateBy { it.key }, VodRows.featured(titles))
        }
        Log.i(TAG, "Library of ${titles.size} titles built in ${SystemClock.elapsedRealtime() - buildStart} ms")
        _library.value = VodLibraryState(
            titles = titles,
            byKey = byKey,
            featured = featured,
            loading = loading,
            error = order.firstNotNullOfOrNull { errors[it] },
            loaded = !loading || titles.isNotEmpty()
        )
    }

    private fun dispatcharr(config: SourceConfig): DispatcharrClient =
        dispatcharrClients.getOrPut(config.id) { DispatcharrClient(config) }.also { it.setConfig(config) }

    fun title(key: String): VodTitle? = _library.value.byKey[key]

    fun cachedDetails(key: String): VodDetails? = synchronized(details) { details[key] }

    // Posters found for the titles without one (a few hundred cards are seen in a session); "" = none
    private val posters = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_POSTERS
    }

    /** Poster of [title] when its providers give none, looked up once (only for the cards shown). */
    suspend fun posterFor(title: VodTitle): String? {
        synchronized(posters) { posters[title.key] }?.let { return it.ifEmpty { null } }
        val found = VodMetadata.poster(title, settings.tmdbKey.value.takeIf { it.isNotBlank() })
        synchronized(posters) { posters[title.key] = found.orEmpty() }
        return found
    }

    /**
     * Sheet of [title]: details of every source that has it (in parallel, each bounded in time), files
     * of the same movie or episode together, completed by TMDB (with a key) or TVmaze (series).
     */
    suspend fun details(title: VodTitle): VodDetails {
        cachedDetails(title.key)?.let { return it }
        val configs = loadedFor.associateBy { it.id }
        val sourceName = { id: String -> configs[id]?.name.orEmpty() }

        // M3U: every entry is a file (a movie version, or an episode); the others: one request per source
        val m3uEntries = title.entries.filter { configs[it.sourceId]?.type == SourceType.M3U }
        val remoteEntries = title.entries.filter { configs[it.sourceId]?.type != SourceType.M3U }.distinctBy { it.sourceId to it.providerId }
        val parts = coroutineScope {
            remoteEntries.map { entry ->
                async {
                    val config = configs[entry.sourceId] ?: return@async null
                    withTimeoutOrNull(DETAILS_TIMEOUT_MS) {
                        try {
                            when {
                                config.type == SourceType.XTREAM && title.kind == VodKind.MOVIE -> XtreamVod.movieDetails(config, entry)
                                config.type == SourceType.XTREAM -> XtreamVod.seriesDetails(config, entry)
                                title.kind == VodKind.MOVIE -> DispatcharrVod.movieDetails(config, dispatcharr(config), entry)
                                else -> DispatcharrVod.seriesDetails(config, dispatcharr(config), entry)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Details unavailable: ${e.javaClass.simpleName}")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val versions = parts.flatMap { it.versions } +
            if (title.kind == VodKind.MOVIE) m3uEntries.map { M3uVod.version(sourceName(it.sourceId), it) } else emptyList()
        val episodes = (parts.flatMap { it.episodes } + M3uVod.episodes(sourceName, m3uEntries))
            .groupBy { it.key }
            .map { (_, same) -> same.first().copy(versions = same.flatMap { it.versions }) }

        val tmdbId = title.tmdbId ?: parts.firstNotNullOfOrNull { it.tmdbId }
        val merged = title.copy(
            tmdbId = tmdbId,
            plot = title.plot ?: parts.firstNotNullOfOrNull { it.plot },
            posterUrl = title.posterUrl ?: parts.firstNotNullOfOrNull { it.posterUrl },
            backdropUrl = title.backdropUrl ?: parts.firstNotNullOfOrNull { it.backdropUrl },
            genres = title.genres.ifEmpty { parts.firstOrNull { it.genres.isNotEmpty() }?.genres.orEmpty() }
        )
        val base = VodDetails(
            title = merged,
            // Some providers give minutes in duration_secs: below 5 min the value is not trusted
            durationSecs = (parts.mapNotNull { it.durationSecs } + versions.mapNotNull { it.durationSecs }).firstOrNull { it >= MIN_DURATION_SECS },
            director = parts.firstNotNullOfOrNull { it.director },
            cast = parts.firstOrNull { it.cast.isNotEmpty() }?.cast.orEmpty(),
            versions = versions,
            seasons = seasons(episodes)
        )
        val enrichment = VodMetadata.enrich(merged, episodes.map { it.season }.toSet(), settings.tmdbKey.value.takeIf { it.isNotBlank() })
        val result = enrichment?.let { enrich(base, it, preferEnrichment = settings.tmdbKey.value.isNotBlank()) } ?: base
        synchronized(details) { details[title.key] = result }
        return result
    }

    private fun seasons(episodes: List<VodEpisode>): List<VodSeason> =
        episodes.groupBy { it.season }
            .toSortedMap()
            .map { (season, list) -> VodSeason(season, list.sortedBy { it.number }) }

    companion object {
        private const val TAG = "VodRepository"
        private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val DETAILS_TIMEOUT_MS = 15_000L
        private const val MAX_DETAILS = 40
        private const val MAX_POSTERS = 3_000
        private const val MIN_DURATION_SECS = 300
        private const val RANKINGS_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        /**
         * [details] completed by [enrichment]: TMDB texts (in the language of the TV) replace the provider
         * ones when [preferEnrichment]; TVmaze ones (English) only fill what is missing.
         */
        internal fun enrich(details: VodDetails, enrichment: VodEnrichment, preferEnrichment: Boolean): VodDetails {
            fun <T> pick(own: T?, other: T?): T? = if (preferEnrichment) other ?: own else own ?: other
            val title = details.title
            val episodesMeta = enrichment.episodes
            return details.copy(
                title = title.copy(
                    plot = pick(title.plot, enrichment.plot),
                    posterUrl = pick(title.posterUrl, enrichment.posterUrl),
                    backdropUrl = pick(title.backdropUrl, enrichment.backdropUrl),
                    genres = if (preferEnrichment) enrichment.genres.ifEmpty { title.genres } else title.genres.ifEmpty { enrichment.genres },
                    rating = pick(title.rating, enrichment.rating),
                    year = title.year ?: enrichment.year,
                    tmdbId = title.tmdbId ?: enrichment.tmdbId,
                    imdbId = title.imdbId ?: enrichment.imdbId
                ),
                durationSecs = if (preferEnrichment) enrichment.durationSecs ?: details.durationSecs else details.durationSecs ?: enrichment.durationSecs,
                director = pick(details.director, enrichment.director),
                cast = if (preferEnrichment) enrichment.cast.ifEmpty { details.cast } else details.cast.ifEmpty { enrichment.cast },
                seasons = details.seasons.map { season ->
                    season.copy(episodes = season.episodes.map { episode ->
                        val meta = episodesMeta[episode.key] ?: return@map episode
                        episode.copy(
                            title = if (preferEnrichment || episode.title.isBlank()) meta.title.ifBlank { episode.title } else episode.title,
                            plot = pick(episode.plot, meta.plot),
                            stillUrl = pick(episode.stillUrl, meta.stillUrl),
                            durationSecs = episode.durationSecs ?: meta.durationSecs,
                            airDate = episode.airDate ?: meta.airDate
                        )
                    })
                }
            )
        }
    }
}
