package io.github.sim7b.tele.data.record

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.sim7b.tele.MainActivity
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.data.remote.ApiKeyInterceptor
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Writes the recordings on air to the device, in the foreground (a notification says so) so that the system
 * lets it run while another app is used or the TV shows its home. Each recording reads its stream from its
 * start to its end, again after a drop (appended to its file), and stops when the storage gets low. The
 * service ends with the last recording.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private lateinit var store: RecordingStore
    private lateinit var client: OkHttpClient
    private var userAgents: Map<String, String> = emptyMap()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore.get(this)
        // Dispatcharr keys of the enabled servers, each one only sent to its own server (see ApiKeyAuth)
        val configs = SettingsRepository(applicationContext).enabledConfigs.value
        val auths = configs.filter { it.type == SourceType.DISPATCHARR }.mapNotNull { ApiKeyAuth.of(it) }
        userAgents = configs.mapNotNull { config -> config.customUserAgent?.takeIf { it.isNotBlank() }?.let { config.id to it } }.toMap()
        client = HttpClients.base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor(ApiKeyInterceptor { request -> auths.firstOrNull { it.appliesTo(request.url) } })
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: RuntimeException) {
            // Not allowed from the background (Android 12+ without an exact alarm): retried when the app opens
            Log.w(TAG, "Recording service refused: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        startDue()
        return START_STICKY
    }

    /** A job for each recording on air not written yet; the service stops when none is left. */
    private fun startDue() {
        val now = System.currentTimeMillis()
        RecordingRules.missed(store.recordings.value, now).forEach { missed ->
            store.update(missed.id) { it.copy(state = RecordingState.FAILED) }
        }
        RecordingRules.due(store.recordings.value, now).filter { jobs[it.id]?.isActive != true }.forEach { recording ->
            jobs[recording.id] = scope.launch { record(recording) }
        }
        RecordingScheduler.schedule(this, store.recordings.value)
        if (jobs.values.none { it.isActive }) stopSelf()
    }

    private suspend fun record(recording: Recording) {
        val file = store.file(recording)
        val stopAt = RecordingRules.stopAt(recording)
        var bytes = if (file.exists()) file.length() else 0L
        store.update(recording.id) { it.copy(state = RecordingState.RECORDING, bytes = bytes) }
        refreshNotification()
        val buffer = ByteArray(READ_BYTES)
        var lowStorage = false
        while (scope.isActive && System.currentTimeMillis() < stopAt && !lowStorage) {
            // Cancelled from the app: its file is already deleted
            if (store.recordings.value.none { it.id == recording.id }) return finish()
            val request = Request.Builder()
                .url(recording.url)
                .header("User-Agent", userAgents[recording.sourceId] ?: HttpClients.DEFAULT_USER_AGENT)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    FileOutputStream(file, true).use { output ->
                        val input = response.body.byteStream()
                        var lastSaved = bytes
                        while (System.currentTimeMillis() < stopAt) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytes += read
                            if (bytes - lastSaved >= SAVE_EVERY_BYTES) {
                                lastSaved = bytes
                                if (store.recordings.value.none { it.id == recording.id }) return finish()
                                store.update(recording.id) { it.copy(bytes = bytes) }
                                if (store.dir.usableSpace < RecordingRules.RESERVE_BYTES) {
                                    lowStorage = true
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Recording interrupted: ${e.javaClass.simpleName}")
            }
            // Dropped or ended by the server before the end: again after a pause
            if (System.currentTimeMillis() < stopAt && !lowStorage) delay(RETRY_DELAY_MS)
        }
        store.update(recording.id) { it.copy(state = if (bytes > 0) RecordingState.DONE else RecordingState.FAILED, bytes = bytes) }
        finish()
    }

    private fun finish() {
        refreshNotification()
        // The others may still be going
        scope.launch {
            delay(1_000)
            if (jobs.values.none { it.isActive }) stopSelf()
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val current = store.recordings.value.filter { it.state == RecordingState.RECORDING }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(current.joinToString { "${it.title} (${it.channelName})" })
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        // Stopped by the system in the middle: what was written is kept
        store.recordings.value.filter { it.state == RecordingState.RECORDING && jobs.containsKey(it.id) }.forEach { recording ->
            store.update(recording.id) { it.copy(state = if (it.bytes > 0) RecordingState.DONE else RecordingState.FAILED) }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recordings"
        private const val NOTIFICATION_ID = 7301
        private const val READ_BYTES = 64 * 1024
        private const val SAVE_EVERY_BYTES = 8L * 1024 * 1024
        private const val RETRY_DELAY_MS = 3_000L

        /** Starts the recordings on air (from the app on screen, or from the alarm of one). */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RecordingService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Recording service not started: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** Alarm at the start of the next recording. */
object RecordingScheduler {

    private const val ALARM_REQUEST = 2

    /** The exact alarms are allowed (else, on Android 12+, a recording may not start while the app is closed). */
    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun schedule(context: Context, recordings: List<Recording>, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, RecordingAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = RecordingRules.nextBeginAt(recordings, now)
        if (at == null) {
            alarms.cancel(intent)
            return
        }
        try {
            if (exactAllowed(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (e: SecurityException) {
            Log.w("RecordingScheduler", "Recording alarm refused: ${e.javaClass.simpleName}")
        }
    }
}

/** Alarm of a recording (only sent by the app's own alarm). */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RecordingService.start(context)
}
