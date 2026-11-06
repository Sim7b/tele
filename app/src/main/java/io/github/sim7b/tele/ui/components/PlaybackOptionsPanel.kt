package io.github.sim7b.tele.ui.components

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.playback.TimeshiftState
import io.github.sim7b.tele.playback.PictureFormat
import io.github.sim7b.tele.playback.PlaybackTracks
import io.github.sim7b.tele.playback.TrackChoice
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.util.Locale

private val OptionsPanelWidth = 400.dp
private val OptionsFadeWidth = 120.dp
private const val OPTIONS_AUTO_HIDE_MS = 20_000L

/**
 * Playback options shown over the live stream (right arrow in the player), mirroring the channel panel:
 * other stream, audio track, subtitles and picture format. Back or inactivity closes it.
 * Movies and series have no stream choice ([onChooseStream] null) but a playback [speed].
 */
@Composable
fun PlaybackOptionsPanel(
    tracks: PlaybackTracks,
    pictureFormat: PictureFormat,
    onChooseStream: (() -> Unit)?,
    onSelectAudio: (TrackChoice) -> Unit,
    onSelectSubtitles: (TrackChoice?) -> Unit,
    onSelectPictureFormat: (PictureFormat) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    speed: Float? = null,
    onSelectSpeed: (Float) -> Unit = {},
    // Movies and series with an OpenSubtitles key: subtitles searched online
    onSearchSubtitles: (() -> Unit)? = null,
    // Playback statistics over the picture: shown or not
    statsOn: Boolean? = null,
    onToggleStats: () -> Unit = {},
    // Channel shown in a corner (null: none): swapped with the main picture, or closed
    insetName: String? = null,
    onSwapInset: () -> Unit = {},
    onCloseInset: () -> Unit = {},
    // Live channel kept on the device (null: none): pause, back, forward, to live
    timeshift: TimeshiftState? = null,
    onTimeshiftPlayPause: () -> Unit = {},
    onTimeshiftSeek: (deltaMs: Long) -> Unit = {},
    onTimeshiftLive: () -> Unit = {}
) {
    val firstRow = remember { FocusRequester() }
    var interactions by remember { mutableIntStateOf(0) }
    val locale = LocalConfiguration.current.locales[0]

    LaunchedEffect(interactions) {
        delay(OPTIONS_AUTO_HIDE_MS)
        onDismiss()
    }
    LaunchedEffect(Unit) { firstRow.requestFocusWithRetry() }
    // Phone: the whole width, without the fade over the picture
    val compact = LocalScreen.current.compact

    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(OptionsPanelWidth + OptionsFadeWidth))
            .background(
                if (compact) {
                    SolidColor(Color.Black.copy(alpha = 0.92f))
                } else {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.3f to Color.Black.copy(alpha = 0.78f),
                        1f to Color.Black.copy(alpha = 0.92f)
                    )
                }
            )
            .onPreviewKeyEvent { event ->
                interactions++
                // Back closes the panel at once (else Compose first moves the focus out of the list)
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.type == KeyEventType.KeyUp) onDismiss()
                    true
                } else {
                    false
                }
            }
    ) {
        LazyColumn(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.CenterEnd)
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(OptionsPanelWidth))
                .fillMaxHeight()
                // Touch: taps here stay in the panel (beside it, they close it)
                .onAnyTouch { interactions++ }
                .blockTouches()
                .padding(start = 12.dp, end = screenMargin(32.dp)),
            contentPadding = PaddingValues(vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.options_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp, bottom = 10.dp)
                    )
                    if (Device.isTouch) {
                        RoundIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.vod_close),
                            onClick = onDismiss,
                            size = 36.dp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                }
                if (onChooseStream != null) {
                    MenuRow(
                        label = stringResource(R.string.options_choose_stream),
                        icon = Icons.Filled.List,
                        onClick = onChooseStream,
                        modifier = Modifier.focusRequester(firstRow)
                    )
                }
            }

            // Without a stream choice, the first choice of the panel takes the focus
            val audioFirst = onChooseStream == null && tracks.audio.isNotEmpty()
            if (tracks.audio.isNotEmpty()) {
                item { OptionsHeader(R.string.options_audio) }
                items(tracks.audio, key = { "a${it.groupIndex}/${it.trackIndex}" }) { choice ->
                    ChoiceRow(
                        label = trackLabel(choice, locale, isAudio = true),
                        selected = choice.selected,
                        modifier = if (audioFirst && choice == tracks.audio.first()) Modifier.focusRequester(firstRow) else Modifier
                    ) { onSelectAudio(choice) }
                }
            }

            item {
                OptionsHeader(R.string.options_subtitles)
                ChoiceRow(
                    label = stringResource(R.string.options_subtitles_off),
                    selected = !tracks.subtitlesOn,
                    modifier = if (onChooseStream == null && !audioFirst) Modifier.focusRequester(firstRow) else Modifier
                ) { onSelectSubtitles(null) }
            }
            items(tracks.subtitles, key = { "s${it.groupIndex}/${it.trackIndex}" }) { choice ->
                ChoiceRow(label = trackLabel(choice, locale, isAudio = false), selected = choice.selected) { onSelectSubtitles(choice) }
            }
            if (onSearchSubtitles != null) {
                item {
                    MenuRow(label = stringResource(R.string.subtitles_search), icon = Icons.Filled.Search, onClick = onSearchSubtitles)
                }
            }
            if (tracks.subtitles.isEmpty() && onSearchSubtitles == null) {
                item {
                    Text(
                        text = stringResource(R.string.options_no_subtitles),
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                    )
                }
            }

            if (speed != null) {
                item { OptionsHeader(R.string.options_speed) }
                items(PLAYBACK_SPEEDS) { value ->
                    ChoiceRow(
                        label = if (value == 1f) stringResource(R.string.options_speed_normal) else speedLabel(value),
                        selected = value == speed
                    ) { onSelectSpeed(value) }
                }
            }

            item { OptionsHeader(R.string.options_picture) }
            items(PictureFormat.entries) { format ->
                ChoiceRow(label = stringResource(format.labelRes), selected = format == pictureFormat) { onSelectPictureFormat(format) }
            }
            if (timeshift != null) {
                item {
                    OptionsHeader(R.string.timeshift_title)
                    Text(
                        text = if (timeshift.behindMs >= TIMESHIFT_LIVE_MS) {
                            stringResource(R.string.timeshift_status_behind, clockLabel(timeshift.behindMs), clockLabel(timeshift.windowMs))
                        } else {
                            stringResource(R.string.timeshift_status_live, clockLabel(timeshift.windowMs))
                        },
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 14.dp, bottom = 4.dp)
                    )
                    MenuRow(label = stringResource(if (timeshift.paused) R.string.timeshift_resume else R.string.timeshift_pause), onClick = onTimeshiftPlayPause)
                    MenuRow(label = stringResource(R.string.timeshift_back, 30), onClick = { onTimeshiftSeek(-30_000L) })
                    MenuRow(label = stringResource(R.string.timeshift_back_minutes, 5), onClick = { onTimeshiftSeek(-300_000L) })
                    if (timeshift.behindMs >= TIMESHIFT_LIVE_MS) {
                        MenuRow(label = stringResource(R.string.timeshift_forward, 30), onClick = { onTimeshiftSeek(30_000L) })
                        MenuRow(label = stringResource(R.string.timeshift_live), onClick = onTimeshiftLive)
                    }
                }
            }
            if (insetName != null) {
                item {
                    OptionsHeader(R.string.inset_title)
                    MenuRow(label = stringResource(R.string.inset_swap, insetName), onClick = onSwapInset)
                    MenuRow(label = stringResource(R.string.inset_close), onClick = onCloseInset)
                }
            }
            if (statsOn != null) {
                item {
                    OptionsHeader(R.string.options_stats)
                    ChoiceRow(label = stringResource(R.string.options_stats_show), selected = statsOn, onClick = onToggleStats)
                }
            }
        }
    }
}

