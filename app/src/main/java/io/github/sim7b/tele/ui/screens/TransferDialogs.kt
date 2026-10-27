package io.github.sim7b.tele.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.storage.SettingsRepository
import io.github.sim7b.tele.data.transfer.ConfigTransfer
import io.github.sim7b.tele.data.transfer.TransferCrypto
import io.github.sim7b.tele.data.transfer.TransferPeer
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.crypto.AEADBadTagException

/** Frame of the transfer dialogs: title, content, Close row. */
@Composable
private fun TransferDialogFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceDark)
                .padding(horizontal = 26.dp, vertical = 22.dp)
        ) {
            Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Sending TV: shows the code and waits for the other TV; the configuration is served encrypted with
 * that code until it has been sent or the dialog is closed.
 */
@Composable
fun SendConfigDialog(repository: SettingsRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val code = remember { TransferCrypto.newCode() }
    val scope = rememberCoroutineScope()
    var sender by remember { mutableStateOf<ConfigTransfer.Sender?>(null) }
    var sent by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    val addresses = remember { ConfigTransfer.localAddresses() }

    LaunchedEffect(Unit) {
        val sealed = withContext(Dispatchers.Default) { TransferCrypto.seal(repository.exportSettings().toString(), code) }
        sender = ConfigTransfer.Sender(context, sealed) { sent = true }.also { it.start(scope) }
        closeFocus.requestFocusWithRetry()
    }
    // Served once: stopped as soon as the other TV has it, or when the dialog closes
    LaunchedEffect(sent) { if (sent) sender?.stop() }
    val currentSender by rememberUpdatedState(sender)
    DisposableEffect(Unit) { onDispose { currentSender?.stop() } }

    TransferDialogFrame(title = stringResource(R.string.transfer_send_title), onDismiss = onDismiss) {
        if (sent) {
            Text(text = stringResource(R.string.transfer_sent), fontSize = 15.sp, color = AccentCyan)
        } else {
            Text(text = stringResource(R.string.transfer_send_instructions), fontSize = 14.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = code.chunked(4).joinToString(" "),
                fontSize = 46.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp,
                color = TextPrimary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = TextSecondary, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = stringResource(R.string.transfer_waiting), fontSize = 13.sp, color = TextSecondary)
            }
            sender?.let { server ->
                if (addresses.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.transfer_address, addresses.joinToString("  ·  ") { "$it:${server.port}" }),
                        fontSize = 12.sp,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        MenuRow(label = stringResource(R.string.transfer_close), onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus))
    }
}

private sealed interface ReceiveStep {
    data object Choose : ReceiveStep
    data object TypeAddress : ReceiveStep
    data class TypeCode(val address: String, val name: String?) : ReceiveStep
    data object Importing : ReceiveStep
    data class Done(val sources: Int) : ReceiveStep
}

/**
 * Receiving TV: lists the TVs offering their configuration (or takes an address), then asks for the
 * code shown on the other TV, downloads, decrypts and applies the configuration.
 */
@Composable
fun ReceiveConfigDialog(repository: SettingsRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<ReceiveStep>(ReceiveStep.Choose) }
    var error by remember { mutableStateOf<String?>(null) }
    val peers = remember { mutableStateListOf<TransferPeer>() }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        ConfigTransfer.discover(context).collect { peer ->
            if (peers.none { it.address == peer.address }) peers += peer
        }
    }
    LaunchedEffect(step) {
        error = null
        firstFocus.requestFocusWithRetry()
    }

    val wrongCode = stringResource(R.string.transfer_error_code)
    val unreachable = stringResource(R.string.transfer_error_unreachable)
    val invalid = stringResource(R.string.transfer_error_invalid)

    fun import(address: String, code: String) {
        step = ReceiveStep.Importing
        scope.launch {
            try {
                val sealed = ConfigTransfer.fetch(address)
                val json = withContext(Dispatchers.Default) { JSONObject(TransferCrypto.open(sealed, code)) }
                repository.importSettings(json)
                step = ReceiveStep.Done(json.optJSONArray("sources")?.length() ?: 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AEADBadTagException) {
                step = ReceiveStep.TypeCode(address, null)
                error = wrongCode
            } catch (e: java.io.IOException) {
                step = ReceiveStep.TypeCode(address, null)
                error = unreachable
            } catch (e: Exception) {
                step = ReceiveStep.TypeCode(address, null)
                error = invalid
            }
        }
    }

    TransferDialogFrame(title = stringResource(R.string.transfer_receive_title), onDismiss = onDismiss) {
        when (val current = step) {
            ReceiveStep.Choose -> {
                Text(text = stringResource(R.string.transfer_receive_instructions), fontSize = 14.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                if (peers.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp)) {
                        CircularProgressIndicator(color = TextSecondary, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = stringResource(R.string.transfer_searching), fontSize = 13.sp, color = TextSecondary)
                    }
                }
                peers.forEachIndexed { index, peer ->
                    MenuRow(
                        label = peer.name,
                        subtitle = peer.address,
                        icon = Icons.Filled.Home,
                        onClick = { step = ReceiveStep.TypeCode(peer.address, peer.name) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
                MenuRow(
                    label = stringResource(R.string.transfer_manual_address),
                    icon = Icons.Filled.Edit,
                    onClick = { step = ReceiveStep.TypeAddress },
                    modifier = if (peers.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
                )
                MenuRow(label = stringResource(R.string.transfer_close), onClick = onDismiss)
            }
            ReceiveStep.TypeAddress -> {
                Text(text = stringResource(R.string.transfer_address_instructions), fontSize = 14.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                TransferTextField(
                    placeholder = "192.168.0.10:40000",
                    keyboardType = KeyboardType.Uri,
                    focusRequester = firstFocus,
                    onDone = { address -> if (address.isNotBlank()) step = ReceiveStep.TypeCode(address.trim(), null) }
                )
            }
            is ReceiveStep.TypeCode -> {
                Text(
                    text = stringResource(R.string.transfer_code_instructions, current.name ?: current.address),
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                TransferTextField(
                    placeholder = "1234 5678",
                    keyboardType = KeyboardType.NumberPassword,
                    focusRequester = firstFocus,
                    onDone = { code ->
                        if (TransferCrypto.normalizeCode(code).length == TransferCrypto.CODE_LENGTH) import(current.address, code)
                    }
                )
            }
            ReceiveStep.Importing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = TextSecondary, strokeWidth = 1.5.dp, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = stringResource(R.string.transfer_importing), fontSize = 14.sp, color = TextSecondary)
                }
            }
            is ReceiveStep.Done -> {
                Text(
                    text = pluralStringResource(R.plurals.transfer_imported, current.sources, current.sources),
                    fontSize = 15.sp,
                    color = AccentCyan
                )
                Spacer(modifier = Modifier.height(14.dp))
                MenuRow(label = stringResource(R.string.transfer_close), onClick = onDismiss, modifier = Modifier.focusRequester(firstFocus))
            }
        }
        error?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = it, fontSize = 13.sp, color = LiveRed.copy(alpha = 0.9f))
        }
    }
}

/** Text field typed with the TV keyboard; its OK / Done key validates. */
@Composable
private fun TransferTextField(
    placeholder: String,
    keyboardType: KeyboardType,
    focusRequester: FocusRequester,
    onDone: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    val accent = LocalAppColors.current.accent
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 20.sp, letterSpacing = 2.sp),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(value) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard)
                    .border(2.dp, accent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (value.isEmpty()) Text(text = placeholder, fontSize = 20.sp, color = TextSecondary.copy(alpha = 0.5f))
                inner()
            }
        }
    )
}
