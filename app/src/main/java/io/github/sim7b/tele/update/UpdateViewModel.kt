package io.github.sim7b.tele.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: UiText, val release: AppRelease? = null) : UpdateState
}

/**
 * In-app updates from the latest GitHub release: checked once at launch, then on demand;
 * the APK is downloaded to the cache and handed to the system installer.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val client = UpdateClient()
    private val apkFile = File(application.cacheDir, "updates/update.apk")

    val isSupported = UpdateInstaller.isSupported(application)
    val installedVersion = UpdateInstaller.installedVersion(application)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

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
                    InstallResult.CANCELLED -> UpdateState.Available(release)
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
            _state.value = try {
                val release = client.latestRelease()
                if (release != null && AppVersion.isNewer(release.version, installedVersion)) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.toUiText())
            }
        }
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
                _state.value = UpdateState.Downloading(release, 0f)
                apkFile.delete()
                client.download(release, apkFile) { progress ->
                    _state.value = UpdateState.Downloading(release, progress)
                }
                _state.value = UpdateState.Installing(release)
                withContext(Dispatchers.IO) {
                    if (!UpdateInstaller.isNewerBuildOfThisApp(app, apkFile)) {
                        throw IllegalStateException("Downloaded APK is not a newer build of this app")
                    }
                    UpdateInstaller.install(app, apkFile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                _state.value = UpdateState.Failed(UiText.Res(R.string.update_error_package), release)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.toUiText(), release)
            } finally {
                // Copied into the installer session (or useless after a failure)
                withContext(Dispatchers.IO + NonCancellable) { apkFile.delete() }
            }
        }
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
}
