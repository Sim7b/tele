package io.github.sim7b.tele.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Outcome of an installation reported by the system. */
enum class InstallResult { CANCELLED, SIGNATURE_MISMATCH, FAILED }

/**
 * Installs a downloaded update with the system [PackageInstaller]: Android asks the user to confirm,
 * and refuses an APK signed with another key than the installed app.
 */
object UpdateInstaller {

    private val _results = MutableStateFlow<InstallResult?>(null)

    /** Last failed installation, until [consumeResult]; a successful update restarts the app. */
    val results: StateFlow<InstallResult?> = _results.asStateFlow()

    fun consumeResult() {
        _results.value = null
    }

    internal fun report(result: InstallResult) {
        _results.value = result
    }

    /** Development builds have another package name and signing key: they cannot install releases. */
    fun isSupported(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    /** Installed version name, e.g. "0.2.1". */
    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** True if [apk] is this app, with a higher version code than the installed one. */
    fun isNewerBuildOfThisApp(context: Context, apk: File): Boolean {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val archive = packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        val installed = packageManager.getPackageInfo(context.packageName, 0)
        return archive.packageName == context.packageName &&
            PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)
    }

    /** False when the user must first allow the app to install apps ("unknown sources"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Starts the installation; the result reaches [InstallResultReceiver]. To be called off the main thread. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Mutable: the system adds the status and, if needed, the confirmation intent
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                flags
            )
            session.commit(callback.intentSender)
        }
    }
}

/** Receives the installation status: shows the system confirmation, or reports a failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return UpdateInstaller.report(InstallResult.FAILED)
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstaller.report(InstallResult.CANCELLED)
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateInstaller.report(InstallResult.SIGNATURE_MISMATCH)
            else -> UpdateInstaller.report(InstallResult.FAILED)
        }
    }
}
