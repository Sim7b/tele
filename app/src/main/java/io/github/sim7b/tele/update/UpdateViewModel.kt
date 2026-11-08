package io.github.sim7b.tele.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.toUiText
import io.github.sim7b.tele.data.update.AppRelease
import io.github.sim7b.tele.data.update.AppVersion
import io.github.sim7b.tele.data.update.UpdateClient
import io.github.sim7b.tele.data.update.UpdateFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Update state shown in the settings (and as a dot on the home screen when one is available). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    /** Downloaded and checked: installed at once when asked. */
    data class Ready(val release: AppRelease) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: UiText, val release: AppRelease? = null) : UpdateState
}

/** The newer version this state is about (to offer, downloading, ready, failed), null when there is none. */
val UpdateState.release: AppRelease?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Ready -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
        else -> null
    }

/**
 * In-app updates from the latest GitHub release: checked once at launch, then on demand. A newer
 * version is only offered (see [ignore]): installing downloads it to the cache, checks it and hands it to
 * the system installer, which always asks the user to confirm.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val client = UpdateClient()
    private val updatesDir = File(application.cacheDir, "updates")
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isSupported = UpdateInstaller.isSupported(application)
    val installedVersion = UpdateInstaller.installedVersion(application)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Version whose offer the user turned down: still installable from the settings. */
    private val _ignoredVersion = MutableStateFlow(prefs.getString(KEY_IGNORED, null))
    val ignoredVersion: StateFlow<String?> = _ignoredVersion.asStateFlow()

    private var job: Job? = null

    init {
        if (isSupported) check()
        // Failed or cancelled installation: back to the offer, with the reason
        viewModelScope.launch {
            UpdateInstaller.results.collect { result ->
                if (result == null) return@collect
                val release = (_state.value as? UpdateState.Installing)?.release ?: return@collect
                UpdateInstaller.consumeResult()
                _state.value = when (result) {
                    InstallResult.CANCELLED -> UpdateState.Ready(release)
                    InstallResult.SIGNATURE_MISMATCH -> UpdateState.Failed(UiText.Res(R.string.update_error_signature), release)
                    InstallResult.FAILED -> UpdateState.Failed(UiText.Res(R.string.update_error_install), release)
                }
            }
        }
    }

    fun check() {
        if (!isSupported || job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = UpdateState.Checking
            val release = try {
                client.latestRelease()?.takeIf { AppVersion.isNewer(it.version, installedVersion) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.toUiText())
                return@launch
            }
            clean(keep = release?.version)
            // Only offered: the APK is downloaded once the user asks to install it, never in the background
            _state.value = if (release == null) UpdateState.UpToDate else UpdateState.Available(release)
        }
    }

    /** Turns down the offer of [release]: not shown at startup any more (a newer version will be). */
    fun ignore(release: AppRelease) {
        prefs.edit { putString(KEY_IGNORED, release.version) }
        _ignoredVersion.value = release.version
    }

    fun install(release: AppRelease) {
        if (!isSupported || job?.isActive == true) return
        val app = getApplication<Application>()
        if (!UpdateInstaller.canInstall(app) && openInstallPermissionSettings()) {
            _state.value = UpdateState.Failed(UiText.Res(R.string.update_error_permission), release)
            return
        }
        job = viewModelScope.launch {
            try {
                val apk = prepare(release)
                _state.value = UpdateState.Installing(release)
                withContext(Dispatchers.IO) { UpdateInstaller.install(app, apk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                _state.value = UpdateState.Failed(UiText.Res(R.string.update_error_package), release)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.toUiText(), release)
            }
        }
    }

    /**
     * The APK of [release], downloaded unless it already is, and checked: this app, a newer build
     * (the system then checks its signature). Kept in the cache until a later start finds it installed.
     */
    private suspend fun prepare(release: AppRelease): File {
        val name = UpdateFiles.fileName(release.version) ?: throw IllegalStateException("Unexpected version name")
        val apk = File(updatesDir, name)
        val app = getApplication<Application>()
        if (withContext(Dispatchers.IO) { apk.isFile && UpdateInstaller.isNewerBuildOfThisApp(app, apk) }) return apk
        val partial = File(updatesDir, "$name.part")
        try {
            withContext(Dispatchers.IO) {
                updatesDir.mkdirs()
                apk.delete()
                partial.delete()
            }
            _state.value = UpdateState.Downloading(release, 0f)
            client.download(release, partial) { progress -> _state.value = UpdateState.Downloading(release, progress) }
            withContext(Dispatchers.IO) {
                // Checked under its final name: the system parses an archive named .apk
                if (!partial.renameTo(apk) || !UpdateInstaller.isNewerBuildOfThisApp(app, apk)) {
                    apk.delete()
                    throw IllegalStateException("Downloaded APK is not a newer build of this app")
                }
            }
            return apk
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { partial.delete() }
        }
    }

    /** Deletes the APKs already installed (or replaced by [keep]), and the leftovers of interrupted downloads. */
    private suspend fun clean(keep: String?) = withContext(Dispatchers.IO) {
        val names = updatesDir.list()?.toList().orEmpty()
        UpdateFiles.stale(names, installedVersion, keep).forEach { File(updatesDir, it).delete() }
    }

    /** "Install unknown apps" screen of this app; missing on some TV builds, where the installer asks itself. */
    private fun openInstallPermissionSettings(): Boolean {
        val app = getApplication<Application>()
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private companion object {
        const val PREFS_NAME = "updates"
        const val KEY_IGNORED = "ignored_version"
    }
}
