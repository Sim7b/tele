package io.github.sim7b.tele.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.data.remote.ApiKeyInterceptor
import io.github.sim7b.tele.data.remote.HttpClients
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Actual technical information of the stream (null until ExoPlayer knows it).
 */
data class StreamInfo(
    val resolution: String? = null,
    val fps: Float? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val bitrateKbps: Int? = null
)

/** Automatic reconnection in progress after the stream dropped: retry [attempt] of [maxAttempts]. */
data class Reconnection(val attempt: Int, val maxAttempts: Int)

@OptIn(UnstableApi::class)
class TvPlayerManager(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentUrl: String? = null
    private var currentUserAgent: String? = null
    @Volatile
    private var currentAuth: ApiKeyAuth? = null

    /**
     * Player HTTP client, derived from the shared client (TLS connections reused from one channel to the next).
     * The API key is added by a network interceptor, run on each redirect: it is only sent
     * to the origin of the Dispatcharr server, never to the provider it redirects to.
     */
    private val streamHttpClient = HttpClients.base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addNetworkInterceptor(ApiKeyInterceptor { currentAuth })
        .build()

    // TS: non-IDR keyframes (common in IPTV) are enough to show the first frame sooner
    private val extractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
    private var retryCount = 0
    // The current stream has played at least once: a drop gets more (and visible) reconnection attempts
    private var hasPlayed = false
    // HLS playlist served without the .m3u8 extension: detected when the progressive extractors reject it
    private var forceHls = false
    // Start of the current channel switch (measures the time to the first frame)
    private var zapStartedAt = 0L
    private val retryRunnable = Runnable { prepareCurrent() }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    private val _streamInfo = MutableStateFlow(StreamInfo())
    val streamInfo: StateFlow<StreamInfo> = _streamInfo.asStateFlow()

    private val _reconnection = MutableStateFlow<Reconnection?>(null)
    val reconnection: StateFlow<Reconnection?> = _reconnection.asStateFlow()

    // Network back after a failure (Wi-Fi or router restart): the stream is retried right away
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post {
                if (_error.value != null && currentUrl != null) retry()
            }
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: RuntimeException) {
            Log.w(PERF_TAG, "Network callback unavailable: ${e.javaClass.simpleName}")
        }
    }

    fun getPlayer(): ExoPlayer {
        return exoPlayer ?: createPlayer().also { exoPlayer = it }
    }

    private fun createPlayer(): ExoPlayer {
        // Fast channel switching (playback starts with 500 ms buffered), then a comfortable buffer
        // against network hiccups: the channel switch speed only depends on bufferForPlaybackMs
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs: buffer kept ahead of the playback position
                30_000, // maxBufferMs
                500,    // bufferForPlaybackMs: buffer required to start (channel switch)
                2_500   // bufferForPlaybackAfterRebufferMs: buffer required to resume after a stall
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            // Audio focus handling: pauses/resumes cleanly around other apps
            .setAudioAttributes(audioAttributes, true)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        // Radio-like streams have no video frame: playing audio is the proof they work
                        if (playing && !currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) markPlaying()
                    }

                    override fun onRenderedFirstFrame() {
                        markPlaying()
                        if (zapStartedAt > 0) {
                            Log.i(PERF_TAG, "Channel switch: first frame in ${SystemClock.elapsedRealtime() - zapStartedAt} ms")
                            zapStartedAt = 0
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _isLoading.value = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_READY) {
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
     * The stream really plays (first frame shown, or audio of a stream without video): the attempt budget is
     * restored. Not on STATE_READY: a broken HLS stream can reach it, then fail, without ever playing.
     */
    private fun markPlaying() {
        retryCount = 0
        hasPlayed = true
        _reconnection.value = null
    }

    /**
     * Retries with a backoff ([ReconnectPolicy]); a definitive refusal or the last failed attempt shows the error.
     */
    private fun handlePlaybackError(playbackError: PlaybackException) {
        val player = exoPlayer ?: return
        // Fell behind the live window: back to the live edge, within the same attempt budget (a broken
        // HLS playlist can raise it in a loop, which used to leave an endless spinner)
        if (playbackError.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
            retryCount < ReconnectPolicy.maxAttempts(hasPlayed)
        ) {
            retryCount++
            player.seekToDefaultPosition()
            player.prepare()
            return
        }

        if (playbackError.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !forceHls &&
            currentUrl?.let { isHlsUrl(it) } == false
        ) {
            forceHls = true
            prepareCurrent()
            return
        }

        val httpCode = (playbackError.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        // Definitive refusals from the provider: no point retrying
        val isDefinitive = httpCode in DEFINITIVE_HTTP_ERRORS

        val maxAttempts = ReconnectPolicy.maxAttempts(hasPlayed)
        if (!isDefinitive && retryCount < maxAttempts && currentUrl != null) {
            val delayMs = ReconnectPolicy.delayMs(retryCount)
            retryCount++
            _isLoading.value = true
            _error.value = null
            if (hasPlayed) _reconnection.value = Reconnection(retryCount, maxAttempts)
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, delayMs)
        } else {
            _isLoading.value = false
            _reconnection.value = null
            _error.value = describeError(playbackError, httpCode)
        }
    }

    private fun describeError(playbackError: PlaybackException, httpCode: Int?): UiText = when {
        httpCode == 401 || httpCode == 403 -> UiText.Res(R.string.player_error_access_denied, httpCode)
        httpCode == 404 || httpCode == 410 -> UiText.Res(R.string.player_error_stream_not_found, httpCode)
        httpCode == 429 || httpCode == 458 || httpCode == 509 ->
            UiText.Res(R.string.player_error_connection_limit, httpCode)
        httpCode != null -> UiText.Res(R.string.player_error_http, httpCode)
        playbackError.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            playbackError.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            UiText.Res(R.string.player_error_connection_failed)
        playbackError.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
            UiText.Res(R.string.player_error_live_window)
        playbackError.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
            playbackError.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            UiText.Res(R.string.player_error_unsupported_format)
        else -> UiText.Res(R.string.player_error_generic, playbackError.errorCodeName)
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

    fun playStream(url: String, customUserAgent: String? = null, auth: ApiKeyAuth? = null) {
        zapStartedAt = SystemClock.elapsedRealtime()
        currentUrl = url
        currentUserAgent = customUserAgent
        currentAuth = auth
        retryCount = 0
        hasPlayed = false
        forceHls = false
        mainHandler.removeCallbacks(retryRunnable)
        _reconnection.value = null
        _streamInfo.value = StreamInfo()
        prepareCurrent()
    }

    /** New series of attempts on the current stream (OK on the error message, network back). */
    fun retry() {
        if (currentUrl == null) return
        retryCount = 0
        mainHandler.removeCallbacks(retryRunnable)
        _reconnection.value = null
        _isLoading.value = true
        prepareCurrent()
    }

    private fun prepareCurrent() {
        val url = currentUrl ?: return
        _error.value = null
        val player = getPlayer()

        // Custom User-Agent (shows up in the Dispatcharr stats)
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

        val mediaSource: MediaSource = if (forceHls || isHlsUrl(url)) {
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

    private fun isHlsUrl(url: String) = url.contains(".m3u8", ignoreCase = true)

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /**
     * Stops playback (back to home, app in background): releases the network stream and the audio.
     */
    fun stop() {
        mainHandler.removeCallbacks(retryRunnable)
        currentUrl = null
        retryCount = 0
        _reconnection.value = null
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
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: RuntimeException) {
            Log.w(PERF_TAG, "Network callback not registered: ${e.javaClass.simpleName}")
        }
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        const val PERF_TAG = "TVPerf"
        private val DEFINITIVE_HTTP_ERRORS = setOf(401, 403, 404, 410)
    }
}
