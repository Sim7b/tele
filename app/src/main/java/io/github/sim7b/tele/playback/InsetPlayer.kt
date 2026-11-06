package io.github.sim7b.tele.playback

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.data.remote.ApiKeyInterceptor
import io.github.sim7b.tele.data.remote.HttpClients
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * Second channel shown in a corner of the player (a match beside another one): its own muted player, in at
 * most 480p. It takes one more provider connection; a refused one shows as [failed]. Each Dispatcharr key
 * only goes to its own server, as for the main player.
 */
@OptIn(UnstableApi::class)
class InsetPlayer(private val context: Context) {

    @Volatile
    private var auths: List<ApiKeyAuth> = emptyList()
    private val client = HttpClients.base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addNetworkInterceptor(ApiKeyInterceptor { request -> auths.firstOrNull { it.appliesTo(request.url) } })
        .build()
    private val extractors = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
    private var player: ExoPlayer? = null
    // Video output, given again to the player made after a pause
    private var view: TextureView? = null
    private var url: String? = null
    private var userAgent: String? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().apply {
        volume = 0f
        view?.let { setVideoTextureView(it) }
        playWhenReady = true
        trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(MAX_WIDTH, MAX_HEIGHT).build()
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                if (isPlaying) _failed.value = false
            }

            override fun onPlayerError(error: PlaybackException) {
                _failed.value = true
            }
        })
    }.also { player = it }

    /** Plays [streamUrl] muted; the same one keeps playing. */
    fun play(streamUrl: String, userAgent: String?, streamAuths: List<ApiKeyAuth>) {
        if (streamUrl == url && player != null) return
        url = streamUrl
        this.userAgent = userAgent
        auths = streamAuths
        _failed.value = false
        val dataSource = OkHttpDataSource.Factory(client).setUserAgent(userAgent?.takeIf { it.isNotBlank() } ?: HttpClients.DEFAULT_USER_AGENT)
        val item = MediaItem.fromUri(streamUrl)
        val source = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSource).setAllowChunklessPreparation(true).createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(dataSource, extractors).createMediaSource(item)
        }
        player().apply {
            setMediaSource(source)
            prepare()
            play()
        }
    }

    fun attach(output: TextureView) {
        view = output
        player?.setVideoTextureView(output)
    }

    fun detach(output: TextureView) {
        if (view === output) view = null
        player?.clearVideoTextureView(output)
    }


    // Kept while the app is in the background, to start again when it comes back
    private var paused: Triple<String, String?, List<ApiKeyAuth>>? = null

    /** App in the background: the connection is freed, [resume] plays the same channel again. */
    fun pause() {
        val current = url ?: return
        paused = Triple(current, userAgent, auths)
        release()
    }

    fun resume() {
        val (streamUrl, agent, streamAuths) = paused ?: return
        paused = null
        play(streamUrl, agent, streamAuths)
    }

    /** Stops it and frees its connection and decoder. */
    fun stop() {
        paused = null
        release()
    }

    private fun release() {
        url = null
        _playing.value = false
        _failed.value = false
        player?.release()
        player = null
    }

    private companion object {
        const val MAX_WIDTH = 854
        const val MAX_HEIGHT = 480
    }
}
