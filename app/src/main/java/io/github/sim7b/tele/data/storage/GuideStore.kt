package io.github.sim7b.tele.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Program the user wants to be told about when it starts. */
data class Reminder(
    val channelId: String,
    val channelName: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long
)

/** Rules of the reminders and of the recent channels, kept apart from the storage to be tested. */
object Reminders {

    const val MAX_REMINDERS = 50
    const val MAX_RECENT_CHANNELS = 20
    // Shown a minute before the program starts
    const val LEAD_MS = 60_000L
    // A reminder missed (app closed) is still shown during the first minutes of the program, then dropped
    const val GRACE_MS = 10 * 60_000L

    fun matches(reminder: Reminder, channelId: String, startMillis: Long) =
        reminder.channelId == channelId && reminder.startMillis == startMillis

    /** Adds the reminder, or removes it when the program already had one; sorted by start. */
    fun toggle(reminders: List<Reminder>, reminder: Reminder): List<Reminder> =
        if (reminders.any { matches(it, reminder.channelId, reminder.startMillis) }) {
            reminders.filterNot { matches(it, reminder.channelId, reminder.startMillis) }
        } else {
            (reminders + reminder).sortedBy { it.startMillis }.take(MAX_REMINDERS)
        }

    /** The reminders still to come (or just started). */
    fun pending(reminders: List<Reminder>, now: Long): List<Reminder> =
        reminders.filter { it.startMillis + GRACE_MS > now }

    /** The first reminder to show now, or null. */
    fun due(reminders: List<Reminder>, now: Long): Reminder? =
        reminders.firstOrNull { it.startMillis - LEAD_MS <= now && it.startMillis + GRACE_MS > now }

    /** Time of the next reminder alarm after [now] ([LEAD_MS] before its program), null when none is left. */
    fun nextAlarmAt(reminders: List<Reminder>, now: Long): Long? =
        reminders.map { it.startMillis - LEAD_MS }.filter { it > now }.minOrNull()

    /**
     * The reminders to notify at [now], when the previous alarm went at [lastFiredAt]: their time came since,
     * and their program has not been on for long. Each one is notified once.
     */
    fun dueAll(reminders: List<Reminder>, lastFiredAt: Long, now: Long): List<Reminder> =
        reminders.filter { it.startMillis - LEAD_MS in (lastFiredAt + 1)..now && it.startMillis + GRACE_MS > now }

    /** [channelId] first, then the other recent channels, without duplicates. */
    fun recent(channels: List<String>, channelId: String): List<String> =
        (listOf(channelId) + channels.filter { it != channelId }).take(MAX_RECENT_CHANNELS)

    fun toJson(reminders: List<Reminder>): String = JSONArray().apply {
        reminders.forEach { reminder ->
            put(JSONObject().apply {
                put("channelId", reminder.channelId)
                put("channelName", reminder.channelName)
                put("title", reminder.title)
                put("start", reminder.startMillis)
                put("end", reminder.endMillis)
            })
        }
    }.toString()

    /** Reminders saved by [toJson]; the broken entries are skipped. */
    fun fromJson(json: String?): List<Reminder> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Reminder(
                    channelId = item.optStringOrNull("channelId") ?: return@mapNotNull null,
                    channelName = item.optStringOrNull("channelName").orEmpty(),
                    title = item.optStringOrNull("title").orEmpty(),
                    startMillis = item.optLong("start", 0L).takeIf { it > 0 } ?: return@mapNotNull null,
                    endMillis = item.optLong("end", 0L)
                )
            }.take(MAX_REMINDERS)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Reminders of programs and recently watched channels (first category of the channel panel and of
 * the guide grid), kept on the device.
 */
class GuideStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _reminders = MutableStateFlow(Reminders.pending(Reminders.fromJson(prefs.getString(KEY_REMINDERS, null)), System.currentTimeMillis()))
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    private val _recentChannels = MutableStateFlow(readRecent())
    val recentChannels: StateFlow<List<String>> = _recentChannels.asStateFlow()

    fun toggleReminder(reminder: Reminder) = saveReminders(Reminders.toggle(_reminders.value, reminder))

    /** Reminders as synced from the other TVs of the user (Télé API plugin). */
    fun replaceReminders(reminders: List<Reminder>) {
        val kept = reminders.take(Reminders.MAX_REMINDERS)
        if (kept != _reminders.value) saveReminders(kept)
    }

    /** Drops [reminder] once shown, with the reminders already past. */
    fun dismiss(reminder: Reminder) =
        saveReminders(Reminders.pending(_reminders.value, System.currentTimeMillis()).filterNot { it == reminder })

    private val _sportAlerted = MutableStateFlow(readAlerted())
    /** The matches already given a reminder by the sport alerts (see SportEvents.alertKey). */
    val sportAlerted: StateFlow<Set<String>> = _sportAlerted.asStateFlow()

    /** Adds the reminders of the sport alerts, each one once. */
    fun addSportAlerts(alerts: List<Reminder>) {
        if (alerts.isEmpty()) return
        var reminders = _reminders.value
        alerts.forEach { alert ->
            if (reminders.none { Reminders.matches(it, alert.channelId, alert.startMillis) }) reminders = Reminders.toggle(reminders, alert)
        }
        saveReminders(reminders)
        val now = System.currentTimeMillis()
        // Kept until a day after their match, at most a few hundred
        val alerted = (_sportAlerted.value + alerts.map { "${it.channelId}|${it.startMillis}" })
            .filter { key -> (key.substringAfterLast('|').toLongOrNull() ?: 0L) > now - ALERTED_KEPT_MS }
            .takeLast(MAX_ALERTED)
            .toSet()
        _sportAlerted.value = alerted
        prefs.edit { putString(KEY_SPORT_ALERTED, JSONArray(alerted.toList()).toString()) }
    }

    fun addRecent(channelId: String) {
        val updated = Reminders.recent(_recentChannels.value, channelId)
        if (updated == _recentChannels.value) return
        _recentChannels.value = updated
        prefs.edit { putString(KEY_RECENT, JSONArray(updated).toString()) }
    }

    private fun saveReminders(reminders: List<Reminder>) {
        _reminders.value = reminders
        prefs.edit { putString(KEY_REMINDERS, Reminders.toJson(reminders)) }
    }

    private fun readAlerted(): Set<String> = try {
        val array = JSONArray(prefs.getString(KEY_SPORT_ALERTED, null) ?: "[]")
        (0 until minOf(array.length(), MAX_ALERTED)).mapNotNull { array.optString(it).takeIf { key -> key.isNotEmpty() } }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun readRecent(): List<String> = try {
        val array = JSONArray(prefs.getString(KEY_RECENT, null) ?: "[]")
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotEmpty() } }.take(Reminders.MAX_RECENT_CHANNELS)
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        const val PREFS_NAME = "guide"
        const val KEY_REMINDERS = "reminders"
        const val KEY_RECENT = "recent_channels"
        const val KEY_SPORT_ALERTED = "sport_alerted"
        const val MAX_ALERTED = 300
        const val ALERTED_KEPT_MS = 24 * 60 * 60_000L
    }
}
