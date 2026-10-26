package io.github.sim7b.tele.ui.components

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.ChannelNumbers
import kotlinx.coroutines.delay

private const val COMMIT_DELAY_MS = 1_500L
private const val NOT_FOUND_DISPLAY_MS = 1_800L

/** Digit of a remote key (top row or numeric keypad), or null. */
private fun digitOf(keyCode: Int): Int? = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

/**
 * Channel number typed with the remote digits: opened 1.5 s after the last digit, or right away with OK.
 * Feed it the remote keys with [onKey] and show it with [ChannelNumberBadge].
 */
@Stable
class ChannelNumberEntry internal constructor() {
    var typed by mutableStateOf("")
        private set
    internal var commitPending by mutableStateOf(false)
    internal var notFound by mutableStateOf<Int?>(null)

    // OK confirmed the number on key down: its key up must not also click the focused item
    private var swallowOkUp = false

    val isTyping: Boolean get() = typed.isNotEmpty()

    fun cancel() {
        typed = ""
        commitPending = false
    }

    /** Handles a remote key event (down and up); true if consumed. */
    fun onKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val isDown = native.action == KeyEvent.ACTION_DOWN
        digitOf(native.keyCode)?.let { digit ->
            if (isDown && native.repeatCount == 0) {
                typed = ChannelNumbers.append(typed, digit)
                notFound = null
            }
            return true
        }
        val isOk = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == KeyEvent.KEYCODE_ENTER ||
            native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!isOk) return false
        if (isDown && isTyping) {
            commitPending = true
            swallowOkUp = true
            return true
        }
        if (!isDown && swallowOkUp) {
            swallowOkUp = false
            return true
        }
        return false
    }
}

/** Entry opening the channel of the typed number among [channels] with [onChannel]. */
@Composable
fun rememberChannelNumberEntry(channels: List<Channel>, onChannel: (Channel) -> Unit): ChannelNumberEntry {
    val entry = remember { ChannelNumberEntry() }
    val currentChannels by rememberUpdatedState(channels)
    val currentOnChannel by rememberUpdatedState(onChannel)

    LaunchedEffect(entry.typed, entry.commitPending) {
        val typed = entry.typed
        if (typed.isEmpty()) return@LaunchedEffect
        if (!entry.commitPending) delay(COMMIT_DELAY_MS)
        val number = typed.toInt()
        entry.cancel()
        val channel = ChannelNumbers.find(currentChannels, number)
        if (channel != null) currentOnChannel(channel) else entry.notFound = number
    }
    LaunchedEffect(entry.notFound) {
        if (entry.notFound != null) {
            delay(NOT_FOUND_DISPLAY_MS)
            entry.notFound = null
        }
    }
    return entry
}

/** Typed number at the top right, with the name of the matching channel (or "no channel"). */
@Composable
fun ChannelNumberBadge(entry: ChannelNumberEntry, channels: List<Channel>, modifier: Modifier = Modifier) {
    val notFound = entry.notFound
    AnimatedVisibility(
        visible = entry.isTyping || notFound != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val typed = entry.typed
        val preview = remember(typed, channels) {
            typed.toIntOrNull()?.let { ChannelNumbers.find(channels, it) }
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .widthIn(min = 120.dp, max = 360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Text(
                text = typed.ifEmpty { notFound?.toString().orEmpty() },
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Light
            )
            Text(
                text = when {
                    typed.isEmpty() && notFound != null -> stringResource(R.string.channel_number_not_found, notFound)
                    else -> preview?.name.orEmpty()
                },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
