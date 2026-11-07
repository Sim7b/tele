package io.github.sim7b.tele.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.trakt.Trakt
import io.github.sim7b.tele.data.trakt.TraktApp
import io.github.sim7b.tele.data.trakt.TraktDeviceCode
import io.github.sim7b.tele.data.trakt.TraktSession
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.QrCode
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Linking of the Trakt account: a code to type on trakt.tv/activate (from a phone, the QR code opens it),
 * polled until it is typed, refused or expired.
 */
@Composable
fun TraktLinkDialog(app: TraktApp, onLinked: (TraktSession) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf<TraktDeviceCode?>(null) }
    var linked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val closeFocus = remember { FocusRequester() }
    val currentLinked by rememberUpdatedState(onLinked)
    val failed = stringResource(R.string.trakt_error)
    val expired = stringResource(R.string.trakt_expired)
    val denied = stringResource(R.string.trakt_denied)

    LaunchedEffect(Unit) {
        closeFocus.requestFocusWithRetry()
        try {
            val device = Trakt.deviceCode(app)
            code = device
            while (System.currentTimeMillis() < device.expiresAt) {
                delay(device.intervalSeconds * 1000L)
                when (val poll = Trakt.poll(app, device)) {
                    is Trakt.Poll.Linked -> {
                        currentLinked(poll.session)
                        linked = true
                        return@LaunchedEffect
                    }
                    Trakt.Poll.Expired -> break
                    Trakt.Poll.Denied -> {
                        error = denied
                        return@LaunchedEffect
                    }
                    Trakt.Poll.Pending -> Unit
                }
            }
            error = expired
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = failed
        }
    }

    TransferDialogFrame(title = stringResource(R.string.settings_trakt_account), onDismiss = onDismiss) {
        val current = code
        when {
            linked -> Text(text = stringResource(R.string.trakt_linked), fontSize = 15.sp, color = AccentCyan)
            current != null && error == null -> {
                Text(text = stringResource(R.string.trakt_instructions, current.verificationUrl), fontSize = 14.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QrCode(text = current.verificationUrl, size = 120.dp)
                    Spacer(modifier = Modifier.width(24.dp))
                    Text(text = current.userCode, fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = TextSecondary, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = stringResource(R.string.trakt_waiting), fontSize = 13.sp, color = TextSecondary)
                }
            }
            error == null -> CircularProgressIndicator(color = TextSecondary, strokeWidth = 1.5.dp, modifier = Modifier.size(16.dp))
        }
        error?.let { Text(text = it, fontSize = 13.sp, color = LiveRed.copy(alpha = 0.9f)) }
        Spacer(modifier = Modifier.height(16.dp))
        MenuRow(label = stringResource(R.string.transfer_close), onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus))
    }
}
