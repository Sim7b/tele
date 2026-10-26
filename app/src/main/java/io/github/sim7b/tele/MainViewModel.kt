package io.github.sim7b.tele

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.failure
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Main app state: loads channels and guide from the active source (Dispatcharr, Xtream Codes or M3U),
 * refreshes the current program every 30 s, keeps the selected streams and a local cache
 * for an immediate display at startup.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val configRepository = SettingsRepository(application)
    val dispatcharrClient = DispatcharrClient(
        configRepository.activeConfig.value?.takeIf { it.type == SourceType.DISPATCHARR }
    )

    // Cached channels are read off the main thread at startup (see showCachedChannels)
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<UiText?>(null)
    val loadError: StateFlow<UiText?> = _loadError.asStateFlow()

    // Account first name (welcome message), known at startup when it was saved
    private val _userName = MutableStateFlow(
        configRepository.activeConfig.value?.let { configRepository.getUserDisplayName(it.id) }
    )
    val userName: StateFlow<String?> = _userName.asStateFlow()

    // True once the first sync is over (channels, guide and artwork), even when it failed
    private val _initialSyncDone = MutableStateFlow(false)
    val initialSyncDone: StateFlow<Boolean> = _initialSyncDone.asStateFlow()

    // Free / total playback slots of the Dispatcharr provider accounts (null: not shown)
    private val _streamSlots = MutableStateFlow<StreamSlots?>(null)
    val streamSlots: StateFlow<StreamSlots?> = _streamSlots.asStateFlow()
    private var slotsJob: Job? = null

    // EPG programs by channel id, kept to move the "current program" forward
    @Volatile
    private var programsByChannelId: Map<String, List<GuideProgram>> = emptyMap()
    private var loadJob: Job? = null
    // Null at startup: the first load shows the cached channels of the active source
    private var loadedConfigId: String? = null
    // XMLTV guides of the Xtream / M3U source, kept for the periodic guide reload
    private var playlistGuideUrls: List<String> = emptyList()

    init {
        viewModelScope.launch {
            configRepository.activeConfig.collect { config -> loadChannels(config) }
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
                    reloadGuide()
                } else if (ticks % ARTWORK_REFRESH_TICKS == 0 &&
                    configRepository.activeConfig.value?.type == SourceType.DISPATCHARR
                ) {
                    // Artwork only covers the next hours: renew it regularly
                    refreshArtwork()
                }
            }
        }
    }

    /**
     * Reloads the Dispatcharr playback slots (cheap request). Called every 30 s and when the home
     * screen is shown again, since leaving the player frees a slot.
     */
    fun refreshStreamSlots() {
        if (configRepository.activeConfig.value?.type != SourceType.DISPATCHARR) {
            _streamSlots.value = null
            return
        }
        if (slotsJob?.isActive == true) return
        slotsJob = viewModelScope.launch { _streamSlots.value = dispatcharrClient.getStreamSlots() }
    }

    /** Manual refresh: everything is reloaded, including the reference tables cached by the client. */
    fun refresh() {
        dispatcharrClient.clearCaches()
        loadChannels(configRepository.activeConfig.value)
    }

    /** Streams available for a channel (loaded on demand, when the menu opens). */
    suspend fun loadStreams(channel: Channel): Result<List<StreamSource>> {
        // Xtream: TS / HLS variants already known
        if (channel.availableStreams.isNotEmpty()) return Result.success(channel.availableStreams)
        // M3U: a single stream per playlist entry
        if (configRepository.activeConfig.value?.type != SourceType.DISPATCHARR) return Result.success(emptyList())
        val serverId = channel.serverId
            ?: return failure(R.string.error_sync_channels_for_streams)
        return dispatcharrClient.getChannelStreams(serverId).onSuccess { streams ->
            freshSelection(channel.id, streams)?.let { selectStream(channel, it) }
        }
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
        SourceType.DISPATCHARR -> dispatcharrClient.testConnection(config)
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
    private fun dropDirectSelections(channels: List<Channel>) {
        val selections = configRepository.selectedStreams.value
        channels.forEach { channel ->
            val selected = selections[channel.id] ?: return@forEach
            if (!dispatcharrClient.isProxyUrl(selected.url)) configRepository.setSelectedStream(channel.id, null)
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

    private fun loadChannels(config: SourceConfig?) {
        // A new load replaces the previous one (prevents an old response from overwriting the latest one)
        loadJob?.cancel()

        if (config?.id != loadedConfigId) {
            // Source change: do not show the channels of the previous one
            loadedConfigId = config?.id
            programsByChannelId = emptyMap()
            playlistGuideUrls = emptyList()
            _channels.value = emptyList()
            config?.let { showCachedChannels(it) }
            _userName.value = config?.let { configRepository.getUserDisplayName(it.id) }
        }

        dispatcharrClient.setConfig(config?.takeIf { it.type == SourceType.DISPATCHARR })
        slotsJob?.cancel()
        _streamSlots.value = null
        refreshStreamSlots()

        if (config == null) {
            programsByChannelId = emptyMap()
            _channels.value = emptyList()
            _isLoading.value = false
            _loadError.value = null
            _initialSyncDone.value = true
            return
        }

        _isLoading.value = true
        _loadError.value = null

        loadJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            when (config.type) {
                SourceType.DISPATCHARR -> loadDispatcharr(config, startedAt)
                SourceType.XTREAM, SourceType.M3U -> loadPlaylistSource(config, startedAt)
            }
            _isLoading.value = false
            _initialSyncDone.value = true
        }
    }

    /** Shows the cached channels of [config] while it loads, unless the load has already published its own. */
    private fun showCachedChannels(config: SourceConfig) {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { configRepository.getCachedChannels(config.id) }
            if (cached.isEmpty() || loadedConfigId != config.id) return@launch
            val withStreams = withContext(Dispatchers.Default) { withSelectedStreams(cached) }
            _channels.update { current -> if (current.isEmpty() && loadedConfigId == config.id) withStreams else current }
        }
    }

    private suspend fun loadDispatcharr(config: SourceConfig, startedAt: Long) = coroutineScope {
        // Account first name, in parallel: does not delay the channel load
        launch {
            dispatcharrClient.getCurrentUser()?.let { (firstName, username) ->
                displayFirstName(firstName, username)?.let { saveUserName(config, it) }
            }
        }

        // Program artwork in parallel with channels and guide: it only depends on the time
        val now = System.currentTimeMillis()
        val artworkDeferred = async { dispatcharrClient.getProgramArtwork(now, now + ARTWORK_WINDOW_MS) }
        val epgDeferred = async { dispatcharrClient.getEpgGrid() }
        val channelsResult = dispatcharrClient.getChannels()
        val epgResult = epgDeferred.await()
        Log.i(TvPlayerManager.PERF_TAG, "Load: channels + guide in ${SystemClock.elapsedRealtime() - startedAt} ms")

        channelsResult.onSuccess { dChannels ->
            val channels = dChannels.map { it.toChannel() }
            dropDirectSelections(channels)
            val programs = matchDispatcharrGuide(channels, epgResult.getOrNull().orEmpty())
            publish(channels, programs, epgResult.exceptionOrNull()?.let { UiText.Res(R.string.error_guide_unavailable, it.toUiText()) })
            _isLoading.value = false

            applyArtwork(artworkDeferred.await())
            Log.i(TvPlayerManager.PERF_TAG, "Load: artwork included in ${SystemClock.elapsedRealtime() - startedAt} ms")
            saveCache(config)
        }.onFailure { error ->
            artworkDeferred.cancel()
            _loadError.value = UiText.Res(R.string.error_load_channels, error.toUiText())
        }
    }

    /**
     * Xtream Codes and M3U: channels shown as soon as received, then the XMLTV guide (often large).
     */
    private suspend fun loadPlaylistSource(config: SourceConfig, startedAt: Long) {
        val result = when (config.type) {
            SourceType.XTREAM -> XtreamClient.loadChannels(config)
            else -> M3uClient.loadChannels(config)
        }
        result.onSuccess { source ->
            source.userName?.let { displayFirstName(null, it) }?.let { saveUserName(config, it) }
            playlistGuideUrls = source.guideUrls
            // The guide already loaded for this source stays shown while the new one downloads
            publish(source.channels, programsByChannelId, null)
            _isLoading.value = false
            Log.i(TvPlayerManager.PERF_TAG, "Load: ${source.channels.size} channels in ${SystemClock.elapsedRealtime() - startedAt} ms")

            val guide = GuideLoader.load(source.guideUrls, source.channels, userAgent(config))
            // A guide that failed to download does not erase the previous one
            if (guide.programs.isNotEmpty() || guide.error == null) programsByChannelId = guide.programs
            refreshCurrentPrograms()
            _loadError.value = guide.error
            Log.i(
                TvPlayerManager.PERF_TAG,
                "Load: guide of ${guide.programs.size} channels in ${SystemClock.elapsedRealtime() - startedAt} ms"
            )
            saveCache(config)
        }.onFailure { error ->
            _loadError.value = UiText.Res(R.string.error_load_channels, error.toUiText())
        }
    }

    /**
     * Periodic guide reload, without reloading the channels. Skipped while a load is running;
     * a failed reload keeps the previous guide.
     */
    private fun reloadGuide() {
        val config = configRepository.activeConfig.value ?: return
        if (loadJob?.isActive == true || _channels.value.isEmpty()) return

        loadJob = viewModelScope.launch {
            when (config.type) {
                SourceType.DISPATCHARR -> {
                    val now = System.currentTimeMillis()
                    val artworkDeferred = async { dispatcharrClient.getProgramArtwork(now, now + ARTWORK_WINDOW_MS) }
                    dispatcharrClient.getEpgGrid().onSuccess { epg ->
                        programsByChannelId = matchDispatcharrGuide(_channels.value, epg)
                        refreshCurrentPrograms()
                    }
                    applyArtwork(artworkDeferred.await())
                }
                SourceType.XTREAM, SourceType.M3U -> {
                    val guide = GuideLoader.load(playlistGuideUrls, _channels.value, userAgent(config))
                    if (guide.programs.isNotEmpty()) {
                        programsByChannelId = guide.programs
                        refreshCurrentPrograms()
                    }
                }
            }
            saveCache(config)
        }
    }

    /**
     * Programs of each channel (by app channel id) from the Dispatcharr grid: by tvg_id, channel uuid,
     * else by normalized name.
     */
    private suspend fun matchDispatcharrGuide(
        channels: List<Channel>,
        epg: List<DispatcharrEpgChannel>
    ): Map<String, List<GuideProgram>> = withContext(Dispatchers.Default) {
        val epgByKey = epg.associate { it.channelUuid to it.programs }
        val epgKeyByName = buildNameIndex(epgByKey.keys)
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

    /** Publishes the channels with their current program and the stream selected by the user. */
    private suspend fun publish(channels: List<Channel>, programs: Map<String, List<GuideProgram>>, error: UiText?) {
        programsByChannelId = programs
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
        configRepository.applyLegacyFavoriteOrder(published.map { it.id })
        _channels.value = published
        _loadError.value = error
    }

    private suspend fun saveCache(config: SourceConfig) = withContext(Dispatchers.IO) {
        configRepository.saveCachedChannels(config.id, _channels.value)
    }

    private fun saveUserName(config: SourceConfig, name: String) {
        _userName.value = name
        configRepository.saveUserDisplayName(config.id, name)
    }

    /**
     * Renews the 16:9 artwork and categories of the programs aired in the next hours (Dispatcharr).
     */
    private suspend fun refreshArtwork() {
        if (programsByChannelId.isEmpty()) return
        val now = System.currentTimeMillis()
        applyArtwork(dispatcharrClient.getProgramArtwork(now, now + ARTWORK_WINDOW_MS))
    }

    private suspend fun applyArtwork(artwork: Map<String, ProgramArtwork>) {
        val programsMap = programsByChannelId
        if (artwork.isEmpty() || programsMap.isEmpty()) return

        programsByChannelId = withContext(Dispatchers.Default) {
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
        refreshCurrentPrograms()
    }

    /**
     * Normalized name -> EPG key index, for channels without epg_data_id or tvg_id
     * (e.g. "France 2" <-> "France2.fr"). Exact match only, never partial.
     */
    private suspend fun buildNameIndex(epgKeys: Set<String>): Map<String, String> {
        val index = mutableMapOf<String, String>()
        dispatcharrClient.getEpgData().forEach { data ->
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
    }
}
