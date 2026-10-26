package io.github.sim7b.tele.data.storage

import java.io.File
import java.io.IOException

/**
 * Tone of the channel logos already analyzed (true = light logo), by logo URL. Bounded to the
 * [maxEntries] most recently used logos and saved to its own file as a whole, so that browsing
 * thousands of channels neither grows it without limit nor rewrites it on every logo.
 * [load] and [save] do file I/O: to call off the main thread.
 */
class LogoToneStore(private val file: File, private val maxEntries: Int = MAX_ENTRIES) {

    private val tones = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > maxEntries
    }
    private var dirty = false

    @Synchronized
    fun get(url: String): Boolean? = tones[url]

    @Synchronized
    fun put(url: String, light: Boolean) {
        if (url.contains('\n') || tones[url] == light) return
        tones[url] = light
        dirty = true
    }

    /** Reads the saved tones; those analyzed in the meantime are kept. One "<0|1><url>" line per logo. */
    fun load() {
        if (!file.exists()) return
        val saved = LinkedHashMap<String, Boolean>()
        file.bufferedReader().useLines { lines ->
            lines.take(maxEntries).forEach { line ->
                val light = when (line.firstOrNull()) {
                    '1' -> true
                    '0' -> false
                    else -> return@forEach
                }
                val url = line.substring(1)
                if (url.isNotEmpty()) saved[url] = light
            }
        }
        synchronized(this) {
            saved.forEach { (url, light) -> tones.putIfAbsent(url, light) }
        }
    }

    /** Writes the tones when they changed since the last save (temporary file, then renamed). */
    fun save() {
        val snapshot = synchronized(this) {
            if (!dirty) return
            dirty = false
            tones.entries.map { it.key to it.value }
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.bufferedWriter().use { writer ->
                snapshot.forEach { (url, light) ->
                    writer.write(if (light) "1" else "0")
                    writer.write(url)
                    writer.newLine()
                }
            }
            if (!temp.renameTo(file)) throw IOException("Unable to replace the logo tones")
        } catch (e: IOException) {
            synchronized(this) { dirty = true }
            throw e
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val MAX_ENTRIES = 5_000
    }
}
