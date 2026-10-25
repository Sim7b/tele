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
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.model.ProgramArtwork
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
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

    private val _channels = MutableStateFlow(
        withSelectedStreams(configRepository.activeConfig.value?.let { configRepository.getCachedChannels(it.id) } ?: emptyList())
    )
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _isLoading = MutableStateFlow(_channels.value.isEmpty())
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

    // EPG programs by channel id, kept to move the "current program" forward
    @Volatile
    private var programsByChannelId: Map<String, List<GuideProgram>> = emptyMap()
    private var loadJob: Job? = null
    private var loadedConfigId: String? = configRepository.activeConfig.value?.id

    init {
        viewModelScope.launch {
            configRepository.activeConfig.collect { config -> loadChannels(config) }
        }
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(EPG_TICK_MS)
                refreshCurrentPrograms()
                ticks++
                when (configRepository.activeConfig.value?.type) {
                    // Artwork only covers the next hours: renew it regularly
                    SourceType.DISPATCHARR -> if (ticks % ARTWORK_REFRESH_TICKS == 0) refreshArtwork()
                    // The XMLTV guide is only kept over a limited window: reload it periodically
                    SourceType.XTREAM, SourceType.M3U -> if (ticks % GUIDE_RELOAD_TICKS == 0) refresh()
                    null -> Unit
                }
            }
        }
    }

    fun refresh() {
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
        return dispatcharrClient.getChannelStreams(serverId)
    }

    /**
     * Stream selection of a channel, kept across sessions. Null [stream] (or without id) = automatic mode.
     */
    fun selectStream(channel: Channel, stream: StreamSource?) {
        configRepository.setSelectedStream(channel.id, stream?.takeIf { it.id != null })
        _channels.value = withSelectedStreams(_channels.value)
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
            _channels.value = config?.let { withSelectedStreams(configRepository.getCachedChannels(it.id)) } ?: emptyList()
            _userName.value = config?.let { configRepository.getUserDisplayName(it.id) }
        }

        dispatcharrClient.setConfig(config?.takeIf { it.type == SourceType.DISPATCHARR })

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
            val (channels, programs) = withContext(Dispatchers.Default) {
                val epgByKey = epgResult.getOrNull()
                    ?.associate { it.channelUuid to it.programs }
                    ?: emptyMap()
                val epgKeyByName = buildNameIndex(epgByKey.keys)
                val programsMap = HashMap<String, List<GuideProgram>>()
                val result = dChannels.map { dChan ->
                    val baseChannel = dChan.toChannel()
                    programsMap[baseChannel.id] = epgByKey[dChan.tvgId]
                        ?: epgByKey[dChan.uuid]
                        ?: epgKeyByName[EpgResolver.normalizeName(dChan.name)]?.let { epgByKey[it] }
                        ?: emptyList()
                    baseChannel
                }
                result to programsMap
            }
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
            publish(source.channels, emptyMap(), null)
            _isLoading.value = false
            Log.i(TvPlayerManager.PERF_TAG, "Load: ${source.channels.size} channels in ${SystemClock.elapsedRealtime() - startedAt} ms")

            val userAgent = config.customUserAgent?.takeIf { it.isNotBlank() } ?: HttpClients.DEFAULT_USER_AGENT
            val guide = GuideLoader.load(source.guideUrls, source.channels, userAgent)
            programsByChannelId = guide.programs
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

    /** Publishes the channels with their current program and the stream selected by the user. */
    private fun publish(channels: List<Channel>, programs: Map<String, List<GuideProgram>>, error: UiText?) {
        programsByChannelId = programs
        val now = System.currentTimeMillis()
        val withPrograms = channels.map { channel ->
            programs[channel.id]?.let { channel.copy(currentProgram = EpgResolver.currentProgram(it, now)) } ?: channel
        }
        configRepository.applyLegacyFavoriteOrder(withPrograms.map { it.id })
        _channels.value = withSelectedStreams(withPrograms)
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
    private fun refreshCurrentPrograms() {
        val now = System.currentTimeMillis()
        val programsMap = programsByChannelId
        _channels.value = _channels.value.map { channel ->
            val programs = programsMap[channel.id]
            val program = if (programs != null) {
                EpgResolver.currentProgram(programs, now)
            } else {
                channel.currentProgram?.let { EpgResolver.refreshCached(it, now) }
            }
            if (program == channel.currentProgram) channel else channel.copy(currentProgram = program)
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
