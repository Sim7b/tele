package io.github.sim7b.tele.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.update.AppRelease
import io.github.sim7b.tele.ui.theme.SurfaceDark
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/**
 * A newer version on GitHub: install it now (downloaded with its [progress] shown, then confirmed in the
 * system installer), or ignore this version (not offered again). Back closes it until the next start.
 */
@Composable
fun UpdateReadyDialog(
    release: AppRelease,
    progress: Float?,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit
) {
    val installFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { installFocus.requestFocusWithRetry() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .dialogWidth(520.dp)
                .clip(shape)
                .background(SurfaceDark)
                .border(1.dp, TextPrimary.copy(alpha = 0.08f), shape)
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.update_prompt_title, release.version),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(text = stringResource(R.string.update_prompt_text), fontSize = 13.sp, color = TextSecondary)
            if (release.highlights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                release.highlights.forEach { point ->
                    Text(text = "·  $point", fontSize = 12.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            MenuRow(
                label = if (progress != null) {
                    stringResource(R.string.settings_update_downloading, (progress * 100).toInt())
                } else {
                    stringResource(R.string.update_prompt_install)
                },
                icon = Icons.Filled.Refresh,
                onClick = onInstall,
                modifier = Modifier.focusRequester(installFocus)
            )
            MenuRow(label = stringResource(R.string.update_prompt_ignore), icon = Icons.Filled.Close, onClick = onIgnore)
        }
    }
}
