package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.BuildConfig
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
        val titles = _library.value.titles
        indexJob = scope.launch { index.index(titles, key) }
    }

    private suspend fun loadSource(config: SourceConfig, force: Boolean, selection: VodSelection) {
        val readStart = SystemClock.elapsedRealtime()
        val cached = withContext(Dispatchers.IO) { settings.getVodCatalog(config.id) }
        Log.i(TAG, "Catalog of a ${config.type} source read from the device: ${cached?.entries?.size ?: 0} entries in ${SystemClock.elapsedRealtime() - readStart} ms")
        if (cached != null) {
            catalogs[config.id] = cached.entries
            catalogStamps[config.id] = cached.savedAt to cached.fullAt
            // Already shown by the library kept on the device
            if (keptLibrary == null) publish(loading = true)
        }
        val fresh = cached != null && System.currentTimeMillis() - cached.savedAt in 0..MAX_AGE_MS
        if (fresh && !force) return
        try {
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

    private suspend fun publish(loading: Boolean) {
        val order = loadedFor.map { it.id }
        val sources = order.map { catalogs[it].orEmpty() }
        val hideTheater = settings.vodDisplay.value.hideTheater
        val built = builtFrom
        if (built != null && built.second == hideTheater && built.first.size == sources.size &&
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
        val kept = keptLibrary?.takeIf { signature != null && it.first == signature }?.second
        val buildStart = SystemClock.elapsedRealtime()
        val (titles, byKey, featured) = withContext(Dispatchers.Default) {
            val titles = kept ?: VodLibrary.build(sources.flatten()).let { all ->
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
    }

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
        } ?: return
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
            val rankings = _rankings.value
            val store = settings.vodStore
            lastRows = VodRows.build(
                titles, store.progress.value, store.myList.value, store.collections.value, rankings,
                matcher(titles, rankings), settings.vodDisplay.value.hiddenRows
            )
            // Kept: the next start shows them before its library is ready
            withContext(Dispatchers.IO) { settings.vodSnapshots.saveRows(lastRows) }
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
            seasons = seasons(episodes)
        )
        val enrichment = VodMetadata.enrich(merged, episodes.map { it.season }.toSet(), settings.tmdbKey.value.takeIf { it.isNotBlank() })
        val result = enrichment?.let { enrich(base, it, preferEnrichment = settings.tmdbKey.value.isNotBlank()) } ?: base
        synchronized(details) { details[title.key] = result }
        // Kept only when a source answered: a sheet without its files is not worth showing again
        if (parts.isNotEmpty() || m3uEntries.isNotEmpty()) {
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
                    rating = pick(title.rating, enrichment.rating),
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
