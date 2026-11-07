package io.github.sim7b.tele.data.storage

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.sim7b.tele.MainActivity
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.search.AppIntents
import io.github.sim7b.tele.data.record.RecordingRules
import io.github.sim7b.tele.data.record.RecordingScheduler
import io.github.sim7b.tele.data.record.RecordingService
import io.github.sim7b.tele.data.record.RecordingStore
import java.text.DateFormat
import java.util.Date

/**
 * Reminders told by the system when the app is not on screen (another app, the TV home): an alarm at the
 * next reminder, then a notification that opens the channel. On screen, the app shows its own dialog.
 */
object ReminderAlarms {

    private const val TAG = "ReminderAlarms"
    private const val CHANNEL_ID = "reminders"
    private const val ALARM_REQUEST = 1

    /** Set by the activity: on screen, the reminders are shown by the app itself. */
    @Volatile
    var appVisible = false

    /** The alarm of the next reminder to come (none left: no alarm). */
    fun schedule(context: Context, reminders: List<Reminder>, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = Reminders.nextAlarmAt(reminders, now)
        if (at == null) {
            alarms.cancel(intent)
            return
        }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                // Without the exact alarms permission (never asked for): the system may shift it a little
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Reminder alarm refused: ${e.javaClass.simpleName}")
        }
    }

    /** Notifications of the reminders due at [now], then the alarm of the next ones. */
    fun fire(context: Context, now: Long = System.currentTimeMillis()) {
        val reminders = GuideStore(context).reminders.value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Not the ones of the previous alarm again, nor those older than their grace time on the first one
        val lastFiredAt = prefs.getLong(KEY_LAST_FIRED, now - Reminders.GRACE_MS)
        if (!appVisible) Reminders.dueAll(reminders, lastFiredAt, now).forEach { notify(context, it) }
        prefs.edit().putLong(KEY_LAST_FIRED, now).apply()
        schedule(context, reminders, now)
    }

    private const val PREFS_NAME = "reminder_alarms"
    private const val KEY_LAST_FIRED = "last_fired"

    /** The notification of [reminder] taken away (the app showed it itself). */
    fun cancel(context: Context, reminder: Reminder) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(reminder))
    }

    private fun notificationId(reminder: Reminder) = (reminder.channelId + ":" + reminder.startMillis).hashCode()

    private fun notify(context: Context, reminder: Reminder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context,
            notificationId(reminder),
            Intent(Intent.ACTION_VIEW, AppIntents.channelLink(reminder.channelId).toUri(), context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reminder.startMillis))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText(context.getString(R.string.reminder_notification_text, reminder.channelName, time))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(reminder.startMillis + Reminders.GRACE_MS - System.currentTimeMillis())
            .build()
        try {
            manager.notify(notificationId(reminder), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Reminder notification refused: ${e.javaClass.simpleName}")
        }
    }
}

/** Alarm of a reminder (only sent by the app's own alarm). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReminderAlarms.fire(context)
}

/** The alarms are lost when the TV restarts: set again from the reminders and the recordings kept. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReminderAlarms.schedule(context, GuideStore(context).reminders.value)
        val recordings = RecordingStore.get(context).recordings.value
        RecordingScheduler.schedule(context, recordings)
        // One on air when the TV came back: started now (allowed right after the start of the TV)
        if (RecordingRules.due(recordings, System.currentTimeMillis()).isNotEmpty()) RecordingService.start(context)
    }
}
