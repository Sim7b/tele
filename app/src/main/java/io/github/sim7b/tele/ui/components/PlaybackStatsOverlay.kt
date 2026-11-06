package io.github.sim7b.tele.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.playback.PlaybackStats
import io.github.sim7b.tele.playback.TvPlayerManager
import kotlinx.coroutines.delay
import java.util.Locale

/** Playback statistics over the picture (to diagnose a stream that stutters), read every second. */
@Composable
fun PlaybackStatsOverlay(playerManager: TvPlayerManager, modifier: Modifier = Modifier) {
    val stats by produceState<PlaybackStats?>(null, playerManager) {
        while (true) {
            value = playerManager.playbackStats()
            delay(STATS_INTERVAL_MS)
        }
    }
    val current = stats ?: return
    val unknown = "—"
    val rows = listOf(
        stringResource(R.string.stats_video) to listOfNotNull(current.resolution, current.fps?.let { fpsLabel(it) }, current.videoCodec)
            .joinToString(" · ").ifEmpty { unknown },
        stringResource(R.string.stats_audio) to (current.audioCodec ?: unknown),
        stringResource(R.string.stats_bitrate) to (current.videoBitrateKbps?.let(::bitrateLabel) ?: unknown),
        stringResource(R.string.stats_bandwidth) to (current.bandwidthKbps?.let(::bitrateLabel) ?: unknown),
        stringResource(R.string.stats_buffer) to bufferLabel(current.bufferMs),
        stringResource(R.string.stats_dropped) to current.droppedFrames.toString()
    )
    Column(
        modifier = modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        rows.forEach { (label, value) ->
            Row {
                Text(text = label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.width(150.dp))
                Text(text = value, fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** "8.4 Mb/s" from 1,000 kb/s up, else "850 kb/s". */
internal fun bitrateLabel(kbps: Int): String =
    if (kbps >= 1000) String.format(Locale.ROOT, "%.1f Mb/s", kbps / 1000f) else "$kbps kb/s"

/** Seconds of buffer ahead, with a decimal: "12.5 s". */
internal fun bufferLabel(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms / 1000f)

/** "25 fps", "29.97 fps". */
internal fun fpsLabel(fps: Float): String =
    (if (fps % 1f == 0f) fps.toInt().toString() else String.format(Locale.ROOT, "%.2f", fps)) + " fps"

private const val STATS_INTERVAL_MS = 1_000L
