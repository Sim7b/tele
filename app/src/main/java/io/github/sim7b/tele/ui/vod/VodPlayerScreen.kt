package io.github.sim7b.tele.ui.vod

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.playback.TvPlayerManager
import io.github.sim7b.tele.ui.theme.AccentCyan
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Plays a movie or an episode with the Media3 controls (play / pause, seek, audio and subtitle tracks),
 * from [VodPlayRequest.startMs]. The position is reported every few seconds and when leaving.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(
    request: VodPlayRequest,
    playerManager: TvPlayerManager,
    streamAuths: List<ApiKeyAuth>,
    userAgent: String?,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLoading by playerManager.isLoading.collectAsState()
    val error by playerManager.error.collectAsState()
    val reportProgress by rememberUpdatedState(onProgress)

    fun report() {
        val player = playerManager.getPlayer()
        val duration = player.duration
        if (duration > 0 && player.currentPosition > 0) reportProgress(player.currentPosition, duration)
    }

    DisposableEffect(request) {
        playerManager.playVod(request.url, request.startMs, userAgent, streamAuths, request.fallbacks)
        onDispose {
            report()
            playerManager.stop()
        }
    }
    LaunchedEffect(request) {
        while (isActive) {
            delay(PROGRESS_INTERVAL_MS)
            report()
        }
    }
    BackHandler {
        report()
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = playerManager.getPlayer()
                    useController = true
                    controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                    // The remote drives the controls: the view must hold the focus
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isLoading && error == null) {
            CircularProgressIndicator(color = AccentCyan, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(42.dp))
        }
        error?.let { message ->
            Column(modifier = Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = request.label, fontSize = 18.sp, color = Color.White)
                Text(text = message.resolve(), fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private const val PROGRESS_INTERVAL_MS = 10_000L
private const val CONTROLLER_TIMEOUT_MS = 4_000
