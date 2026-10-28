package io.github.sim7b.tele.data.storage

import android.util.Log
import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Where a movie or an episode was left: [key] is the title key, or the title key and the episode
 * ("s:tmdb:1399#S1E5"); [titleKey] gives the row of the library.
 */
data class VodProgress(
    val key: String,
    val titleKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

    companion object {
        const val FINISHED_FRACTION = 0.93
    }
}

/** A collection made by the user ("Family night"), shown as a row of the home: titles in the order added. */
data class VodCollection(val id: String, val name: String, val titleKeys: List<String>)

/**
 * Playback positions, "my list" and the collections of the movies and series module, kept in a small file of the device
 * (a few hundred titles at most: the oldest positions are dropped).
 */
class VodStore(private val file: File) {

    private val _progress = MutableStateFlow<Map<String, VodProgress>>(emptyMap())
    val progress: StateFlow<Map<String, VodProgress>> = _progress.asStateFlow()

    private val _myList = MutableStateFlow<List<String>>(emptyList())
    val myList: StateFlow<List<String>> = _myList.asStateFlow()

    private val _collections = MutableStateFlow<List<VodCollection>>(emptyList())
    val collections: StateFlow<List<VodCollection>> = _collections.asStateFlow()

    private val lock = Any()

    /** Reads the file (to call off the main thread, once). */
    fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val progress = HashMap<String, VodProgress>()
            root.optJSONArray("progress")?.let { array ->
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val key = obj.optStringOrNull("key") ?: continue
                    progress[key] = VodProgress(
                        key = key,
                        titleKey = obj.optStringOrNull("title") ?: key,
                        positionMs = obj.optLong("position"),
                        durationMs = obj.optLong("duration"),
                        updatedAt = obj.optLong("updated")
                    )
                }
            }
            val myList = root.optJSONArray("myList")?.let { array -> (0 until array.length()).mapNotNull { array.optStringOrNull(it) } }.orEmpty()
            val collections = root.optJSONArray("collections")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = obj.optStringOrNull("id") ?: return@mapNotNull null
                    val keys = obj.optJSONArray("titles")?.let { titles -> (0 until titles.length()).mapNotNull { titles.optStringOrNull(it) } }.orEmpty()
                    VodCollection(id, obj.optString("name"), keys)
                }
            }.orEmpty()
            synchronized(lock) {
                _progress.value = progress
                _myList.value = myList
                _collections.value = collections
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read the movies and series state: ${e.javaClass.simpleName}")
        }
    }

    fun saveProgress(key: String, titleKey: String, positionMs: Long, durationMs: Long, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val updated = _progress.value.toMutableMap()
            updated[key] = VodProgress(key, titleKey, positionMs, durationMs, now)
            if (updated.size > MAX_PROGRESS) {
                updated.values.sortedBy { it.updatedAt }.take(updated.size - MAX_PROGRESS).forEach { updated.remove(it.key) }
            }
            _progress.value = updated
            write()
        }
    }

    /** Marks a movie or an episode watched (it leaves "Continue watching"). */
    fun markWatched(key: String, titleKey: String) = saveProgress(key, titleKey, positionMs = WATCHED_MS, durationMs = WATCHED_MS)

    /** Forgets the position of a movie or an episode (unwatched). */
    fun clearProgress(key: String) {
        synchronized(lock) {
            if (key !in _progress.value) return
            _progress.value = _progress.value - key
            write()
        }
    }

    /** Removes a title from "Continue watching": the positions of the movie or of all its episodes are forgotten. */
    fun clearTitleProgress(titleKey: String) {
        synchronized(lock) {
            val kept = _progress.value.filterValues { it.titleKey != titleKey || it.finished }
            if (kept.size == _progress.value.size) return
            _progress.value = kept
            write()
        }
    }

    fun toggleMyList(titleKey: String) {
        synchronized(lock) {
            val list = _myList.value
            _myList.value = if (titleKey in list) list - titleKey else listOf(titleKey) + list
            write()
        }
    }

    /** New empty collection named [name]; returns its id. */
    fun createCollection(name: String): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            _collections.value = _collections.value + VodCollection(id, name.trim().take(MAX_NAME), emptyList())
            write()
        }
        return id
    }

    fun renameCollection(id: String, name: String) = updateCollections { list ->
        list.map { if (it.id == id) it.copy(name = name.trim().take(MAX_NAME)) else it }
    }

    fun deleteCollection(id: String) = updateCollections { list -> list.filterNot { it.id == id } }

    /** Adds [titleKey] to collection [id] (first), or removes it when it is already in. */
    fun toggleInCollection(id: String, titleKey: String) = updateCollections { list ->
        list.map { collection ->
            if (collection.id != id) collection
            else if (titleKey in collection.titleKeys) collection.copy(titleKeys = collection.titleKeys - titleKey)
            else collection.copy(titleKeys = (listOf(titleKey) + collection.titleKeys).take(MAX_COLLECTION_SIZE))
        }
    }

    private fun updateCollections(change: (List<VodCollection>) -> List<VodCollection>) {
        synchronized(lock) {
            _collections.value = change(_collections.value)
            write()
        }
    }

    private fun write() {
        val root = JSONObject()
            .put("progress", JSONArray().apply {
                _progress.value.values.forEach { progress ->
                    put(
                        JSONObject()
                            .put("key", progress.key)
                            .put("title", progress.titleKey)
                            .put("position", progress.positionMs)
                            .put("duration", progress.durationMs)
                            .put("updated", progress.updatedAt)
                    )
                }
            })
            .put("myList", JSONArray(_myList.value))
            .put("collections", JSONArray().apply {
                _collections.value.forEach { collection ->
                    put(JSONObject().put("id", collection.id).put("name", collection.name).put("titles", JSONArray(collection.titleKeys)))
                }
            })
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) throw IOException("Unable to replace the movies and series state")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to save the movies and series state: ${e.javaClass.simpleName}")
        } finally {
            temp.delete()
        }
    }

    private fun JSONArray.optStringOrNull(index: Int): String? = optString(index).takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "VodStore"
        const val MAX_PROGRESS = 500
        const val MAX_NAME = 60
        const val MAX_COLLECTION_SIZE = 2_000
        // Position and duration of a title marked watched by hand
        const val WATCHED_MS = 1L
    }
}
