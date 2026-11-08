package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.vod.VodDetails
import io.github.sim7b.tele.data.vod.VodRow
import io.github.sim7b.tele.data.vod.VodTitle
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * What the movies and series screens built, kept on the device to show it at once on the next opening: the
 * library grouped from the catalogs, the rows of the Movies and Series tabs, the sheets opened, the posters
 * found. Encrypted (see [VodBinary]); a file unreadable or of another format is ignored. Off the main thread.
 */
class VodSnapshots(private val dir: File, private val secrets: SecretBox) {

    /** A sheet kept, with when it was built. */
    data class KeptDetails(val savedAt: Long, val details: VodDetails)

    // ---------------------------------------------------------------- Library

    /** Keeps [titles], grouped from catalogs described by [signature] (sources, times loaded, display). */
    fun saveLibrary(signature: String, titles: List<VodTitle>) = save(File(dir, LIBRARY_FILE)) { out ->
        out.writeUTF(signature)
        VodBinary.list(out, titles, VodBinary::writeTitle)
    }

    /** The library kept for [signature]; null when the catalogs changed since. */
    fun library(signature: String): List<VodTitle>? = load(File(dir, LIBRARY_FILE)) { data ->
        if (data.readUTF() != signature) null else VodBinary.list(data, VodBinary::readTitle)
    }

    // ---------------------------------------------------------------- Rows of the Movies and Series tabs

    fun saveRows(rows: List<VodRow>) = save(File(dir, ROWS_FILE)) { out -> VodBinary.list(out, rows, VodBinary::writeRow) }

    fun rows(): List<VodRow> = load(File(dir, ROWS_FILE)) { data -> VodBinary.list(data, VodBinary::readRow) }.orEmpty()

    // ---------------------------------------------------------------- Sheets

    /** Keeps the sheet of [key]; past [MAX_SHEETS], the oldest ones go. */
    fun saveDetails(key: String, details: VodDetails, savedAt: Long = System.currentTimeMillis()) {
        save(sheetFile(key)) { out ->
            out.writeUTF(key)
            out.writeLong(savedAt)
            VodBinary.writeDetails(out, details)
        }
        val sheets = sheetsDir().listFiles { file -> file.name.endsWith(SHEET_SUFFIX) } ?: return
        if (sheets.size > MAX_SHEETS) {
            sheets.sortedBy { it.lastModified() }.take(sheets.size - MAX_SHEETS).forEach { it.delete() }
        }
    }

    fun details(key: String): KeptDetails? = load(sheetFile(key)) { data ->
        // Another title with the same file name (never expected): ignored
        if (data.readUTF() != key) return@load null
        val savedAt = data.readLong()
        VodBinary.readDetails(data)?.let { KeptDetails(savedAt, it) }
    }

    private fun sheetsDir() = File(dir, SHEETS_DIR)

    // Named by a hash of the key: keys hold titles, never a path
    private fun sheetFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(sheetsDir(), digest.take(16).joinToString("") { "%02x".format(it) } + SHEET_SUFFIX)
    }

    // ---------------------------------------------------------------- Posters found

    /** Posters found for titles without one, by title key ("" when there is none). */
    fun savePosters(posters: Map<String, String>) = save(File(dir, POSTERS_FILE)) { out ->
        out.writeInt(posters.size)
        posters.forEach { (key, url) ->
            VodBinary.text(out, key)
            VodBinary.text(out, url)
        }
    }

    fun posters(): Map<String, String> = load(File(dir, POSTERS_FILE)) { data ->
        val count = VodBinary.count(data, MAX_POSTERS)
        LinkedHashMap<String, String>(count).apply {
            repeat(count) {
                val key = VodBinary.text(data) ?: return@repeat
                put(key, VodBinary.text(data).orEmpty())
            }
        }
    }.orEmpty()

    fun clear() {
        dir.deleteRecursively()
    }

    private fun save(file: File, block: (java.io.DataOutputStream) -> Unit) {
        try {
            VodBinary.write(file, secrets) { out ->
                out.writeInt(FORMAT)
                block(out)
            }
        } catch (_: IOException) {
            // Shown from memory this time
        } catch (_: java.security.GeneralSecurityException) {
            // Key unavailable: shown from memory this time
        }
    }

    private fun <T> load(file: File, block: (java.io.DataInputStream) -> T?): T? = try {
        VodBinary.read(file, secrets) { data -> if (data.readInt() != FORMAT) null else block(data) }
    } catch (_: IOException) {
        null
    } catch (_: java.security.GeneralSecurityException) {
        // Encrypted with another key (the app reinstalled) or tampered with
        null
    } catch (_: RuntimeException) {
        // Broken file (unexpected values)
        null
    }

    companion object {
        // Bumped when what is written changes: the files of the older format are ignored
        private const val FORMAT = 1
        private const val LIBRARY_FILE = "library.bin"
        private const val ROWS_FILE = "rows.bin"
        private const val POSTERS_FILE = "posters.bin"
        private const val SHEETS_DIR = "sheets"
        private const val SHEET_SUFFIX = ".bin"
        const val MAX_SHEETS = 500
        const val MAX_POSTERS = 5_000
    }
}
