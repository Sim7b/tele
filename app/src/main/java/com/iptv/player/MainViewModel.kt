package com.iptv.player

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.epg.EpgResolver
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.GuideProgram
import com.iptv.player.data.model.ProgramArtwork
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.model.StreamSource
import com.iptv.player.data.remote.DispatcharrClient
import com.iptv.player.data.remote.GuideLoader
import com.iptv.player.data.remote.HttpClients
import com.iptv.player.data.remote.M3uClient
import com.iptv.player.data.remote.XtreamClient
import com.iptv.player.data.storage.SettingsRepository
import com.iptv.player.playback.TvPlayerManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * État principal de l'application : chargement des chaînes et du guide depuis la source active
 * (Dispatcharr, Xtream Codes ou M3U), programme en cours rafraîchi toutes les 30 s, flux choisis
 * et cache local pour un affichage immédiat au démarrage.
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

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    // Prénom du compte (message d'accueil), connu dès le démarrage s'il a été mémorisé
    private val _userName = MutableStateFlow(
        configRepository.activeConfig.value?.let { configRepository.getUserDisplayName(it.id) }
    )
    val userName: StateFlow<String?> = _userName.asStateFlow()

    // Vrai une fois la première synchronisation terminée (chaînes, guide et visuels), même en cas d'échec
    private val _initialSyncDone = MutableStateFlow(false)
    val initialSyncDone: StateFlow<Boolean> = _initialSyncDone.asStateFlow()

    // Programmes EPG par identifiant de chaîne, conservés pour faire avancer le "programme en cours"
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
                    // Les visuels ne couvrent que les prochaines heures : on les renouvelle régulièrement
                    SourceType.DISPATCHARR -> if (ticks % ARTWORK_REFRESH_TICKS == 0) refreshArtwork()
                    // Le guide XMLTV n'est conservé que sur une fenêtre limitée : rechargement périodique
                    SourceType.XTREAM, SourceType.M3U -> if (ticks % GUIDE_RELOAD_TICKS == 0) refresh()
                    null -> Unit
                }
            }
        }
    }

    fun refresh() {
        loadChannels(configRepository.activeConfig.value)
    }

    /** Flux disponibles pour une chaîne (chargés à la demande, à l'ouverture du menu). */
    suspend fun loadStreams(channel: Channel): Result<List<StreamSource>> {
        // Xtream : variantes TS / HLS déjà connues
        if (channel.availableStreams.isNotEmpty()) return Result.success(channel.availableStreams)
        // M3U : un seul flux par entrée de playlist
        if (configRepository.activeConfig.value?.type != SourceType.DISPATCHARR) return Result.success(emptyList())
        val serverId = channel.serverId
            ?: return Result.failure(IllegalStateException("Synchronisez les chaînes pour afficher leurs flux"))
        return dispatcharrClient.getChannelStreams(serverId)
    }

    /**
     * Choix du flux d'une chaîne, mémorisé entre les sessions. [stream] null (ou sans id) = mode automatique.
     */
    fun selectStream(channel: Channel, stream: StreamSource?) {
        configRepository.setSelectedStream(channel.id, stream?.takeIf { it.id != null })
        _channels.value = withSelectedStreams(_channels.value)
    }

    /** Test de connexion depuis le formulaire d'une source (message de réussite ou erreur lisible). */
    suspend fun testConnection(config: SourceConfig): Result<String> = when (config.type) {
        SourceType.DISPATCHARR -> dispatcharrClient.testConnection(config)
            .map { count -> "Connexion réussie · $count chaînes détectées" }
        SourceType.XTREAM -> XtreamClient.authenticate(config).map { account ->
            listOfNotNull(
                "Compte actif",
                account.expiresAtMillis?.let { "expire le " + SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).format(Date(it)) },
                account.maxConnections?.let { "$it connexion(s) simultanée(s)" }
            ).joinToString(" · ")
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
        // Un nouveau chargement remplace le précédent (évite qu'une réponse ancienne écrase la plus récente)
        loadJob?.cancel()

        if (config?.id != loadedConfigId) {
            // Changement de source : ne pas afficher les chaînes de l'ancienne
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
        // Prénom du compte, en parallèle : ne retarde pas le chargement des chaînes
        launch {
            dispatcharrClient.getCurrentUser()?.let { (firstName, username) ->
                displayFirstName(firstName, username)?.let { saveUserName(config, it) }
            }
        }

        // Visuels des programmes en parallèle des chaînes et du guide : ils ne dépendent que de l'heure
        val now = System.currentTimeMillis()
        val artworkDeferred = async { dispatcharrClient.getProgramArtwork(now, now + ARTWORK_WINDOW_MS) }
        val epgDeferred = async { dispatcharrClient.getEpgGrid() }
        val channelsResult = dispatcharrClient.getChannels()
        val epgResult = epgDeferred.await()
        Log.i(TvPlayerManager.PERF_TAG, "Chargement : chaînes + guide en ${SystemClock.elapsedRealtime() - startedAt} ms")

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
            publish(channels, programs, epgResult.exceptionOrNull()?.let { "Guide EPG indisponible : ${it.localizedMessage}" })
            _isLoading.value = false

            applyArtwork(artworkDeferred.await())
            Log.i(TvPlayerManager.PERF_TAG, "Chargement : visuels inclus en ${SystemClock.elapsedRealtime() - startedAt} ms")
            saveCache(config)
        }.onFailure { error ->
            artworkDeferred.cancel()
            _loadError.value = "Impossible de charger les chaînes : ${error.localizedMessage ?: "erreur réseau"}"
        }
    }

    /**
     * Xtream Codes et M3U : chaînes affichées dès réception, puis guide XMLTV (souvent volumineux).
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
            Log.i(TvPlayerManager.PERF_TAG, "Chargement : ${source.channels.size} chaînes en ${SystemClock.elapsedRealtime() - startedAt} ms")

            val userAgent = config.customUserAgent?.takeIf { it.isNotBlank() } ?: HttpClients.DEFAULT_USER_AGENT
            val guide = GuideLoader.load(source.guideUrls, source.channels, userAgent)
            programsByChannelId = guide.programs
            refreshCurrentPrograms()
            _loadError.value = guide.error
            Log.i(
                TvPlayerManager.PERF_TAG,
                "Chargement : guide de ${guide.programs.size} chaînes en ${SystemClock.elapsedRealtime() - startedAt} ms"
            )
            saveCache(config)
        }.onFailure { error ->
            _loadError.value = "Impossible de charger les chaînes : ${error.localizedMessage ?: "erreur réseau"}"
        }
    }

    /** Publie les chaînes avec leur programme en cours et le flux choisi par l'utilisateur. */
    private fun publish(channels: List<Channel>, programs: Map<String, List<GuideProgram>>, error: String?) {
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
     * Renouvelle les visuels 16:9 et catégories des programmes diffusés dans les prochaines heures (Dispatcharr).
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
     * Index nom normalisé -> clé EPG, pour les chaînes sans epg_data_id ni tvg_id
     * (ex: "France 2" <-> "France2.fr"). Correspondance exacte uniquement, jamais partielle.
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
     * Fait avancer les barres de progression et bascule sur le programme suivant quand l'actuel se termine.
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
     * Prénom à afficher : first_name du compte, sinon début de l'identifiant ("jean.dupont" -> "Jean").
     * Un identifiant technique (chiffres, une seule lettre : "a8f3k2", "user123") n'est pas affiché.
     * Chaque partie commence toujours par une majuscule, même si l'API renvoie tout en minuscules.
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
                part.replaceFirstChar { it.titlecase(Locale.FRENCH) }
            }
        }
    }

    companion object {
        private const val EPG_TICK_MS = 30_000L
        private const val ARTWORK_WINDOW_MS = 4 * 60 * 60 * 1000L
        private const val ARTWORK_REFRESH_TICKS = 120 // toutes les heures
        private const val GUIDE_RELOAD_TICKS = 720 // toutes les 6 heures
    }
}
