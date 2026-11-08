package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * TMDB lists kept on the device between two starts (the rankings of the home, the catalogs of the streaming
 * services and of the genres opened): one file per list, with the time it was fetched. Public data only
 * (no key, no address); a file unreadable or written by another version is ignored.
 */
class TmdbListsCache(private val dir: File) {

    /** A list kept: its entries and when they were fetched. */
    data class Entry(val fetchedAt: Long, val lists: Map<String, List<RankedItem>>)

    /** The list kept under [name], null when there is none or it is unreadable. */
    fun read(name: String): Entry? {
        val file = fileOf(name) ?: return null
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        return try {
            decode(file.readText())
        } catch (_: IOException) {
            null
        }
    }

    /** Keeps [lists] under [name]; written beside, then renamed: never half a file. */
    fun write(name: String, lists: Map<String, List<RankedItem>>, fetchedAt: Long = System.currentTimeMillis()) {
        val file = fileOf(name) ?: return
        try {
            dir.mkdirs()
            val partial = File(dir, "${file.name}.part")
            partial.writeText(encode(Entry(fetchedAt, lists)))
            if (!partial.renameTo(file)) {
                file.delete()
                partial.renameTo(file)
            }
        } catch (_: IOException) {
            // Kept in memory only this time
        }
    }

    private fun fileOf(name: String): File? = name.takeIf { SAFE_NAME.matches(it) }?.let { File(dir, "$it.json") }

    companion object {
        private val SAFE_NAME = Regex("[a-z0-9_]{1,64}")
        private const val VERSION = 1
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024

        internal fun encode(entry: Entry): String = JSONObject()
            .put("version", VERSION)
            .put("fetchedAt", entry.fetchedAt)
            .put("lists", JSONObject().apply {
                entry.lists.forEach { (name, items) ->
                    put(name, JSONArray().apply {
                        items.forEach { item ->
                            put(
                                JSONObject()
                                    .put("kind", item.kind.name)
                                    .put("id", item.tmdbId)
                                    .put("title", item.title)
                                    .putOpt("original", item.originalTitle)
                                    .putOpt("year", item.year)
                            )
                        }
                    })
                }
            })
            .toString()

        /** The entry of [text]; null when it is not one (broken, another version). */
        internal fun decode(text: String): Entry? {
            val json = try {
                JSONObject(text)
            } catch (_: org.json.JSONException) {
                return null
            }
            if (json.optInt("version") != VERSION) return null
            val fetchedAt = json.optLong("fetchedAt", 0).takeIf { it > 0 } ?: return null
            val lists = json.optJSONObject("lists") ?: return null
            val decoded = lists.keys().asSequence().associateWith { name ->
                VodMetadata.objects(lists.optJSONArray(name)).mapNotNull { item ->
                    RankedItem(
                        kind = item.optStringOrNull("kind")?.let { kind -> VodKind.entries.firstOrNull { it.name == kind } } ?: return@mapNotNull null,
                        tmdbId = item.optStringOrNull("id") ?: return@mapNotNull null,
                        title = item.optStringOrNull("title") ?: return@mapNotNull null,
                        originalTitle = item.optStringOrNull("original"),
                        year = item.optInt("year", 0).takeIf { it > 0 }
                    )
                }
            }
            return Entry(fetchedAt, decoded)
        }
    }
}
