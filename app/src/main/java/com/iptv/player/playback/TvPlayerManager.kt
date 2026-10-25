package com.iptv.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.iptv.player.data.remote.HttpClients
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Informations techniques réelles du flux (null tant qu'ExoPlayer ne les connaît pas).
 */
/**
 * Clé API Dispatcharr à joindre aux flux, uniquement pour les requêtes vers [host].
 */
data class StreamAuth(val host: String, val apiKey: String)

data class StreamInfo(
    val resolution: String? = null,
    val fps: Float? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val bitrateKbps: Int? = null
)

@OptIn(UnstableApi::class)
class TvPlayerManager(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentUrl: String? = null
    private var currentUserAgent: String? = null
    @Volatile
    private var currentAuth: StreamAuth? = null

    /**
     * Client HTTP du lecteur, dérivé du client partagé (connexions TLS réutilisées d'un zapping à l'autre).
     * La clé API est ajoutée par un intercepteur réseau, exécuté à chaque redirection : elle n'est envoyée
     * qu'au serveur Dispatcharr, jamais au fournisseur vers lequel il redirige.
     */
    private val streamHttpClient = HttpClients.base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val auth = currentAuth
            if (auth != null && request.url.host.equals(auth.host, ignoreCase = true)) {
                chain.proceed(request.newBuilder().header("X-API-Key", auth.apiKey).build())
            } else {
                chain.proceed(request)
            }
        }
        .build()

    // TS : les images clés non IDR (fréquentes en IPTV) suffisent pour afficher la première image plus tôt
    private val extractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
    private var retryCount = 0
    // Début du zapping en cours (mesure du temps jusqu'à la première image)
    private var zapStartedAt = 0L
    private val retryRunnable = Runnable { prepareCurrent() }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _streamInfo = MutableStateFlow(StreamInfo())
    val streamInfo: StateFlow<StreamInfo> = _streamInfo.asStateFlow()

    fun getPlayer(): ExoPlayer {
        return exoPlayer ?: createPlayer().also { exoPlayer = it }
    }

    private fun createPlayer(): ExoPlayer {
        // Buffer optimisé pour le zapping ultra-rapide sur Android TV (< 500 ms)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,   // Min buffer avant démarrage de la lecture (zapping instantané)
                3500,  // Max buffer
                500,   // Buffer requis après un rebuffering
                500    // Buffer après une pause
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            // Gestion du focus audio : coupe/reprend proprement face aux autres applications
            .setAudioAttributes(audioAttributes, true)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                    }

                    override fun onRenderedFirstFrame() {
                        if (zapStartedAt > 0) {
                            Log.i(PERF_TAG, "Zapping : première image en ${SystemClock.elapsedRealtime() - zapStartedAt} ms")
                            zapStartedAt = 0
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _isLoading.value = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_READY) {
                            retryCount = 0
                            _error.value = null
                            updateStreamInfo()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateStreamInfo()
                    }

                    override fun onPlayerError(playbackError: PlaybackException) {
                        handlePlaybackError(playbackError)
                    }
                })
            }
    }

    /**
     * Dispatcharr bascule côté serveur sur un flux de secours : on retente avec un backoff court.
     */
    private fun handlePlaybackError(playbackError: PlaybackException) {
        val player = exoPlayer ?: return
        if (playbackError.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }

        val httpCode = (playbackError.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        // Refus définitifs du fournisseur : inutile de réessayer
        val isDefinitive = httpCode in DEFINITIVE_HTTP_ERRORS

        if (!isDefinitive && retryCount < MAX_RETRIES && currentUrl != null) {
            val delayMs = 1000L shl retryCount
            retryCount++
            _isLoading.value = true
            _error.value = null
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, delayMs)
        } else {
            _isLoading.value = false
            _error.value = describeError(playbackError, httpCode)
        }
    }

    private fun describeError(playbackError: PlaybackException, httpCode: Int?): String = when {
        httpCode == 401 || httpCode == 403 -> "Accès refusé par le fournisseur (HTTP $httpCode)"
        httpCode == 404 || httpCode == 410 -> "Flux introuvable chez le fournisseur (HTTP $httpCode)"
        httpCode == 429 || httpCode == 458 || httpCode == 509 ->
            "Limite de connexions du fournisseur atteinte (HTTP $httpCode)"
        httpCode != null -> "Le serveur du flux a répondu HTTP $httpCode"
        playbackError.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            playbackError.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Connexion au flux impossible"
        playbackError.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
            playbackError.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "Format du flux non pris en charge"
        else -> "Erreur de lecture (${playbackError.errorCodeName})"
    }

    private fun updateStreamInfo() {
        val player = exoPlayer ?: return
        val videoFormat = player.videoFormat
        val audioFormat = player.audioFormat

        _streamInfo.value = StreamInfo(
            resolution = videoFormat?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}x${it.height}" },
            fps = videoFormat?.frameRate?.takeIf { it > 0f },
            videoCodec = videoFormat?.let { codecLabel(it) },
            audioCodec = audioFormat?.let { codecLabel(it) },
            audioChannels = audioFormat?.channelCount?.takeIf { it != Format.NO_VALUE && it > 0 },
            bitrateKbps = (videoFormat?.bitrate ?: Format.NO_VALUE).takeIf { it > 0 }?.div(1000)
        )
    }

    private fun codecLabel(format: Format): String? {
        val mime = format.sampleMimeType ?: return null
        return mime.substringAfter('/').uppercase()
    }

    fun playStream(url: String, customUserAgent: String? = null, auth: StreamAuth? = null) {
        zapStartedAt = SystemClock.elapsedRealtime()
        currentUrl = url
        currentUserAgent = customUserAgent
        currentAuth = auth
        retryCount = 0
        mainHandler.removeCallbacks(retryRunnable)
        _streamInfo.value = StreamInfo()
        prepareCurrent()
    }

    private fun prepareCurrent() {
        val url = currentUrl ?: return
        _error.value = null
        val player = getPlayer()

        // User-Agent customisé (idéal pour remonter dans les stats Dispatcharr)
        val userAgent = currentUserAgent?.takeIf { it.isNotBlank() } ?: HttpClients.DEFAULT_USER_AGENT

        val httpDataSourceFactory = OkHttpDataSource.Factory(streamHttpClient)
            .setUserAgent(userAgent)

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.02f)
                    .setMinPlaybackSpeed(0.98f)
                    .build()
            )
            .build()

        val mediaSource: MediaSource = if (url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /**
     * Arrête la lecture (retour à l'accueil, application en arrière-plan) : libère le flux réseau et le son.
     */
    fun stop() {
        mainHandler.removeCallbacks(retryRunnable)
        currentUrl = null
        retryCount = 0
        exoPlayer?.run {
            stop()
            clearMediaItems()
        }
        _isLoading.value = false
        _isPlaying.value = false
        _error.value = null
        _streamInfo.value = StreamInfo()
    }

    fun release() {
        mainHandler.removeCallbacks(retryRunnable)
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        const val PERF_TAG = "TVPerf"
        private const val MAX_RETRIES = 3
        private val DEFINITIVE_HTTP_ERRORS = setOf(401, 403, 404, 410)
    }
}
