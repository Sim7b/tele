package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.remote.TeleApi
import io.github.sim7b.tele.BuildConfig
import android.os.SystemClock
import android.util.Log
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.remote.DispatcharrClient
import io.github.sim7b.tele.data.storage.CachedVodCatalog
import io.github.sim7b.tele.data.storage.SettingsRepository
import io.github.sim7b.tele.data.toUiText
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.storage.VodProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
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
    val loaded: Boolean = false,
    // Sources whose categories are not chosen yet: nothing is loaded from them until then
    val toChoose: List<SourceConfig> = emptyList(),
    // Categories added to the sources since they were chosen, that look like the chosen ones (by source id)
    val newCategories: Map<String, List<VodCategory>> = emptyMap()
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

    // Logos of the streaming services (TMDB), fetched with the rankings
    // TMDB facts looked up by the Dispatcharr servers (Télé API plugin), by "movie:<id>" / "series:<id>", and the
    // time of the last answer by source and kind (then only the changes are asked)
    private val serverFacts = ConcurrentHashMap<String, TmdbFacts>()
    private val factsUntil = ConcurrentHashMap<String, Double>()

    private suspend fun refreshServerFacts(config: SourceConfig) {
        var added = false
        for (kind in listOf("movie", "series")) {
            val key = "${config.id}|$kind"
            val answer = try {
                dispatcharr(config).getVodFacts(kind, factsUntil[key] ?: 0.0)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: continue
            answer.first.forEach { (id, facts) -> serverFacts["$kind:$id"] = facts }
            factsUntil[key] = answer.second
            if (answer.first.isNotEmpty()) added = true
        }
        if (added) applyServerFacts()
    }

    /** The facts of the servers given to the library titles (the TMDB index of the TV, without TMDB requests). */
    private suspend fun applyServerFacts() {
        if (serverFacts.isEmpty()) return
        val titles = _library.value.titles
        if (titles.isEmpty()) return
        withContext(Dispatchers.IO) { index.importServerFacts(titles, HashMap(serverFacts)) }
    }

    // A Dispatcharr source whose plugin searches subtitles with the key of the server
    private val _serverSubtitles = MutableStateFlow(false)
    val serverSubtitles: StateFlow<Boolean> = _serverSubtitles.asStateFlow()
    private val subtitleSources = ConcurrentHashMap.newKeySet<String>()

    // Titles that may also please and segments of the files, by TMDB id (asked once a session)
    private val relatedCache = ConcurrentHashMap<String, List<String>>()
    private val segmentsCache = ConcurrentHashMap<String, VodSegments>()

    /**
     * Titles of the library that may also please, for the sheet of [title]: TMDB recommendations then similar
     * titles, looked up by a Dispatcharr server (Télé API plugin) or with the TMDB key of the TV.
     */
    suspend fun related(title: VodTitle, limit: Int = 20): List<VodTitle> {
        val tmdbId = title.tmdbId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val kind = if (title.kind == VodKind.MOVIE) "movie" else "series"
        val ids = relatedCache.getOrPut("$kind:$tmdbId") {
            val fromServer = loadedFor.filter { it.type == SourceType.DISPATCHARR }.firstNotNullOfOrNull { config ->
                try {
                    dispatcharr(config).getVodRelated(kind, tmdbId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
            fromServer ?: settings.tmdbKey.value.takeIf { it.isNotBlank() }?.let { key ->
                try {
                    VodMetadata.related(if (title.kind == VodKind.MOVIE) "movie" else "tv", tmdbId, key, java.util.Locale.getDefault().toLanguageTag())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }
            }.orEmpty()
        }
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) { VodLibrary.byTmdbIds(_library.value.titles, title.kind, ids, exclude = title.key).take(limit) }
    }

    /**
     * Intro and end credits of a movie (no [season]) or an episode played from [sourceId], from TheIntroDB: through
     * the Télé API plugin of a Dispatcharr server first (that source, then any other; kept on the server), else asked
     * from the TV directly. Null when unknown.
     */
    suspend fun segments(sourceId: String, tmdbId: String?, season: Int?, episode: Int?): VodSegments? {
        val id = tmdbId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
        val key = "$id:$season:$episode"
        segmentsCache[key]?.let { return it.takeIf { found -> !found.isEmpty } }
        // Dispatcharr first (the source of the file, then any other with the plugin): kept there for every TV;
        // asked locally when no server has the plugin or none answered
        val servers = loadedFor.filter { it.type == SourceType.DISPATCHARR }.sortedBy { if (it.id == sourceId) 0 else 1 }
        val fromServer = servers.firstNotNullOfOrNull { config ->
            try {
                dispatcharr(config).getVodSegments(id, season, episode)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
        val found = fromServer ?: try {
            VodSegments.fetch(id, season, episode)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return null
        segmentsCache[key] = found
        return found.takeIf { !it.isEmpty }
    }

    /** The client of a Dispatcharr source whose plugin searches subtitles (null: none). */
    fun subtitleServer(): DispatcharrClient? =
        loadedFor.firstOrNull { it.type == SourceType.DISPATCHARR && it.id in subtitleSources }?.let { dispatcharr(it) }

    // Keys of the titles most watched on the Dispatcharr servers (Télé API plugin), most watched first
    private val _serverPopular = MutableStateFlow<List<String>>(emptyList())
    val serverPopular: StateFlow<List<String>> = _serverPopular.asStateFlow()
    // ("movie" | "series", Dispatcharr id) most watched, by Dispatcharr source
    private val popularIds = ConcurrentHashMap<String, List<Pair<String, String>>>()
    // What is new on the Dispatcharr servers (Télé API plugin), by source: the titles added this week, and the latest
    // episode of the series followed here
    private val freshBySource = ConcurrentHashMap<String, TeleApi.VodFresh>()
    @Volatile private var serverAdded: List<String> = emptyList()
    @Volatile private var newEpisodes: Map<String, TeleApi.NewEpisode> = emptyMap()

    private val _platformLogos = MutableStateFlow<Map<StreamingPlatform, String>>(emptyMap())
    val platformLogos: StateFlow<Map<StreamingPlatform, String>> = _platformLogos.asStateFlow()

    // Lists of the genres opened on their screen, kept [RANKINGS_MAX_AGE_MS]
    private val genreCatalogs = ConcurrentHashMap<Pair<VodGenre, VodKind>, Pair<Long, GenreCatalog>>()

    /** The TMDB lists of [genre] for [kind] (fetched once, then kept, on the device too); empty without a key or offline. */
    suspend fun genreCatalog(genre: VodGenre, kind: VodKind): GenreCatalog {
        val cacheKey = genre to kind
        genreCatalogs[cacheKey]?.let { (at, lists) ->
            if (fresh(at)) return lists
        }
        val name = listName("genre_${genre.name}_${kind.name}")
        keptList(name)?.let { entry ->
            val kept = GenreCatalog(entry.lists["popular"].orEmpty(), entry.lists["recent_best"].orEmpty(), entry.lists["all_time_best"].orEmpty())
            genreCatalogs[cacheKey] = entry.fetchedAt to kept
            return kept
        }
        val key = settings.tmdbKey.value.takeIf { it.isNotBlank() } ?: return GenreCatalog()
        val fetched = VodGenres.catalog(genre, kind, key)
        if (!fetched.isEmpty) {
            val now = System.currentTimeMillis()
            genreCatalogs[cacheKey] = now to fetched
            keepList(name, mapOf("popular" to fetched.popular, "recent_best" to fetched.recentBest, "all_time_best" to fetched.allTimeBest), now)
        }
        return fetched
    }

    // TMDB lists kept on the device: named by list and language of the TV (its titles, its country's catalogs)
    private fun listName(list: String): String =
        "${list}_${Locale.getDefault().toLanguageTag()}".lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")

    private fun fresh(fetchedAt: Long): Boolean = System.currentTimeMillis() - fetchedAt in 0 until RANKINGS_MAX_AGE_MS

    private suspend fun keptList(name: String): TmdbListsCache.Entry? =
        withContext(Dispatchers.IO) { settings.tmdbLists.read(name) }?.takeIf { fresh(it.fetchedAt) }

    private suspend fun keepList(name: String, lists: Map<String, List<RankedItem>>, fetchedAt: Long) =
        withContext(Dispatchers.IO) { settings.tmdbLists.write(name, lists, fetchedAt) }

    // Catalogs of the streaming services opened on their screen, kept [RANKINGS_MAX_AGE_MS]
    private val platformCatalogs = ConcurrentHashMap<Pair<StreamingPlatform, VodKind>, Pair<Long, PlatformCatalog>>()

    /**
     * The titles of [platform] for [kind] on TMDB, by popularity, and its own productions: its full catalog (fetched
     * once, then kept), else the entries of its row on the home (no key, offline).
     */
    suspend fun platformCatalog(platform: StreamingPlatform, kind: VodKind): PlatformCatalog {
        val cacheKey = platform to kind
        platformCatalogs[cacheKey]?.let { (at, items) ->
            if (fresh(at)) return items
        }
        val name = listName("platform_${platform.name}_${kind.name}")
        keptList(name)?.let { entry ->
            val kept = PlatformCatalog(entry.lists["popular"].orEmpty(), entry.lists["originals"].orEmpty())
            if (kept.popular.isNotEmpty()) {
                platformCatalogs[cacheKey] = entry.fetchedAt to kept
                return kept
            }
        }
        val home = PlatformCatalog(Ranking.of(platform, kind)?.let { _rankings.value[it] }.orEmpty())
        val key = settings.tmdbKey.value.takeIf { it.isNotBlank() } ?: return home
        // Asked twice at once (focus, then opening): one fetch
        val fetched = platformFetches.getOrPut(cacheKey) {
            scope.async { VodRankings.platformCatalog(platform, kind, key) }
        }.let { job ->
            try {
                job.await()
            } finally {
                platformFetches.remove(cacheKey, job)
            }
        }
        if (fetched.popular.isEmpty()) return home
        val now = System.currentTimeMillis()
        platformCatalogs[cacheKey] = now to fetched
        keepList(name, mapOf("popular" to fetched.popular, "originals" to fetched.originals), now)
        return fetched
    }

    private val platformFetches = ConcurrentHashMap<Pair<StreamingPlatform, VodKind>, kotlinx.coroutines.Deferred<PlatformCatalog>>()

    /** Starts fetching the catalog of [platform] (its tile focused), to open it at once. */
    fun prefetchPlatform(platform: StreamingPlatform, kind: VodKind) {
        if (settings.tmdbKey.value.isBlank()) return
        scope.launch { platformCatalog(platform, kind) }
    }

    // Background TMDB indexing of the titles (countries, studios, popularity)
    val index get() = settings.vodIndex
    private var indexJob: Job? = null

    // Entries by source (replaced as a whole), and the sources they were loaded for
    private val catalogs = ConcurrentHashMap<String, List<VodEntry>>()
    private val errors = ConcurrentHashMap<String, UiText>()
    private var loadedFor: List<SourceConfig> = emptyList()
    private var loadedSelections: Map<String, VodSelection> = emptyMap()
    private var toChoose: List<SourceConfig> = emptyList()
    private var newCategories: Map<String, List<VodCategory>> = emptyMap()
    // Duration of the last build: partial libraries of a first load are built again accordingly
    @Volatile
    private var lastBuildMs = 0L
    // Catalogs (by identity) and theater setting of the last build: the same ones are not built again
    private var builtFrom: Pair<List<List<VodEntry>>, Boolean>? = null
    private var loadJob: Job? = null
    private val dispatcharrClients = ConcurrentHashMap<String, DispatcharrClient>()

    // Sheets already built (a few dozen: users go back and forth between a sheet and the library)
    private val details = object : LinkedHashMap<String, VodDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VodDetails>?) = size > MAX_DETAILS
    }

    /** Loads the library of the enabled sources (their chosen categories), if not done yet for them. */
    fun open() {
        // The rows of the last start, shown at once if the library takes a while
        if (lastRows.isEmpty()) {
            scope.launch(Dispatchers.IO) {
                val kept = settings.vodSnapshots.rows()
                if (lastRows.isEmpty()) lastRows = kept
            }
        }
        val configs = settings.enabledConfigs.value
        val selections = settings.vodSelections.value
        if (configs == loadedFor && selections.loaded() == loadedSelections.loaded() && (_library.value.loaded || loadJob?.isActive == true)) return
        load(configs, force = false)
    }

    /** Categories of movies and series of a source, to choose what to load. */
    suspend fun categories(config: SourceConfig): List<VodCategory> = when (config.type) {
        SourceType.XTREAM -> XtreamVod.categoriesOf(config)
        SourceType.M3U -> M3uVod.categoriesOf(config)
        SourceType.DISPATCHARR -> DispatcharrVod.categoriesOf(dispatcharr(config))
    }

    /** Categories of [config] chosen: its catalog is loaded again with them only. */
    fun choose(config: SourceConfig, selection: VodSelection) {
        settings.setVodSelection(config.id, selection)
        newCategories = newCategories - config.id
        // The catalog of the device was loaded for another choice
        scope.launch(Dispatchers.IO) { settings.clearVodCatalog(config.id) }
        catalogs.remove(config.id)
        catalogStamps.remove(config.id)
        builtFrom = null
        load(settings.enabledConfigs.value, force = false, forced = setOf(config.id))
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
        loadedSelections = emptyMap()
        catalogs.clear()
        catalogStamps.clear()
        keptLibrary = null
        builtFrom = null
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

    private fun load(configs: List<SourceConfig>, force: Boolean, forced: Set<String> = emptySet()) {
        loadJob?.cancel()
        val selections = settings.vodSelections.value
        loadedFor = configs
        loadedSelections = selections
        // Only the sources whose categories are chosen; the others wait for the choice
        val chosen = configs.filter { config -> selections[config.id]?.let { it.all || it.keys.isNotEmpty() } == true }
        toChoose = configs.filter { it.id !in selections }
        synchronized(details) { details.clear() }
        catalogs.keys.retainAll(chosen.map { it.id }.toSet())
        catalogStamps.keys.retainAll(chosen.map { it.id }.toSet())
        chosenIds = chosen.map { it.id }.toSet()
        errors.clear()
        if (chosen.isEmpty()) {
            // Nothing to load until the categories are chosen
            loadJob = null
            builtFrom = null
            _library.value = VodLibraryState(loaded = true, toChoose = toChoose)
            return
        }
        _library.value = _library.value.copy(loading = true, error = null, toChoose = toChoose)
        loadJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                showKeptLibrary(chosen)
                coroutineScope {
                    chosen.map { config ->
                        async { loadSource(config, force || config.id in forced, selections.getValue(config.id)) }
                    }.awaitAll()
                }
            } finally {
                if (loadJob === coroutineContext[Job]) {
                    publish(loading = false)
                    Log.i(TAG, "Library of ${catalogs.values.sumOf { it.size }} entries in ${SystemClock.elapsedRealtime() - startedAt} ms")
                    startTmdbWork()
                    prepareHome()
                    checkNewCategories(chosen)
                }
            }
        }
    }

    /**
     * Categories added to [sources] since they were chosen, that look like the chosen ones: suggested
     * until chosen or dismissed. A choice made before the categories were recorded records them now.
     */
    private fun checkNewCategories(sources: List<SourceConfig>) {
        scope.launch {
            val found = HashMap<String, List<VodCategory>>()
            sources.forEach { config ->
                val selection = settings.vodSelections.value[config.id] ?: return@forEach
                // Every category loaded, or M3U (its categories are read from the whole playlist)
                if (selection.all || selection.keys.isEmpty() || config.type == SourceType.M3U) return@forEach
                val categories = try {
                    categories(config)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@forEach
                }
                if (selection.known.isEmpty()) {
                    remember(config.id, selection.copy(known = categories.mapTo(LinkedHashSet()) { it.key }))
                    return@forEach
                }
                val suggested = withContext(Dispatchers.Default) { VodCategories.suggestions(categories, selection) }
                if (suggested.isNotEmpty()) found[config.id] = suggested
            }
            newCategories = found
            _library.value = _library.value.copy(newCategories = found)
        }
    }

    /** The new categories suggested for [configId] are not suggested again. */
    fun dismissNewCategories(configId: String) {
        val suggested = newCategories[configId] ?: return
        val selection = settings.vodSelections.value[configId] ?: return
        remember(configId, selection.copy(known = selection.known + suggested.map { it.key }))
        newCategories = newCategories - configId
        _library.value = _library.value.copy(newCategories = newCategories)
    }

    /** Saves [selection], which changes nothing to load (the categories known only). */
    private fun remember(configId: String, selection: VodSelection) {
        settings.setVodSelection(configId, selection)
        loadedSelections = loadedSelections + (configId to selection)
    }

    // Selections as far as loading goes (the categories known do not change what is loaded)
    private fun Map<String, VodSelection>.loaded() = mapValues { it.value.copy(known = emptySet()) }

    /** With a TMDB key: rankings (when stale) and the indexing of the titles not known yet. */
    private fun startTmdbWork() {
        val key = settings.tmdbKey.value.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        if (rankingsJob?.isActive != true && (now - rankingsAt > RANKINGS_MAX_AGE_MS || _rankings.value.isEmpty())) {
            rankingsJob = scope.launch {
                if (_platformLogos.value.isEmpty()) {
                    launch {
                        VodRankings.platformLogos(key).takeIf { it.isNotEmpty() }?.let { _platformLogos.value = it }
                    }
                }
                // Kept on the device by a recent start: no need to fetch them again
                val name = listName("rankings")
                if (_rankings.value.isEmpty()) {
                    keptList(name)?.let { entry ->
                        val kept = entry.lists.mapNotNull { (list, items) -> Ranking.entries.firstOrNull { it.name == list }?.let { it to items } }.toMap()
                        if (kept.values.any { it.isNotEmpty() }) {
                            _rankings.value = kept
                            rankingsAt = entry.fetchedAt
                            prepareHome()
                            return@launch
                        }
                    }
                }
                val fetched = VodRankings.fetch(key)
                if (fetched.values.any { it.isNotEmpty() }) {
                    _rankings.value = fetched
                    prepareHome()
                    rankingsAt = System.currentTimeMillis()
                    keepList(name, fetched.mapKeys { it.key.name }, rankingsAt)
                }
            }
        }
        indexJob?.cancel()
        // Ultra light: no TMDB index in the background (the facts already kept, and the ones of the server, stay)
        if (io.github.sim7b.tele.data.model.UltraLight.active) return
        val titles = _library.value.titles
        indexJob = scope.launch { index.index(titles, key) }
    }

    private suspend fun loadSource(config: SourceConfig, forced: Boolean, chosen: VodSelection) {
        val readStart = SystemClock.elapsedRealtime()
        val stored = withContext(Dispatchers.IO) { settings.getVodCatalog(config.id) }
        // Dispatcharr: only the groups its admin serves to the TVs (Télé API plugin); unknown (no plugin, error):
        // nothing hidden. A change of these groups loads the catalog again.
        var selection = chosen
        var force = forced
        var groupsStamp: String? = null
        var allowed: Set<String>? = null
        if (config.type == SourceType.DISPATCHARR) {
            val groups = try {
                dispatcharr(config).getVodGroups()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (groups != null) {
                allowed = if (groups.all) null else groups.groups
                selection = DispatcharrVod.restrict(chosen, allowed)
                groupsStamp = DispatcharrVod.groupsStamp(allowed)
                if (groupsStamp != withContext(Dispatchers.IO) { settings.getVodGroupsStamp(config.id) }) force = true
            }
        }
        // The groups no longer served disappear at once
        val served = allowed
        val cached = if (stored == null || served == null) stored else CachedVodCatalog(
            stored.savedAt, stored.entries.filter { DispatcharrVod.allows(served, it) }, stored.fullAt
        )
        Log.i(TAG, "Catalog of a ${config.type} source read from the device: ${cached?.entries?.size ?: 0} entries in ${SystemClock.elapsedRealtime() - readStart} ms")
        if (cached != null) {
            catalogs[config.id] = cached.entries
            catalogStamps[config.id] = cached.savedAt to cached.fullAt
            // Already shown by the library kept on the device
            if (keptLibrary == null) publish(loading = true)
        }
        val fresh = cached != null && System.currentTimeMillis() - cached.savedAt in 0..MAX_AGE_MS
        if (config.type == SourceType.DISPATCHARR) refreshMeasuredLanguages(config)
        if (fresh && !force) return
        try {
            // Dispatcharr with the Télé API plugin: its catalog, ready (one answer per kind, then only the changes)
            if (config.type == SourceType.DISPATCHARR && loadFromServerCatalog(config, force, selection, cached, groupsStamp)) return
            // Dispatcharr, loaded fully less than a week ago: only the titles added since (a page per list);
            // a full load now and then drops the titles removed from the server
            val now = System.currentTimeMillis()
            if (!force && cached != null && config.type == SourceType.DISPATCHARR && now - cached.fullAt in 0..FULL_LOAD_MAX_AGE_MS) {
                val added = DispatcharrVod.loadAdded(config, dispatcharr(config), selection, cached.savedAt - ADDED_MARGIN_MS)
                if (added != null) {
                    val merged = DispatcharrVod.merge(cached.entries, added)
                    catalogs[config.id] = merged
                    catalogStamps[config.id] = now to cached.fullAt
                    errors.remove(config.id)
                    if (added.isNotEmpty()) publish(loading = true)
                    withContext(Dispatchers.IO) { settings.saveVodCatalog(config.id, now, merged, fullAt = cached.fullAt) }
                    return
                }
            }
            // Built again at most every few seconds: rebuilding tens of thousands of titles for every page
            // of a first load would cost far more than the load itself
            var lastPartialPublish = 0L
            suspend fun progress(partial: List<VodEntry>) {
                // First load (nothing on the device yet): the library fills as the pages arrive
                if (cached == null && partial.isNotEmpty()) {
                    catalogs[config.id] = partial
                    catalogStamps.remove(config.id)
                    val now = SystemClock.elapsedRealtime()
                    // Not more than a fifth of the time spent building: the load keeps the processor
                    if (now - lastPartialPublish >= maxOf(PARTIAL_PUBLISH_MS, lastBuildMs * 4)) {
                        lastPartialPublish = now
                        publish(loading = true)
                    }
                }
            }
            val entries = when (config.type) {
                SourceType.XTREAM -> XtreamVod.loadCatalog(config, selection) { progress(it) }
                SourceType.M3U -> M3uVod.loadCatalog(config, selection)
                SourceType.DISPATCHARR -> if (selection.all) {
                    DispatcharrVod.loadCatalog(config, dispatcharr(config)) { progress(it) }.let { DispatcharrVod.keepGroups(it, cached?.entries) }
                } else {
                    // Chosen categories: each title comes with its category, no second pass for the groups
                    DispatcharrVod.loadCategories(config, dispatcharr(config), selection) { progress(it) }
                }
            }
            catalogs[config.id] = entries
            errors.remove(config.id)
            groupsStamp?.let { stamp -> withContext(Dispatchers.IO) { settings.setVodGroupsStamp(config.id, stamp) } }
            val savedAt = System.currentTimeMillis()
            catalogStamps[config.id] = savedAt to savedAt
            if (config.type == SourceType.DISPATCHARR && selection.all) {
                // The groups (languages) come after: shown before them only when the device had no catalog
                // (otherwise the one of the device stays shown, and the library is built once, at the end)
                if (cached == null) publish(loading = true)
                withContext(Dispatchers.IO) { settings.saveVodCatalog(config.id, savedAt, entries) }
                DispatcharrVod.withGroups(dispatcharr(config), entries)?.let { grouped ->
                    catalogs[config.id] = grouped
                    withContext(Dispatchers.IO) { settings.saveVodCatalog(config.id, savedAt, grouped) }
                }
                publish(loading = true)
            } else {
                publish(loading = true)
                withContext(Dispatchers.IO) { settings.saveVodCatalog(config.id, savedAt, entries) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The catalog of the device stays shown
            Log.w(TAG, "Catalog of a ${config.type} source unavailable: ${e.javaClass.simpleName}")
            errors[config.id] = UiText.Res(R.string.error_with_source, config.name, e.toUiText())
        }
    }

    /**
     * Loads the catalog of [config] from its Télé API plugin: whole when [force]d, when the device has none or
     * every week (titles removed from the server), else only the titles changed since the last answer. False
     * when the plugin does not serve it: the standard API is used.
     */
    private suspend fun loadFromServerCatalog(
        config: SourceConfig,
        force: Boolean,
        selection: VodSelection,
        cached: CachedVodCatalog?,
        groupsStamp: String?
    ): Boolean {
        val now = System.currentTimeMillis()
        val until = withContext(Dispatchers.IO) { settings.getVodCatalogUntil(config.id) }
        val base = cached?.takeIf { !force && until > 0 && now - it.fullAt in 0..FULL_LOAD_MAX_AGE_MS }
        val (found, next) = DispatcharrVod.loadServerCatalog(config, dispatcharr(config), selection, if (base != null) until else 0.0)
            ?: return false
        val entries = if (base != null) DispatcharrVod.merge(base.entries, found) else found
        val fullAt = base?.fullAt ?: now
        catalogs[config.id] = entries
        catalogStamps[config.id] = now to fullAt
        errors.remove(config.id)
        if (base == null || found.isNotEmpty()) publish(loading = true)
        withContext(Dispatchers.IO) {
            groupsStamp?.let { settings.setVodGroupsStamp(config.id, it) }
            if (next > 0) settings.setVodCatalogUntil(config.id, next)
            settings.saveVodCatalog(config.id, now, entries, fullAt = fullAt)
        }
        return true
    }

    /** The languages of [title] come from a measure on the server (Télé API plugin) for one of its files. */
    fun languagesMeasured(title: VodTitle): Boolean = measuredLanguages.isNotEmpty() && title.entries.any { entry ->
        DispatcharrVod.measuredKey(entry)?.let { measuredLanguages[entry.sourceId]?.containsKey(it) } == true
    }

    /** Languages measured on a Dispatcharr server (Télé API plugin): the library is built again when they change. */
    private suspend fun refreshMeasuredLanguages(config: SourceConfig) {
        val measured = try {
            DispatcharrVod.measuredLanguages(dispatcharr(config))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return
        }
        val ids = try {
            DispatcharrVod.tmdbIds(dispatcharr(config))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            tmdbIds[config.id].orEmpty()
        }
        refreshServerFacts(config)
        val subtitles = try {
            dispatcharr(config).hasServerSubtitles()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            config.id in subtitleSources
        }
        if (subtitles) subtitleSources.add(config.id) else subtitleSources.remove(config.id)
        _serverSubtitles.value = subtitleSources.isNotEmpty()
        val popular = try {
            dispatcharr(config).getVodPopular()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            popularIds[config.id].orEmpty()
        }
        if (popular != popularIds[config.id].orEmpty()) {
            if (popular.isEmpty()) popularIds.remove(config.id) else popularIds[config.id] = popular
            updateServerPopular()
        }
        refreshFresh(config)
        if (measured == measuredLanguages[config.id].orEmpty() && ids == tmdbIds[config.id].orEmpty()) return
        if (measured.isEmpty()) measuredLanguages.remove(config.id) else measuredLanguages[config.id] = measured
        if (ids.isEmpty()) tmdbIds.remove(config.id) else tmdbIds[config.id] = ids
        if (catalogs[config.id] != null) publish(loading = _library.value.loading)
    }

    /**
     * What is new on a Dispatcharr server: the titles added this week, and the latest episode of the series followed on
     * this TV (in my list or being watched); the rows of the home are made again when it changed.
     */
    private suspend fun refreshFresh(config: SourceConfig) {
        val titles = _library.value.titles
        if (titles.isEmpty()) return
        val store = settings.vodStore
        val followedKeys = store.myList.value.toHashSet().apply { store.progress.value.values.forEach { add(it.titleKey) } }
        val seriesIds = titles.asSequence()
            .filter { it.kind == VodKind.SERIES && it.key in followedKeys }
            .flatMap { title -> title.entries.asSequence().filter { it.sourceId == config.id && it.kind == VodKind.SERIES } }
            .map { it.providerId }
            .distinct().take(100).toList()
        val fresh = try {
            dispatcharr(config).getVodFresh(seriesIds)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return
        if (freshBySource[config.id] == fresh) return
        freshBySource[config.id] = fresh
        withContext(Dispatchers.Default) {
            val byEntry = HashMap<String, String>()
            titles.forEach { title ->
                title.entries.forEach { entry ->
                    byEntry["${entry.sourceId}|${if (entry.kind == VodKind.MOVIE) "movie" else "series"}|${entry.providerId}"] = title.key
                }
            }
            serverAdded = freshBySource.entries
                .flatMap { (source, f) -> f.added.mapIndexed { rank, (kind, id) -> Triple(byEntry["$source|$kind|$id"], rank, source) } }
                .filter { it.first != null }
                .sortedBy { it.second }
                .mapNotNull { it.first }
                .distinct()
            val episodes = HashMap<String, TeleApi.NewEpisode>()
            freshBySource.forEach { (source, f) ->
                f.episodes.forEach { (id, episode) ->
                    val key = byEntry["$source|series|$id"] ?: return@forEach
                    if ((episodes[key]?.atMs ?: 0L) < episode.atMs) episodes[key] = episode
                }
            }
            newEpisodes = episodes
        }
        prepareHome()
    }

    /** Keys of the library titles most watched on the servers: their Dispatcharr ids found among the entries. */
    private suspend fun updateServerPopular() {
        val wanted = popularIds.entries.flatMap { (source, list) -> list.mapIndexed { rank, (kind, id) -> Triple("$source|$kind|$id", rank, source) } }
        if (wanted.isEmpty()) {
            _serverPopular.value = emptyList()
            return
        }
        val titles = _library.value.titles
        _serverPopular.value = withContext(Dispatchers.Default) {
            val rank = wanted.associate { it.first to it.second }
            val found = HashMap<String, Int>()
            titles.forEach { title ->
                title.entries.forEach { entry ->
                    val kind = if (entry.kind == VodKind.MOVIE) "movie" else "series"
                    rank["${entry.sourceId}|$kind|${entry.providerId}"]?.let { r -> found[title.key] = minOf(found[title.key] ?: r, r) }
                }
            }
            found.entries.sortedBy { it.value }.map { it.key }
        }
    }

    private suspend fun publish(loading: Boolean) {
        val order = loadedFor.map { it.id }
        val sources = order.map { catalogs[it].orEmpty() }
        val hideTheater = settings.vodDisplay.value.hideTheater
        val measured = order.mapNotNull { id -> measuredLanguages[id]?.let { id to it } }.toMap()
        val ids = order.mapNotNull { id -> tmdbIds[id]?.let { id to it } }.toMap()
        val built = builtFrom
        if (built != null && built.second == hideTheater && built.first.size == sources.size && measured == builtMeasured &&
            ids == builtTmdbIds &&
            built.first.indices.all { built.first[it] === sources[it] }
        ) {
            _library.value = _library.value.copy(
                loading = loading,
                error = order.firstNotNullOfOrNull { errors[it] },
                loaded = !loading || _library.value.titles.isNotEmpty()
            )
            return
        }
        // The library kept on the device for these very catalogs: not built again
        val signature = librarySignature(order.filter { it in chosenIds }, hideTheater, catalogStamps)
            ?.let { if (measured.isEmpty()) it else it + "|m" + measured.hashCode() }
            ?.let { if (ids.isEmpty()) it else it + "|t" + ids.hashCode() }
        val kept = keptLibrary?.takeIf { signature != null && it.first == signature }?.second
        val buildStart = SystemClock.elapsedRealtime()
        val (titles, byKey, featured) = withContext(Dispatchers.Default) {
            val titles = kept ?: VodLibrary.build(sources.flatten(), measured, ids).let { all ->
                if (hideTheater) all.filterNot { VodDisplay.isTheaterCopy(it) } else all
            }
            Triple(titles, titles.associateBy { it.key }, VodRows.featured(titles))
        }
        if (kept == null) {
            lastBuildMs = SystemClock.elapsedRealtime() - buildStart
            Log.i(TAG, "Library of ${titles.size} titles built in $lastBuildMs ms")
            if (signature != null) keepLibrary(signature, titles)
        }
        builtFrom = sources to hideTheater
        builtMeasured = measured
        builtTmdbIds = ids
        val popularStale = popularIds.isNotEmpty()
        _library.value = VodLibraryState(
            titles = titles,
            byKey = byKey,
            featured = featured,
            loading = loading,
            error = order.firstNotNullOfOrNull { errors[it] },
            loaded = !loading || titles.isNotEmpty(),
            toChoose = toChoose,
            newCategories = newCategories
        )
        if (popularStale) updateServerPopular()
        applyServerFacts()
    }

    // Dispatcharr sources: VOD languages measured by the Télé API plugin, by category (DispatcharrVod.measuredKey)
    private val measuredLanguages = ConcurrentHashMap<String, Map<String, VodLanguages>>()
    private var builtMeasured: Map<String, Map<String, VodLanguages>> = emptyMap()
    // Dispatcharr sources: TMDB ids found by the plugin for the titles Dispatcharr has none for
    private val tmdbIds = ConcurrentHashMap<String, Map<String, String>>()
    private var builtTmdbIds: Map<String, Map<String, String>> = emptyMap()

    // The sources whose catalogs are loaded (their categories chosen)
    @Volatile
    private var chosenIds: Set<String> = emptySet()
    // When each catalog in memory was saved and fully loaded (absent: not saved as it is)
    private val catalogStamps = ConcurrentHashMap<String, Pair<Long, Long>>()
    // The library kept on the device, with the catalogs it was grouped from
    @Volatile
    private var keptLibrary: Pair<String, List<VodTitle>>? = null
    private var librarySave: Job? = null

    private fun keepLibrary(signature: String, titles: List<VodTitle>) {
        if (keptLibrary?.first == signature) return
        keptLibrary = signature to titles
        librarySave?.cancel()
        librarySave = scope.launch(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            settings.vodSnapshots.saveLibrary(signature, titles)
            Log.i(TAG, "Library kept on the device in ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

    /**
     * The library kept on the device, shown before the catalogs are read when they are still the ones it was
     * grouped from (their start read only).
     */
    private suspend fun showKeptLibrary(chosen: List<SourceConfig>) {
        if (_library.value.titles.isNotEmpty()) return
        val start = SystemClock.elapsedRealtime()
        val (signature, titles) = withContext(Dispatchers.IO) {
            val stamps = chosen.mapNotNull { config -> settings.getVodCatalogStamp(config.id)?.let { config.id to it } }.toMap()
            val signature = librarySignature(loadedFor.map { it.id }.filter { it in chosenIds }, settings.vodDisplay.value.hideTheater, stamps)
            signature to signature?.let { settings.vodSnapshots.library(it) }
        }
        if (signature == null || titles.isNullOrEmpty() || _library.value.titles.isNotEmpty()) return
        keptLibrary = signature to titles
        val (byKey, featured) = withContext(Dispatchers.Default) { titles.associateBy { it.key } to VodRows.featured(titles) }
        _library.value = VodLibraryState(
            titles = titles,
            byKey = byKey,
            featured = featured,
            loading = true,
            loaded = true,
            toChoose = toChoose,
            newCategories = newCategories
        )
        Log.i(TAG, "Library of ${titles.size} titles read from the device in ${SystemClock.elapsedRealtime() - start} ms")
    }

    private fun dispatcharr(config: SourceConfig): DispatcharrClient =
        dispatcharrClients.getOrPut(config.id) { DispatcharrClient(config) }.also { it.setConfig(config) }

    // Matching of the TMDB rankings with the library (a second on a TV): kept for the same titles, rankings
    // and index version, prepared in the background so that the home opens with its rows
    private var matcherFor: Triple<List<VodTitle>, Map<Ranking, List<RankedItem>>, Int>? = null
    private var matcherValue: VodRankings.Matcher? = null

    /** Rows of the home last built: shown at once when the home opens again, then updated. */
    @Volatile
    var lastRows: List<VodRow> = emptyList()

    // What [lastRows] were built from: built again only when one of them changed
    private var lastRowsInputs: RowsInputs? = null

    // The library and the rankings compared by identity (tens of thousands of titles), the rest by value (small)
    private class RowsInputs(val titles: List<VodTitle>, val rankings: Map<Ranking, List<RankedItem>>, val rest: List<Any?>) {
        fun same(other: RowsInputs) = titles === other.titles && rankings === other.rankings && rest == other.rest
    }

    /**
     * Rows of the home for the current library: the ones last built when nothing changed since (opening the
     * movies or series again, back from a sheet), else built off the main thread and kept.
     */
    suspend fun rows(
        progress: Map<String, VodProgress>,
        myList: List<String>,
        collections: List<VodCollection>,
        hidden: Set<VodRow.Kind>
    ): List<VodRow> {
        val titles = _library.value.titles
        val rankings = _rankings.value
        val popular = _serverPopular.value
        val added = serverAdded
        val episodes = newEpisodes
        val inputs = RowsInputs(titles, rankings, listOf(progress, myList, collections, hidden, index.version.value, popular, added, episodes))
        synchronized(this) { if (lastRowsInputs?.same(inputs) == true) return lastRows }
        return withContext(Dispatchers.Default) {
            VodRows.build(titles, progress, myList, collections, rankings, matcher(titles, rankings), hidden, popular, added, episodes)
                .also { built ->
                    synchronized(this@VodRepository) {
                        lastRows = built
                        lastRowsInputs = inputs
                    }
                }
        }
    }

    // The Movies and Series tabs (their titles filtered, rows and filter values), kept for the same library,
    // tab, filter and index version: they open at once again, even back from a sheet
    private class BrowseKey(val titles: List<VodTitle>, val kind: VodKind, val filter: VodFilter, val version: Int) {
        override fun equals(other: Any?) =
            other is BrowseKey && other.titles === titles && other.kind == kind && other.filter == filter && other.version == version

        override fun hashCode() = ((System.identityHashCode(titles) * 31 + kind.hashCode()) * 31 + filter.hashCode()) * 31 + version
    }

    private val browseLock = Any()
    private val browseViews = object : LinkedHashMap<BrowseKey, VodBrowseView>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BrowseKey, VodBrowseView>?) = size > MAX_BROWSE_VIEWS
    }
    private val browsePending = HashMap<BrowseKey, Deferred<VodBrowseView>>()

    private fun browseKey(kind: VodKind, filter: VodFilter) = BrowseKey(_library.value.titles, kind, filter, index.version.value)

    /** The tab of [kind] with [filter] when already prepared, else null (see [browse]). */
    fun cachedBrowse(kind: VodKind, filter: VodFilter): VodBrowseView? = synchronized(browseLock) { browseViews[browseKey(kind, filter)] }

    /**
     * The tab of [kind] with [filter]: prepared once in the background (it goes on when the screen is left, for
     * when it comes back), then kept.
     */
    suspend fun browse(kind: VodKind, filter: VodFilter): VodBrowseView {
        val key = browseKey(kind, filter)
        if (key.titles.isEmpty()) return VodBrowseView()
        val job = synchronized(browseLock) {
            browseViews[key]?.let { return it }
            browsePending.getOrPut(key) {
                scope.async(Dispatchers.Default) {
                    try {
                        val facts = index.all()
                        val listed = VodBrowse.filter(key.titles, kind, filter, facts)
                        VodBrowseView(listed, VodBrowse.shelves(listed, facts), VodBrowse.options(key.titles, kind, facts))
                            .also { view ->
                                synchronized(browseLock) {
                                    // A library loaded again, or new facts: the tabs built before are of no use any more
                                    browseViews.keys.removeAll { it.titles !== key.titles || it.version != key.version }
                                    browseViews[key] = view
                                }
                            }
                    } finally {
                        synchronized(browseLock) { browsePending.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    @Synchronized
    fun matcher(titles: List<VodTitle>, rankings: Map<Ranking, List<RankedItem>>): VodRankings.Matcher? {
        if (rankings.isEmpty() || titles.isEmpty()) return null
        val version = index.version.value
        matcherFor?.let { (builtTitles, builtRankings, builtVersion) ->
            if (builtTitles === titles && builtRankings === rankings && builtVersion == version) return matcherValue
        }
        return VodRankings.Matcher(titles, index.all()).also {
            matcherFor = Triple(titles, rankings, version)
            matcherValue = it
        }
    }

    /** Prepares the matching and the rows of the home off the main thread (after a load, new rankings). */
    private fun prepareHome() {
        scope.launch(Dispatchers.Default) {
            val titles = _library.value.titles
            if (titles.isEmpty()) return@launch
            val store = settings.vodStore
            val display = settings.vodDisplay.value
            val rows = rows(store.progress.value, store.myList.value, store.collections.value, display.hiddenRows)
            // Kept: the next start shows them before its library is ready
            withContext(Dispatchers.IO) { settings.vodSnapshots.saveRows(rows) }
            // The Movies and Series tabs with their default filters, ready before they are opened
            VodKind.entries.forEach { kind -> browse(kind, display.defaults[kind] ?: VodFilter()) }
        }
    }

    fun title(key: String): VodTitle? = _library.value.byKey[key]

    fun cachedDetails(key: String): VodDetails? = synchronized(details) { details[key] }

    // Posters found for the titles without one (a few hundred cards are seen in a session); "" = none
    private val posters = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_POSTERS
    }

    /** Poster of [title] when its providers give none, looked up once (only for the cards shown). */
    suspend fun posterFor(title: VodTitle): String? {
        loadKeptPosters()
        synchronized(posters) { posters[title.key] }?.let { return it.ifEmpty { null } }
        val found = VodMetadata.poster(title, settings.tmdbKey.value.takeIf { it.isNotBlank() })
        synchronized(posters) { posters[title.key] = found.orEmpty() }
        keepPostersSoon()
        return found
    }

    // The posters found by the previous starts, read once; written a moment after the last one found
    private val postersLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var postersLoading: Job? = null
    private var postersSave: Job? = null

    private suspend fun loadKeptPosters() {
        if (postersLoaded.isCompleted) return
        synchronized(posters) {
            if (postersLoading == null) {
                postersLoading = scope.launch(Dispatchers.IO) {
                    val kept = settings.vodSnapshots.posters()
                    synchronized(posters) { kept.forEach { (key, url) -> posters.putIfAbsent(key, url) } }
                    postersLoaded.complete(Unit)
                }
            }
        }
        postersLoaded.await()
    }

    private fun keepPostersSoon() {
        postersSave?.cancel()
        postersSave = scope.launch(Dispatchers.IO) {
            delay(POSTERS_SAVE_DELAY_MS)
            settings.vodSnapshots.savePosters(synchronized(posters) { LinkedHashMap(posters) })
        }
    }

    /**
     * Sheet of [title]: built once, kept on the device and shown at once the next times (built again in the
     * background once a day old, or at once when the sources of the title changed).
     */
    suspend fun details(title: VodTitle): VodDetails {
        cachedDetails(title.key)?.let { return it }
        val kept = withContext(Dispatchers.IO) { settings.vodSnapshots.details(title.key) }
        if (kept != null && sameFiles(kept.details.title, title)) {
            val age = System.currentTimeMillis() - kept.savedAt
            if (age in 0 until DETAILS_MAX_AGE_MS) {
                synchronized(details) { details[title.key] = kept.details }
                if (age >= DETAILS_REFRESH_AGE_MS) scope.launch { runCatching { buildDetails(title) } }
                return kept.details
            }
        }
        return buildDetails(title)
    }

    /** Sheet of [title] built again (a file was being measured on the server: its description may be there). */
    suspend fun refreshDetails(title: VodTitle): VodDetails = buildDetails(title)

    /** The same files of the same sources: a sheet kept for one is still right for the other. */
    private fun sameFiles(kept: VodTitle, current: VodTitle): Boolean =
        kept.entries.map { it.sourceId to it.providerId }.toSet() == current.entries.map { it.sourceId to it.providerId }.toSet()

    /**
     * Sheet of [title]: details of every source that has it (in parallel, each bounded in time), files
     * of the same movie or episode together, completed by TMDB (with a key) or TVmaze (series).
     */
    private suspend fun buildDetails(title: VodTitle): VodDetails {
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
            seasons = seasons(episodes),
            mediaPending = parts.any { it.mediaPending }
        )
        val enrichment = VodMetadata.enrich(merged, episodes.map { it.season }.toSet(), settings.tmdbKey.value.takeIf { it.isNotBlank() })
        val result = enrichment?.let { enrich(base, it, preferEnrichment = settings.tmdbKey.value.isNotBlank()) } ?: base
        synchronized(details) { details[title.key] = result }
        // Kept only when a source answered: a sheet without its files is not worth showing again; nor while a
        // file is being measured (the sheet is built again once it is)
        if ((parts.isNotEmpty() || m3uEntries.isNotEmpty()) && !result.mediaPending) {
            withContext(Dispatchers.IO) { settings.vodSnapshots.saveDetails(title.key, result) }
        }
        return result
    }

    private fun seasons(episodes: List<VodEpisode>): List<VodSeason> =
        episodes.groupBy { it.season }
            .toSortedMap()
            .map { (season, list) -> VodSeason(season, list.sortedBy { it.number }) }

    companion object {
        private const val TAG = "VodRepository"
        // A catalog loaded again from the server once a day (a few minutes of server work), or on request
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val PARTIAL_PUBLISH_MS = 3_000L
        private const val FULL_LOAD_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        // Titles added a little before the last load are asked again: the clocks of the TV and the server differ
        private const val ADDED_MARGIN_MS = 60 * 60 * 1000L
        private const val DETAILS_TIMEOUT_MS = 15_000L
        private const val MAX_DETAILS = 40
        // Tabs kept: movies and series, with a few filters each
        private const val MAX_BROWSE_VIEWS = 8
        private const val MAX_POSTERS = 3_000
        // Sheets kept on the device: shown up to a week, built again in the background past a day
        private const val DETAILS_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        private const val DETAILS_REFRESH_AGE_MS = 24 * 60 * 60 * 1000L
        private const val POSTERS_SAVE_DELAY_MS = 5_000L

        /**
         * What the library was grouped from: the catalogs of [sourceIds] in this order, saved and fully loaded at
         * [stamps], the display setting and the version of the app (another one may group differently). Null when a
         * catalog is not saved as it is in memory.
         */
        internal fun librarySignature(sourceIds: List<String>, hideTheater: Boolean, stamps: Map<String, Pair<Long, Long>>): String? {
            if (sourceIds.isEmpty()) return null
            val parts = sourceIds.map { id -> stamps[id]?.let { "$id:${it.first}:${it.second}" } ?: return null }
            return "${BuildConfig.VERSION_CODE}|$hideTheater|" + parts.joinToString(",")
        }
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
                    // TMDB only (the providers' ratings are not trusted)
                    rating = enrichment.rating,
                    year = title.year ?: enrichment.year,
                    tmdbId = title.tmdbId ?: enrichment.tmdbId,
                    imdbId = title.imdbId ?: enrichment.imdbId
                ),
                durationSecs = if (preferEnrichment) enrichment.durationSecs ?: details.durationSecs else details.durationSecs ?: enrichment.durationSecs,
                director = pick(details.director, enrichment.director),
                cast = if (preferEnrichment) enrichment.cast.ifEmpty { details.cast } else details.cast.ifEmpty { enrichment.cast },
                trailerKey = enrichment.trailerKey ?: details.trailerKey,
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
