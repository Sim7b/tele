package io.github.sim7b.tele.data.record

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a recording is: waiting for its program, being written, finished, or failed. */
enum class RecordingState { SCHEDULED, RECORDING, DONE, FAILED }

/**
 * A program recorded on the device, from [startMillis] to [endMillis] (plus the margins of [RecordingRules]):
 * the stream [url] of [channelName], written to [fileName] in the recordings folder.
 */
data class Recording(
    val id: String,
    val channelId: String,
    val channelName: String,
    val sourceId: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val url: String,
    val imageUrl: String? = null,
    val description: String? = null,
    val state: RecordingState = RecordingState.SCHEDULED,
    val bytes: Long = 0L
) {
    val fileName: String get() = "$id.ts"
}

/** Rules of the recordings, apart from the storage and the service to be tested. */
object RecordingRules {

    const val MAX_RECORDINGS = 100
    // Started a little before the program and stopped a little after: guides are seldom on time
    const val MARGIN_BEFORE_MS = 60_000L
    const val MARGIN_AFTER_MS = 3 * 60_000L
    // Longest program recorded (a broken guide entry must not fill the storage)
    const val MAX_DURATION_MS = 6 * 60 * 60_000L
    // Left free on the device for the system and the other apps
    const val RESERVE_BYTES = 2L * 1024 * 1024 * 1024

    /** Id of the recording of a program: its channel and start, the same each time it is asked for. */
    fun idOf(channelId: String, startMillis: Long): String =
        Integer.toHexString(channelId.hashCode()) + "-" + startMillis

    /**
     * The stream can be written as it comes: MPEG-TS or another progressive stream. An HLS playlist would
     * need its segments fetched one by one (not done), a replay is already kept by the provider.
     */
    fun canRecord(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return (url.startsWith("http://") || url.startsWith("https://")) && !path.endsWith(".m3u8") && !path.endsWith(".m3u")
    }

    /** Adds [recording], or takes it away when its program was already asked for; sorted by start. */
    fun toggle(recordings: List<Recording>, recording: Recording): List<Recording> =
        if (recordings.any { it.id == recording.id }) {
            recordings.filterNot { it.id == recording.id }
        } else {
            (recordings + recording).sortedBy { it.startMillis }.takeLast(MAX_RECORDINGS)
        }

    /** When the recording starts writing. */
    fun beginAt(recording: Recording): Long = recording.startMillis - MARGIN_BEFORE_MS

    /** When it stops: after its program, never past [MAX_DURATION_MS]. */
    fun stopAt(recording: Recording): Long =
        minOf(recording.endMillis + MARGIN_AFTER_MS, recording.startMillis + MAX_DURATION_MS)

    /** Recordings to write at [now]: waiting (or cut off by a restart of the app) and on air. */
    fun due(recordings: List<Recording>, now: Long): List<Recording> =
        recordings.filter {
            (it.state == RecordingState.SCHEDULED || it.state == RecordingState.RECORDING) && now >= beginAt(it) && now < stopAt(it)
        }

    /** Waiting ones whose program is over without them (TV off, refused): failed. */
    fun missed(recordings: List<Recording>, now: Long): List<Recording> =
        recordings.filter { it.state == RecordingState.SCHEDULED && now >= stopAt(it) }

    /** Start of the next recording waiting after [now], null when none is left. */
    fun nextBeginAt(recordings: List<Recording>, now: Long): Long? =
        recordings.filter { it.state == RecordingState.SCHEDULED }.map { beginAt(it) }.filter { it > now }.minOrNull()

    fun toJson(recordings: List<Recording>): String = JSONArray().apply {
        recordings.forEach { recording ->
            put(
                JSONObject()
                    .put("id", recording.id)
                    .put("channelId", recording.channelId)
                    .put("channelName", recording.channelName)
                    .put("sourceId", recording.sourceId)
                    .put("title", recording.title)
                    .put("start", recording.startMillis)
                    .put("end", recording.endMillis)
                    .put("url", recording.url)
                    .putOpt("image", recording.imageUrl)
                    .putOpt("description", recording.description)
                    .put("state", recording.state.name)
                    .put("bytes", recording.bytes)
            )
        }
    }.toString()

    /** Recordings saved by [toJson]; the broken entries are skipped. */
    fun fromJson(json: String?): List<Recording> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.matches(ID_FORMAT) } ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { canRecord(it) } ?: return@mapNotNull null
                val start = obj.optLong("start")
                val end = obj.optLong("end")
                if (start <= 0 || end <= start) return@mapNotNull null
                Recording(
                    id = id,
                    channelId = obj.optString("channelId"),
                    channelName = obj.optString("channelName"),
                    sourceId = obj.optString("sourceId"),
                    title = obj.optString("title"),
                    startMillis = start,
                    endMillis = end,
                    url = url,
                    imageUrl = obj.optString("image").takeIf { it.isNotBlank() },
                    description = obj.optString("description").takeIf { it.isNotBlank() },
                    state = RecordingState.entries.firstOrNull { it.name == obj.optString("state") } ?: RecordingState.FAILED,
                    bytes = obj.optLong("bytes").coerceAtLeast(0)
                )
            }.take(MAX_RECORDINGS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Only these characters in a file name taken from the storage
    private val ID_FORMAT = Regex("[0-9a-f-]{1,40}")
}

/**
 * The recordings of the device: the list kept in the preferences, the files in the app's own folder
 * (removed with the app). One instance for the app and its recording service.
 */
class RecordingStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    /** Folder of the recorded files: the app's own one on the shared storage when there is one (more room). */
    val dir: File = (context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME)).also { it.mkdirs() }

    private val _recordings = MutableStateFlow(RecordingRules.fromJson(prefs.getString(KEY_RECORDINGS, null)))
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    fun file(recording: Recording) = File(dir, recording.fileName)

    /** Asks for the recording of a program, or cancels it (its file deleted). */
    fun toggle(recording: Recording) = change { list ->
        list.firstOrNull { it.id == recording.id }?.let { file(it).delete() }
        RecordingRules.toggle(list, recording)
    }

    /** Takes away a recording and its file. */
    fun delete(id: String) = change { list ->
        list.firstOrNull { it.id == id }?.let { file(it).delete() }
        list.filterNot { it.id == id }
    }

    /** Changes [id] (state, size) when it is still in the list. */
    fun update(id: String, transform: (Recording) -> Recording) = change { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    private fun change(transform: (List<Recording>) -> List<Recording>) = synchronized(lock) {
        val updated = transform(_recordings.value)
        _recordings.value = updated
        prefs.edit { putString(KEY_RECORDINGS, RecordingRules.toJson(updated)) }
    }

    companion object {
        private const val PREFS_NAME = "recordings"
        private const val KEY_RECORDINGS = "list"
        private const val DIR_NAME = "recordings"

        @Volatile
        private var instance: RecordingStore? = null

        fun get(context: Context): RecordingStore =
            instance ?: synchronized(this) { instance ?: RecordingStore(context.applicationContext).also { instance = it } }
    }
}