@Composable
private fun OptionsHeader(@StringRes title: Int) {
    SectionLabel(text = stringResource(title), modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MenuRow(label = label, onClick = onClick, modifier = modifier, leading = { focused -> SelectionDot(selected = selected, focused = focused) })
}

/** Speeds offered for movies and series. */
private val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** "×1.25", with the decimal separator of the TV's language. */
@Composable
private fun speedLabel(value: Float): String =
    "×" + java.text.NumberFormat.getInstance(LocalConfiguration.current.locales[0]).format(value)

/** "Français · Stéréo", "English · 5.1", the track label, or "Track 2". */
@Composable
internal fun trackLabel(choice: TrackChoice, locale: Locale, isAudio: Boolean): String {
    val language = choice.language?.let { tag ->
        Locale.forLanguageTag(tag).getDisplayLanguage(locale).takeIf { it.isNotBlank() && it != tag }
            ?.replaceFirstChar { it.titlecase(locale) } ?: tag
    }
    val channels = if (!isAudio) null else when (choice.channelCount) {
        null -> null
        1 -> stringResource(R.string.options_mono)
        2 -> stringResource(R.string.options_stereo)
        6 -> "5.1"
        8 -> "7.1"
        else -> stringResource(R.string.options_channels, choice.channelCount)
    }
    val name = choice.label?.takeIf { it != language } ?: language
    return listOfNotNull(name, channels).joinToString(" · ")
        .ifEmpty { stringResource(R.string.options_track, choice.trackIndex + 1) }
}

/** Under this, the channel plays live. */
internal const val TIMESHIFT_LIVE_MS = 2_000L

/** "2:05", "1:02:05": a time behind live, or kept. */
internal fun clockLabel(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
