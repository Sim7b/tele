package io.github.sim7b.tele

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.bouquet.Bouquet
import io.github.sim7b.tele.data.bouquet.BouquetPreference
import io.github.sim7b.tele.data.bouquet.ChannelGrouping
import io.github.sim7b.tele.data.bouquet.ChannelNames
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.failure
import io.github.sim7b.tele.data.model.CategoryMode
import io.github.sim7b.tele.data.model.Categories
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.DispatcharrEpgChannel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.model.ProgramArtwork
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.model.StreamSlots
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.remote.DispatcharrClient
import io.github.sim7b.tele.data.remote.GuideLoader
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.M3uClient
import io.github.sim7b.tele.data.remote.XtreamClient
import io.github.sim7b.tele.data.storage.SettingsRepository
import io.github.sim7b.tele.data.toUiText
import io.github.sim7b.tele.playback.TvPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Main app state: loads channels and guide from every enabled source (Dispatcharr, Xtream Codes, M3U),
 * shown together in the order of the source list, refreshes the current program every 30 s, keeps the
 * selected streams and a local cache per source for an immediate display at startup.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val configRepository = SettingsRepository(application)
    // Connection test from a source form (each loaded Dispatcharr source has its own client)
    private val testClient = DispatcharrClient()

    // Cached channels are read off the main thread at startup (see showCachedChannels)
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<UiText?>(null)
    val loadError: StateFlow<UiText?> = _loadError.asStateFlow()

    // Account first name (welcome message), known at startup when it was saved
    private val _userName = MutableStateFlow(savedUserName(configRepository.enabledConfigs.value))
    val userName: StateFlow<String?> = _userName.asStateFlow()

    // True once the first sync is over (channels, guide and artwork), even when it failed
    private val _initialSyncDone = MutableStateFlow(false)
    val initialSyncDone: StateFlow<Boolean> = _initialSyncDone.asStateFlow()

    // Free / total playback slots of the Dispatcharr provider accounts, all servers together (null: not shown)
    private val _streamSlots = MutableStateFlow<StreamSlots?>(null)
    val streamSlots: StateFlow<StreamSlots?> = _streamSlots.asStateFlow()
    private var slotsJob: Job? = null

    /** One enabled source with its client, channels and guide. Read and written on the main thread. */
    private class LoadedSource(val config: SourceConfig) {
        val dispatcharr: DispatcharrClient? = config.takeIf { it.type == SourceType.DISPATCHARR }?.let { DispatcharrClient(it) }
        var channels: List<Channel> = emptyList()
        var programs: Map<String, List<GuideProgram>> = emptyMap()
        // XMLTV guides of an Xtream / M3U source, kept for the periodic guide reload
        var guideUrls: List<String> = emptyList()
        var error: UiText? = null
        var job: Job? = null
        // Channels not received yet (spinner); done = first load over, guide included, even when it failed
        var loading = false
        var done = false
    }

    private var sources: List<LoadedSource> = emptyList()

    // Displayed channels: names, numbers and categories of the reference bouquet, versions of a same channel
    // grouped, categories cleaned, renamed and merged / separated (see ChannelGrouping and Categories)
    private val _displayChannels = MutableStateFlow<List<Channel>>(emptyList())
    val displayChannels: StateFlow<List<Channel>> = _displayChannels.asStateFlow()
    // Same channels before the renames: the categories a rename applies to
    @Volatile
    private var groupedChannels: List<Channel> = emptyList()
    private val channelNames = ChannelNames(ChannelNames.homeTokens(Locale.getDefault()))
    private val franceBouquet by lazy { Bouquet.france(Locale.getDefault()) }

    private class DisplayInput(
        val channels: List<Channel>,
        val renames: Map<String, String>,
        val mode: CategoryMode,
        val bouquet: BouquetPreference,
        val configs: List<SourceConfig>,
        val favoriteIds: List<String>
    )

    // EPG programs of every source by channel id (ids are unique across sources), to move the current program
    @Volatile
    private var programsByChannelId: Map<String, List<GuideProgram>> = emptyMap()
    // Only the latest publication is applied (several sources may finish at the same time)
    private var publishGeneration = 0

    init {
        viewModelScope.launch {
            configRepository.enabledConfigs.collect { configs -> syncSources(configs) }
        }
        viewModelScope.launch {
            val settings = combine(configRepository.enabledConfigs, configRepository.favoriteIds) { configs, favorites -> configs to favorites }
            combine(
                _channels, configRepository.categoryNames, configRepository.categoryMode, configRepository.bouquet, settings
            ) { channels, renames, mode, bouquet, (configs, favorites) ->
                DisplayInput(channels, renames, mode, bouquet, configs, favorites)
            }.collectLatest { input ->
                _displayChannels.value = withContext(Dispatchers.Default) { buildDisplay(input) }
            }
        }
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(EPG_TICK_MS)
                refreshCurrentPrograms()
                refreshStreamSlots()
                ticks++
                // The guide only covers a limited window (the TV may stay on for days): reload it periodically
                if (ticks % GUIDE_RELOAD_TICKS == 0) {
                    sources.forEach { reloadGuide(it) }
                } else if (ticks % ARTWORK_REFRESH_TICKS == 0) {
                    // Artwork only covers the next hours: renew it regularly
                    sources.filter { it.dispatcharr != null }.forEach { refreshArtwork(it) }
                }
            }
        }
    }

    /**
     * Reloads the Dispatcharr playback slots (cheap request), added up over the enabled servers. Called every
     * 30 s and when the home screen is shown again, since leaving the player frees a slot.
     */
    fun refreshStreamSlots() {
        val clients = sources.mapNotNull { it.dispatcharr }
        if (clients.isEmpty()) {
            _streamSlots.value = null
            return
        }
        if (slotsJob?.isActive == true) return
        slotsJob = viewModelScope.launch {
            val slots = clients.map { async { it.getStreamSlots() } }.awaitAll().filterNotNull()
            _streamSlots.value = slots.takeIf { it.isNotEmpty() }?.let { all ->
                StreamSlots(used = all.sumOf { it.used }, total = all.sumOf { it.total })
            }
        }
    }

    /** Manual refresh: every source is reloaded, including the reference tables cached by the clients. */
    fun refresh() {
        streamsCache.clear()
        sources.forEach { source ->
            source.dispatcharr?.clearCaches()
            load(source)
        }
    }

    private fun sourceOf(channel: Channel): LoadedSource? = sources.firstOrNull { it.config.id == channel.sourceId }

    /** Displayed channels from the loaded ones (off the main thread: tens of thousands of channels). */
    private fun buildDisplay(input: DisplayInput): List<Channel> {
        val bouquet = if (input.bouquet == BouquetPreference.FRANCE) franceBouquet else null
        val sourceNames = input.configs.associate { it.id to it.name }
        val resources = getApplication<Application>().resources
        val grouped = ChannelGrouping(bouquet, bouquet?.names ?: channelNames).build(
            channels = input.channels,
            mergeAcrossSources = input.mode == CategoryMode.MERGE,
            favoriteIds = input.favoriteIds.toSet(),
            sourceNames = sourceNames,
            categoryLabel = { resources.getString(it.labelRes) }
        )
        groupedChannels = grouped
        return Categories.apply(grouped, input.renames, input.mode, sourceNames)
    }

    private fun sourceGroupsOf(category: String): Set<String> = Categories.sourceGroupsOf(
        category,
        groupedChannels,
        configRepository.categoryNames.value,
        configRepository.categoryMode.value,
        configRepository.enabledConfigs.value.associate { it.id to it.name }
    )

    fun isCategoryRenamed(category: String): Boolean =
        sourceGroupsOf(category).any { it in configRepository.categoryNames.value }

    /** Renames displayed category [category] ([name] null or blank: back to its own name); it stays hidden if it was. */
    fun renameCategory(category: String, name: String?) {
        val groups = sourceGroupsOf(category)
        configRepository.renameCategory(groups, name)
        val grouped = groupedChannels
        val sourceNames = configRepository.enabledConfigs.value.associate { it.id to it.name }
        val renamed = Categories.apply(grouped, configRepository.categoryNames.value, configRepository.categoryMode.value, sourceNames)
        val newNames = grouped.indices.filter { grouped[it].group in groups }.mapTo(HashSet()) { renamed[it].group }
        configRepository.replaceHiddenGroup(category, newNames)
    }

    /** Streams available for a channel (loaded on demand, when the menu opens). */
    suspend fun loadStreams(channel: Channel): Result<List<StreamSource>> {
        // Grouped channel: its versions ("TF1 HD", "TF1 FHD"...), by quality preference
        if (channel.memberIds.size > 1) return Result.success(configRepository.qualityPreference.value.sort(channel.availableStreams))
        // Xtream: TS / HLS variants already known
        if (channel.availableStreams.isNotEmpty()) return Result.success(channel.availableStreams)
        // M3U: a single stream per playlist entry
        val client = sourceOf(channel)?.dispatcharr ?: return Result.success(emptyList())
        val serverId = channel.serverId
            ?: return failure(R.string.error_sync_channels_for_streams)
        return client.getChannelStreams(serverId).onSuccess { streams ->
            streamsCache[streamsKey(channel, serverId)] = SystemClock.elapsedRealtime() to streams
            freshSelection(channel.id, streams)?.let { selectStream(channel, it) }
        }.map { configRepository.qualityPreference.value.sort(it) }
    }

    // Streams of the Dispatcharr channels recently opened (source/server id -> time, streams)
    private val streamsCache = ConcurrentHashMap<String, Pair<Long, List<StreamSource>>>()

    private fun streamsKey(channel: Channel, serverId: String) = "${channel.sourceId}/$serverId"

    /**
     * URLs to play for [channel] in automatic mode, by quality preference: its streams through the Dispatcharr
     * proxy, best first, then the channel itself (Dispatcharr failover) as a last resort. Empty when the
     * server order applies (manual choice, other source, "server order" preference, streams unavailable).
     */
    suspend fun automaticStreamUrls(channel: Channel): List<String> {
        val preference = configRepository.qualityPreference.value
        if (channel.selectedStreamUrl != null) return emptyList()
        // Grouped channel: every version, preferred quality first (the next ones if it fails)
        if (channel.memberIds.size > 1) return preference.sort(channel.availableStreams).map { it.url }
        if (!preference.reordersStreams) return emptyList()
        val client = sourceOf(channel)?.dispatcharr ?: return emptyList()
        val serverId = channel.serverId ?: return emptyList()
        val key = streamsKey(channel, serverId)
        val cached = streamsCache[key]?.takeIf { SystemClock.elapsedRealtime() - it.first < STREAMS_CACHE_MS }?.second
        val streams = cached ?: withTimeoutOrNull(STREAMS_TIMEOUT_MS) {
            client.getChannelStreams(serverId).getOrNull()
        }?.also { streamsCache[key] = SystemClock.elapsedRealtime() to it }
        if (streams.isNullOrEmpty()) return emptyList()
        return preference.sort(streams).map { it.url } + channel.streamUrl
    }

    /**
     * Up-to-date version of the stream selected for a channel, when its URL changed (provider token,
     * new Xtream password): the selection follows its stream id. Null when there is nothing to update.
     */
    private fun freshSelection(channelId: String, streams: List<StreamSource>): StreamSource? {
        val selected = configRepository.selectedStreams.value[channelId] ?: return null
        val fresh = streams.firstOrNull { it.id != null && it.id == selected.id } ?: return null
        return fresh.takeIf { it.url != selected.url }
    }

    /**
     * Stream selection of a channel, kept across sessions. Null [stream] (or without id) = automatic mode.
     */
    fun selectStream(channel: Channel, stream: StreamSource?) {
        configRepository.setSelectedStream(channel.id, stream?.takeIf { it.id != null })
        _channels.update { withSelectedStreams(it) }
    }

    /** Connection test from a source form (success message or readable error). */
    suspend fun testConnection(config: SourceConfig): Result<UiText> = when (config.type) {
        SourceType.DISPATCHARR -> testClient.testConnection(config)
            .map { count -> UiText.Plural(R.plurals.test_dispatcharr_success, count, count) }
        SourceType.XTREAM -> XtreamClient.authenticate(config).map { account ->
            val resources = getApplication<Application>().resources
            UiText.Raw(
                listOfNotNull(
                    resources.getString(R.string.test_xtream_account_active),
                    account.expiresAtMillis?.let {
                        resources.getString(R.string.test_xtream_expires, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)))
                    },
                    account.maxConnections?.let { resources.getQuantityString(R.plurals.test_xtream_max_connections, it, it) }
                ).joinToString(" · ")
            )
        }
        SourceType.M3U -> M3uClient.probe(config)
    }

    /**
     * Earlier versions saved the provider URL of a chosen Dispatcharr stream, played without
     * Dispatcharr (no stats, no connection limits): those selections go back to automatic mode.
     */
    private fun dropDirectSelections(channels: List<Channel>, client: DispatcharrClient) {
        val selections = configRepository.selectedStreams.value
        channels.forEach { channel ->
            val selected = selections[channel.id] ?: return@forEach
            if (!client.isProxyUrl(selected.url)) configRepository.setSelectedStream(channel.id, null)
        }
    }

    private fun withSelectedStreams(channels: List<Channel>): List<Channel> {
        val selections = configRepository.selectedStreams.value
        return channels.map { channel ->
            val selected = selections[channel.id]
            if (selected?.url == channel.selectedStreamUrl && selected?.name == channel.selectedStreamName) {
                channel
            } else {
                channel.copy(selectedStreamUrl = selected?.url, selectedStreamName = selected?.name)
            }
        }
    }

    /**
     * Follows the enabled sources: an unchanged source keeps its channels, a new or edited one shows its
     * cache then loads, a disabled one disappears right away.
     */
    private fun syncSources(configs: List<SourceConfig>) {
        val previous = sources
        val next = configs.map { config -> previous.firstOrNull { it.config == config } ?: LoadedSource(config) }
        previous.filter { old -> next.none { it === old } }.forEach { it.job?.cancel() }
        sources = next
        streamsCache.clear()
        _userName.value = savedUserName(configs)

        slotsJob?.cancel()
        _streamSlots.value = null
        refreshStreamSlots()

        if (next.isEmpty()) {
            programsByChannelId = emptyMap()
            _channels.value = emptyList()
            _isLoading.value = false
            _loadError.value = null
            _initialSyncDone.value = true
            return
        }

        val added = next.filter { old -> previous.none { it === old } }
        viewModelScope.launch { publishAll() }
        added.forEach { source ->
            showCachedChannels(source)
            load(source)
        }
    }

    private fun load(source: LoadedSource) {
        // A new load replaces the previous one (prevents an old response from overwriting the latest one)
        source.job?.cancel()
        source.loading = true
        source.error = null
        updateLoadingState()
        source.job = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                when (source.config.type) {
                    SourceType.DISPATCHARR -> loadDispatcharr(source, startedAt)
                    SourceType.XTREAM, SourceType.M3U -> loadPlaylistSource(source, startedAt)
                }
            } finally {
                // A load replaced by a newer one (cancelled) leaves the state to it
                if (source.job === coroutineContext.job) {
                    source.loading = false
                    source.done = true
                    updateLoadingState()
                }
            }
        }
    }

    private fun updateLoadingState() {
        val current = sources
        _isLoading.value = current.any { it.loading }
        if (current.all { it.done }) _initialSyncDone.value = true
    }

    /** Shows the cached channels of [source] while it loads, unless the load has already published its own. */
    private fun showCachedChannels(source: LoadedSource) {
        viewModelScope.launch {
            val id = source.config.id
            val cached = withContext(Dispatchers.IO) {
                configRepository.getCachedChannels(id).map { it.copy(sourceId = id) }
            }
            if (cached.isEmpty() || source.channels.isNotEmpty() || sources.none { it === source }) return@launch
            source.channels = cached
            publishAll()
        }
    }

    private suspend fun loadDispatcharr(source: LoadedSource, startedAt: Long) = coroutineScope {
        val client = source.dispatcharr ?: return@coroutineScope
        val config = source.config
        // Account first name, in parallel: does not delay the channel load
        launch {
            client.getCurrentUser()?.let { (firstName, username) ->
                displayFirstName(firstName, username)?.let { saveUserName(config, it) }
            }
        }

        // Program artwork in parallel with channels and guide: it only depends on the time
        val now = System.currentTimeMillis()
        val artworkDeferred = async { client.getProgramArtwork(now, now + ARTWORK_WINDOW_MS) }
        val epgDeferred = async { client.getEpgGrid() }
        val channelsResult = client.getChannels()
        val epgResult = epgDeferred.await()
        Log.i(TvPlayerManager.PERF_TAG, "Load: channels + guide in ${SystemClock.elapsedRealtime() - startedAt} ms")

        channelsResult.onSuccess { dChannels ->
            val channels = withContext(Dispatchers.Default) { dChannels.map { it.toChannel().copy(sourceId = config.id) } }
            dropDirectSelections(channels, client)
            source.programs = matchDispatcharrGuide(channels, epgResult.getOrNull().orEmpty(), client)
            source.channels = channels
            source.error = epgResult.exceptionOrNull()?.let { UiText.Res(R.string.error_guide_unavailable, it.toUiText()) }
            publishAll()
            source.loading = false
            updateLoadingState()

            applyArtwork(source, artworkDeferred.await())
            Log.i(TvPlayerManager.PERF_TAG, "Load: artwork included in ${SystemClock.elapsedRealtime() - startedAt} ms")
            saveCache(source)
        }.onFailure { error ->
            artworkDeferred.cancel()
            source.error = UiText.Res(R.string.error_load_channels, error.toUiText())
            publishAll()
        }
    }

    /**
     * Xtream Codes and M3U: channels shown as soon as received, then the XMLTV guide (often large).
     */
    private suspend fun loadPlaylistSource(source: LoadedSource, startedAt: Long) {
        val config = source.config
        val result = when (config.type) {
            SourceType.XTREAM -> XtreamClient.loadChannels(config)
            else -> M3uClient.loadChannels(config)
        }
        result.onSuccess { loaded ->
            loaded.userName?.let { displayFirstName(null, it) }?.let { saveUserName(config, it) }
            source.guideUrls = loaded.guideUrls
            // The guide already loaded for this source stays shown while the new one downloads
            source.channels = withContext(Dispatchers.Default) { loaded.channels.map { it.copy(sourceId = config.id) } }
            publishAll()
            source.loading = false
            updateLoadingState()
            Log.i(TvPlayerManager.PERF_TAG, "Load: ${loaded.channels.size} channels in ${SystemClock.elapsedRealtime() - startedAt} ms")

            val guide = GuideLoader.load(loaded.guideUrls, source.channels, userAgent(config))
            // A guide that failed to download does not erase the previous one
            if (guide.programs.isNotEmpty() || guide.error == null) source.programs = guide.programs
            source.error = guide.error
            publishAll()
            Log.i(
                TvPlayerManager.PERF_TAG,
                "Load: guide of ${guide.programs.size} channels in ${SystemClock.elapsedRealtime() - startedAt} ms"
            )
            saveCache(source)
        }.onFailure { error ->
            source.error = UiText.Res(R.string.error_load_channels, error.toUiText())
            publishAll()
        }
    }

    /**
     * Periodic guide reload of [source], without reloading its channels. Skipped while it loads;
     * a failed reload keeps the previous guide.
     */
    private fun reloadGuide(source: LoadedSource) {
        if (source.job?.isActive == true || source.channels.isEmpty()) return

        source.job = viewModelScope.launch {
            val client = source.dispatcharr
            if (client != null) {
                val now = System.currentTimeMillis()
                val artworkDeferred = async { client.getProgramArtwork(now, now + ARTWORK_WINDOW_MS) }
                client.getEpgGrid().onSuccess { epg ->
                    source.programs = matchDispatcharrGuide(source.channels, epg, client)
                }
                applyArtwork(source, artworkDeferred.await())
            } else {
                val guide = GuideLoader.load(source.guideUrls, source.channels, userAgent(source.config))
                if (guide.programs.isNotEmpty()) source.programs = guide.programs
            }
            publishAll()
            saveCache(source)
        }
    }

    /**
     * Programs of each channel (by app channel id) from the Dispatcharr grid: by tvg_id, channel uuid,
     * else by normalized name.
     */
    private suspend fun matchDispatcharrGuide(
        channels: List<Channel>,
        epg: List<DispatcharrEpgChannel>,
        client: DispatcharrClient
    ): Map<String, List<GuideProgram>> = withContext(Dispatchers.Default) {
        val epgByKey = epg.associate { it.channelUuid to it.programs }
        val epgKeyByName = buildNameIndex(epgByKey.keys, client)
        val programsMap = HashMap<String, List<GuideProgram>>()
        channels.forEach { channel ->
            programsMap[channel.id] = channel.tvgId?.let { epgByKey[it] }
                ?: epgByKey[channel.id]
                ?: epgKeyByName[EpgResolver.normalizeName(channel.name)]?.let { epgByKey[it] }
                ?: emptyList()
        }
        programsMap
    }

    private fun userAgent(config: SourceConfig) =
        config.customUserAgent?.takeIf { it.isNotBlank() } ?: HttpClients.DEFAULT_USER_AGENT

    /**
     * Publishes the channels of every source, in the order of the source list, with their current program
     * and the stream selected by the user. The error shown is the first one, with its source name when
     * several sources are enabled.
     */
    private suspend fun publishAll() {
        val generation = ++publishGeneration
        val snapshot = sources
        val channels = snapshot.flatMap { it.channels }
        val programs = withContext(Dispatchers.Default) {
            HashMap<String, List<GuideProgram>>().apply { snapshot.forEach { putAll(it.programs) } }
        }
        // Xtream: stream variants come with the channels, the selections follow their new URLs
        val selections = configRepository.selectedStreams.value
        if (selections.isNotEmpty()) {
            val updates = withContext(Dispatchers.Default) {
                channels.mapNotNull { channel ->
                    if (channel.availableStreams.isEmpty() || channel.id !in selections) return@mapNotNull null
                    freshSelection(channel.id, channel.availableStreams)?.let { channel.id to it }
                }
            }
            updates.forEach { (channelId, stream) -> configRepository.setSelectedStream(channelId, stream) }
        }
        // Off the main thread: playlists may hold tens of thousands of channels
        val published = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            withSelectedStreams(
                channels.map { channel ->
                    programs[channel.id]?.let { channel.copy(currentProgram = EpgResolver.currentProgram(it, now)) } ?: channel
                }
            )
        }
        if (generation != publishGeneration) return
        programsByChannelId = programs
        configRepository.applyLegacyFavoriteOrder(published.map { it.id })
        _channels.value = published
        _loadError.value = snapshot.firstNotNullOfOrNull { source ->
            source.error?.let { error ->
                if (snapshot.size > 1) UiText.Res(R.string.error_with_source, source.config.name, error) else error
            }
        }
    }

    private suspend fun saveCache(source: LoadedSource) {
        val id = source.config.id
        val channels = _channels.value.filter { it.sourceId == id }
        withContext(Dispatchers.IO) { configRepository.saveCachedChannels(id, channels) }
    }

    private fun saveUserName(config: SourceConfig, name: String) {
        configRepository.saveUserDisplayName(config.id, name)
        _userName.value = savedUserName(sources.map { it.config })
    }

    /** First name of the first enabled source that knows it. */
    private fun savedUserName(configs: List<SourceConfig>): String? =
        configs.firstNotNullOfOrNull { configRepository.getUserDisplayName(it.id) }

    /**
     * Renews the 16:9 artwork and categories of the programs aired in the next hours (Dispatcharr).
     */
    private suspend fun refreshArtwork(source: LoadedSource) {
        val client = source.dispatcharr ?: return
        if (source.programs.isEmpty()) return
        val now = System.currentTimeMillis()
        applyArtwork(source, client.getProgramArtwork(now, now + ARTWORK_WINDOW_MS))
    }

    private suspend fun applyArtwork(source: LoadedSource, artwork: Map<String, ProgramArtwork>) {
        val programsMap = source.programs
        if (artwork.isEmpty() || programsMap.isEmpty()) return

        source.programs = withContext(Dispatchers.Default) {
            programsMap.mapValues { (_, programs) ->
                programs.map { program ->
                    val art = artwork[program.id] ?: return@map program
                    program.copy(
                        posterUrl = art.imageUrl ?: program.posterUrl,
                        category = art.category ?: program.category
                    )
                }
            }
        }
        publishAll()
    }

    /**
     * Normalized name -> EPG key index, for channels without epg_data_id or tvg_id
     * (e.g. "France 2" <-> "France2.fr"). Exact match only, never partial.
     */
    private suspend fun buildNameIndex(epgKeys: Set<String>, client: DispatcharrClient): Map<String, String> {
        val index = mutableMapOf<String, String>()
        client.getEpgData().forEach { data ->
            if (data.tvgId in epgKeys) {
                index.putIfAbsent(EpgResolver.normalizeName(data.name), data.tvgId)
                index.putIfAbsent(EpgResolver.normalizeName(data.tvgId), data.tvgId)
            }
        }
        epgKeys.forEach { key -> index.putIfAbsent(EpgResolver.normalizeName(key), key) }
        return index
    }

    /**
     * Moves the progress bars forward and switches to the next program when the current one ends.
     */
    private suspend fun refreshCurrentPrograms() {
        val programsMap = programsByChannelId
        // Off the main thread (every channel, every 30 s); atomic update against a concurrent stream selection
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            _channels.update { channels ->
                channels.map { channel ->
                    val programs = programsMap[channel.id]
                    val program = if (programs != null) {
                        EpgResolver.currentProgram(programs, now)
                    } else {
                        channel.currentProgram?.let { EpgResolver.refreshCached(it, now) }
                    }
                    if (program == channel.currentProgram) channel else channel.copy(currentProgram = program)
                }
            }
        }
    }

    /**
     * First name to display: account first_name, else the start of the username ("jean.dupont" -> "Jean").
     * A technical username (digits, a single letter: "a8f3k2", "user123") is not displayed.
     * Each part always starts with an uppercase letter, even when the API returns everything in lowercase.
     */
    private fun displayFirstName(firstName: String?, username: String?): String? {
        val base = firstName?.trim()?.takeIf { it.isNotEmpty() }
            ?: username?.takeIf { name -> name.none { it.isDigit() } }
                ?.split(Regex("""[^\p{L}'-]+"""))
                ?.firstOrNull { it.isNotBlank() }
                ?.takeIf { it.length >= 2 }
            ?: return null
        return base.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.split("-").joinToString("-") { part ->
                part.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }
    }

    companion object {
        private const val EPG_TICK_MS = 30_000L
        private const val ARTWORK_WINDOW_MS = 4 * 60 * 60 * 1000L
        private const val ARTWORK_REFRESH_TICKS = 120 // every hour
        private const val GUIDE_RELOAD_TICKS = 720 // every 6 hours
        // Channel streams kept for the automatic choice: zapping back to a channel costs no request
        private const val STREAMS_CACHE_MS = 10 * 60 * 1000L
        // Past this delay the channel plays in server order rather than making the user wait
        private const val STREAMS_TIMEOUT_MS = 2_000L
    }
}
