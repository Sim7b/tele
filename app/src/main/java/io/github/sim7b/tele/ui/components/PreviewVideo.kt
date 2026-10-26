package io.github.sim7b.tele.ui.components

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sim7b.tele.playback.TvPlayerManager

/**
 * Video of the muted home preview, shown over the card once it actually plays (no black flash while the
 * stream starts). A TextureView, unlike the SurfaceView of the player, can sit inside a scrolling list.
 */
@Composable
fun PreviewVideo(playerManager: TvPlayerManager, modifier: Modifier = Modifier) {
    val playing by playerManager.isPlaying.collectAsState()
    val alpha by animateFloatAsState(targetValue = if (playing) 1f else 0f, label = "previewAlpha")
    AndroidView(
        factory = { context -> TextureView(context).also { playerManager.attachPreviewSurface(it) } },
        onRelease = { playerManager.detachPreviewSurface(it) },
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha)
    )
}
