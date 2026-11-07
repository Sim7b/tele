package io.github.sim7b.tele.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.storage.SettingsRepository
import io.github.sim7b.tele.data.transfer.BackupFile
import io.github.sim7b.tele.data.transfer.TransferCrypto
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import javax.crypto.AEADBadTagException

/**
 * Backup in a file: shows the code that protects it (to note down), then the system lets the user choose
 * where to save it (a USB key, the storage of the TV). A TV without a file picker is told so.
 */
@Composable
fun BackupExportDialog(repository: SettingsRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val code = remember { TransferCrypto.newCode() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val failed = stringResource(R.string.backup_error_write)
    val noPicker = stringResource(R.string.backup_error_no_picker)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val content = BackupFile.seal(repository.exportSettings().toString(), code)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("No output")
                }
                saved = true
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = failed
            }
        }
    }
    LaunchedEffect(saved) { firstFocus.requestFocusWithRetry() }

    TransferDialogFrame(title = stringResource(R.string.backup_export_title), onDismiss = onDismiss) {
        Text(
            text = stringResource(if (saved) R.string.backup_saved else R.string.backup_export_instructions),
            fontSize = if (saved) 15.sp else 14.sp,
            color = if (saved) AccentCyan else TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = code.chunked(4).joinToString(" "),
            fontSize = 46.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp,
            color = TextPrimary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(18.dp))
        if (!saved) {
            MenuRow(
                label = stringResource(R.string.backup_choose_file),
                icon = Icons.Filled.Edit,
                onClick = {
                    try {
                        launcher.launch("tele-backup-${LocalDate.now()}.json")
                    } catch (e: ActivityNotFoundException) {
                        error = noPicker
                    }
                },
                modifier = Modifier.focusRequester(firstFocus)
            )
        }
        MenuRow(
            label = stringResource(R.string.transfer_close),
            onClick = onDismiss,
            modifier = if (saved) Modifier.focusRequester(firstFocus) else Modifier
        )
        error?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = it, fontSize = 13.sp, color = LiveRed.copy(alpha = 0.9f))
        }
    }
}

/** Restore from a backup file: the system file picker, then the code of the backup; the configuration is applied. */
@Composable
fun BackupImportDialog(repository: SettingsRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Content of the file chosen (null: none yet), then the number of sources restored
    var content by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val wrongCode = stringResource(R.string.transfer_error_code)
    val invalid = stringResource(R.string.backup_error_invalid)
    val noPicker = stringResource(R.string.backup_error_no_picker)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { BackupFile.read(it) } ?: throw IOException("No input")
                }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = invalid
            }
        }
    }
    LaunchedEffect(content, restored, busy) { if (!busy) firstFocus.requestFocusWithRetry() }

    fun restore(file: String, code: String) {
        busy = true
        scope.launch {
            try {
                val json = withContext(Dispatchers.Default) { BackupFile.open(file, code) }
                repository.importSettings(json)
                restored = json.optJSONArray("sources")?.length() ?: 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: AEADBadTagException) {
                error = wrongCode
            } catch (e: Exception) {
                error = invalid
                content = null
            } finally {
                busy = false
            }
        }
    }

    TransferDialogFrame(title = stringResource(R.string.backup_import_title), onDismiss = onDismiss) {
        val file = content
        val count = restored
        when {
            count != null -> {
                Text(text = pluralStringResource(R.plurals.transfer_imported, count, count), fontSize = 15.sp, color = AccentCyan)
                Spacer(modifier = Modifier.height(14.dp))
                MenuRow(label = stringResource(R.string.transfer_close), onClick = onDismiss, modifier = Modifier.focusRequester(firstFocus))
            }
            busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = TextSecondary, strokeWidth = 1.5.dp, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = stringResource(R.string.transfer_importing), fontSize = 14.sp, color = TextSecondary)
            }
            file != null -> {
                Text(text = stringResource(R.string.backup_code_instructions), fontSize = 14.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                TransferTextField(
                    placeholder = "1234 5678",
                    keyboardType = KeyboardType.NumberPassword,
                    focusRequester = firstFocus,
                    onDone = { code -> if (TransferCrypto.normalizeCode(code).length == TransferCrypto.CODE_LENGTH) restore(file, code) }
                )
            }
            else -> {
                Text(text = stringResource(R.string.backup_import_instructions), fontSize = 14.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                MenuRow(
                    label = stringResource(R.string.backup_choose_file),
                    icon = Icons.Filled.Edit,
                    onClick = {
                        try {
                            launcher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                        } catch (e: ActivityNotFoundException) {
                            error = noPicker
                        }
                    },
                    modifier = Modifier.focusRequester(firstFocus)
                )
                MenuRow(label = stringResource(R.string.transfer_close), onClick = onDismiss)
            }
        }
        error?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = it, fontSize = 13.sp, color = LiveRed.copy(alpha = 0.9f))
        }
    }
}
