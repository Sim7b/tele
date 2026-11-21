package io.github.sim7b.tele.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import io.github.sim7b.tele.data.remote.HandoffItem
import io.github.sim7b.tele.data.remote.HandoffTv
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/**
 * What another TV of the user plays ([sent]: false) or sent here ([sent]: true), offered to resume here: the channel,
 * or the movie or episode at its position.
 */
@Composable
fun HandoffDialog(tv: HandoffTv, item: HandoffItem, sent: Boolean, onResume: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { focus.requestFocusWithRetry() }
        DialogPanel(width = 480.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Filled.Share, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(if (sent) R.string.handoff_sent_title else R.string.handoff_playing_title),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            val where = tv.name ?: stringResource(R.string.handoff_other_tv)
            val position = if (item.kind == HandoffItem.VOD && item.positionMs > 0) " · ${clockLabel(item.positionMs)}" else ""
            Text(
                text = stringResource(if (sent) R.string.handoff_sent_message else R.string.handoff_playing_message, where, item.title + position),
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            MenuRow(
                label = stringResource(R.string.handoff_resume_here),
                icon = Icons.Filled.PlayArrow,
                onClick = {
                    onDismiss()
                    onResume()
                },
                modifier = Modifier.focusRequester(focus)
            )
            MenuRow(label = stringResource(R.string.handoff_dismiss), icon = Icons.Filled.Close, onClick = onDismiss)
        }
    }
}
