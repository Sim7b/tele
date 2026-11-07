package io.github.sim7b.tele.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.record.Recording
import io.github.sim7b.tele.data.record.RecordingState
import io.github.sim7b.tele.ui.components.DialogPanel
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.RecordDot
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Programs recorded on the device, the latest first, and the ones planned: OK opens the actions of one
 * (play it once something is written, delete it or cancel it).
 */
@Composable
fun RecordingsScreen(
    recordings: List<Recording>,
    onPlay: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val sorted = remember(recordings) { recordings.sortedByDescending { it.startMillis } }
    var menuFor by remember { mutableStateOf<Recording?>(null) }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    // The one opened last (back from its playback), else the first
    val focusTarget = sorted.firstOrNull { it.id == focusedId }?.id ?: sorted.firstOrNull()?.id
    LaunchedEffect(focusTarget != null) { if (focusTarget != null) firstFocus.requestFocusWithRetry() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
            .padding(horizontal = screenMargin(48.dp), vertical = 28.dp)
    ) {
        Text(
            text = stringResource(R.string.recordings_screen_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (sorted.isEmpty()) {
            Text(
                text = stringResource(R.string.recordings_empty),
                fontSize = 15.sp,
                color = TextSecondary,
                modifier = Modifier.widthIn(max = 640.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(sorted, key = { it.id }) { recording ->
                MenuRow(
                    label = recording.title,
                    subtitle = recordingLine(recording),
                    leading = if (recording.state == RecordingState.RECORDING) ({ RecordDot() }) else null,
                    onClick = {
                        focusedId = recording.id
                        menuFor = recording
                    },
                    modifier = if (recording.id == focusTarget) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }

    menuFor?.let { recording ->
        RecordingActionsDialog(
            recording = recording,
            onPlay = { onPlay(recording) },
            onDelete = { onDelete(recording) },
            onDismiss = {
                menuFor = null
                // Back on the list (the row may be gone with its recording)
                scope.launch { firstFocus.requestFocusWithRetry() }
            }
        )
    }
}

/** Channel, day and time of the program, then where its recording is. */
@Composable
private fun recordingLine(recording: Recording): String {
    val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(recording.startMillis))
    val time = "${EpgResolver.formatClock(recording.startMillis)}–${EpgResolver.formatClock(recording.endMillis)}"
    val state = when (recording.state) {
        RecordingState.SCHEDULED -> stringResource(R.string.recording_state_scheduled, day)
        RecordingState.RECORDING -> stringResource(R.string.recording_state_recording, sizeLabel(recording.bytes))
        RecordingState.DONE -> sizeLabel(recording.bytes)
        RecordingState.FAILED -> stringResource(R.string.recording_state_failed)
    }
    return "${recording.channelName} · $day $time · $state"
}

/** Size written, in MB under a GB, else in GB. */
internal fun sizeLabel(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1024) "%.0f MB".format(mb) else "%.1f GB".format(mb / 1024)
}

/** Something was written: it can be played (while still being recorded too). */
internal fun Recording.playable(): Boolean =
    bytes > 0 && (state == RecordingState.DONE || state == RecordingState.RECORDING)

@Composable
private fun RecordingActionsDialog(recording: Recording, onPlay: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { firstFocus.requestFocusWithRetry() }
        DialogPanel(width = 480.dp) {
            Text(text = recording.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            recording.description?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = it, fontSize = 13.sp, color = TextSecondary, maxLines = 4)
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (recording.playable()) {
                MenuRow(
                    label = stringResource(R.string.recording_play),
                    icon = Icons.Filled.PlayArrow,
                    onClick = {
                        onDismiss()
                        onPlay()
                    },
                    modifier = Modifier.focusRequester(firstFocus)
                )
            }
            MenuRow(
                label = stringResource(if (recording.state == RecordingState.SCHEDULED) R.string.program_cancel_recording else R.string.recording_delete),
                icon = Icons.Filled.Delete,
                onClick = {
                    onDismiss()
                    onDelete()
                },
                modifier = if (recording.playable()) Modifier else Modifier.focusRequester(firstFocus)
            )
        }
    }
}

/** The recordings need the exact alarms (Android 12+) to start while the app is closed: open the system page. */
@Composable
fun ExactAlarmsDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { firstFocus.requestFocusWithRetry() }
        DialogPanel(width = 480.dp) {
            Text(text = stringResource(R.string.recording_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.recording_exact_alarms), fontSize = 14.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(14.dp))
            MenuRow(label = stringResource(R.string.recording_exact_alarms_open), onClick = onOpen, modifier = Modifier.focusRequester(firstFocus))
            MenuRow(label = stringResource(R.string.recording_exact_alarms_later), onClick = onDismiss)
        }
    }
}
