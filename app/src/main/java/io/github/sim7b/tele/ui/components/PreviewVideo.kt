package io.github.sim7b.tele.ui.components

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sim7b.tele.playback.TvPlayerManager

/**
 * Video of the muted preview, shown over the card once it actually plays (no black flash while the
 * stream starts). A TextureView, unlike the SurfaceView of the player, can sit inside a scrolling list.
 * The picture keeps its 16:9 shape: it covers the space given, cropped (never stretched), held to [alignment].
 */
@Composable
fun PreviewVideo(playerManager: TvPlayerManager, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    val playing by playerManager.isPlaying.collectAsState()
    val alpha by animateFloatAsState(targetValue = if (playing) 1f else 0f, label = "previewAlpha")
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds(), contentAlignment = alignment) {
        val size = coverSize(maxWidth, maxHeight)
        AndroidView(
            factory = { context -> TextureView(context).also { playerManager.attachPreviewSurface(it) } },
            onRelease = { playerManager.detachPreviewSurface(it) },
            modifier = Modifier
                .requiredSize(size)
                .alpha(alpha)
        )
    }
}

/** Smallest 16:9 size covering [width] × [height] (the space itself when it is already 16:9). */
internal fun coverSize(width: Dp, height: Dp, aspect: Float = 16f / 9f): DpSize = when {
    height == Dp.Infinity || height.value <= 0f -> DpSize(width, width / aspect)
    width == Dp.Infinity -> DpSize(height * aspect, height)
    else ->
        if (width / height > aspect) DpSize(width, width / aspect) else DpSize(height * aspect, height)
}
