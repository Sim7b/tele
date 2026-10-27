package io.github.sim7b.tele.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.SurfaceDark
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/**
 * Channels the reference bouquet does not know, to spot a missing spelling. The rows are focusable so
 * that the remote scrolls the list.
 */
@Composable
fun UnmatchedChannelsDialog(names: List<String>, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocus.requestFocusWithRetry() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceDark)
                .padding(horizontal = 26.dp, vertical = 22.dp)
        ) {
            Text(text = stringResource(R.string.unmatched_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = stringResource(R.string.unmatched_description), fontSize = 12.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(12.dp))
            MenuRow(
                label = stringResource(R.string.unmatched_close),
                icon = Icons.Filled.Close,
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeFocus)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(names) { name -> MenuRow(label = name, onClick = {}) }
            }
        }
    }
}
