package io.github.sim7b.tele.ui.components

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
 */
@Composable
fun PlaybackOptionsPanel(
    tracks: PlaybackTracks,
    pictureFormat: PictureFormat,
    onChooseStream: () -> Unit,
    onSelectAudio: (TrackChoice) -> Unit,
    onSelectSubtitles: (TrackChoice?) -> Unit,
    onSelectPictureFormat: (PictureFormat) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstRow = remember { FocusRequester() }
    var interactions by remember { mutableIntStateOf(0) }
    val locale = LocalConfiguration.current.locales[0]

    LaunchedEffect(interactions) {
        delay(OPTIONS_AUTO_HIDE_MS)
        onDismiss()
    }
    LaunchedEffect(Unit) { firstRow.requestFocusWithRetry() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(OptionsPanelWidth + OptionsFadeWidth)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.3f to Color.Black.copy(alpha = 0.78f),
                    1f to Color.Black.copy(alpha = 0.92f)
                )
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
                .width(OptionsPanelWidth)
                .fillMaxHeight()
                .padding(start = 12.dp, end = 32.dp),
            contentPadding = PaddingValues(vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.options_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 14.dp, bottom = 10.dp)
                )
                MenuRow(
                    label = stringResource(R.string.options_choose_stream),
                    icon = Icons.Filled.List,
                    onClick = onChooseStream,
                    modifier = Modifier.focusRequester(firstRow)
                )
            }

            if (tracks.audio.isNotEmpty()) {
                item { OptionsHeader(R.string.options_audio) }
                items(tracks.audio, key = { "a${it.groupIndex}/${it.trackIndex}" }) { choice ->
                    ChoiceRow(label = trackLabel(choice, locale, isAudio = true), selected = choice.selected) { onSelectAudio(choice) }
                }
            }

            item {
                OptionsHeader(R.string.options_subtitles)
                ChoiceRow(label = stringResource(R.string.options_subtitles_off), selected = !tracks.subtitlesOn) { onSelectSubtitles(null) }
            }
            items(tracks.subtitles, key = { "s${it.groupIndex}/${it.trackIndex}" }) { choice ->
                ChoiceRow(label = trackLabel(choice, locale, isAudio = false), selected = choice.selected) { onSelectSubtitles(choice) }
            }
            if (tracks.subtitles.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.options_no_subtitles),
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                    )
                }
            }

            item { OptionsHeader(R.string.options_picture) }
            items(PictureFormat.entries) { format ->
                ChoiceRow(label = stringResource(format.labelRes), selected = format == pictureFormat) { onSelectPictureFormat(format) }
            }
        }
    }
}

@Composable
private fun OptionsHeader(@StringRes title: Int) {
    SectionLabel(text = stringResource(title), modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    MenuRow(label = label, onClick = onClick, leading = { focused -> SelectionDot(selected = selected, focused = focused) })
}

/** "Français · Stéréo", "English · 5.1", the track label, or "Track 2". */
@Composable
private fun trackLabel(choice: TrackChoice, locale: Locale, isAudio: Boolean): String {
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
