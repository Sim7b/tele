package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.vod.VodEntry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Catalog of a source as last loaded, with the time it was loaded ([savedAt], completed with the titles
 * added since) and the time of its last full load ([fullAt]: titles removed from the source go then).
 */
class CachedVodCatalog(val savedAt: Long, val entries: List<VodEntry>, val fullAt: Long = savedAt)

/**
 * Movies and series of a source, for a library shown at once and reloaded in the background. A compact
 * binary format (catalogs hold tens of thousands of titles: parsing them as JSON took seconds on a TV),
 * encrypted: Xtream file URLs embed the account credentials. To be used off the main thread.
 */
class VodCatalogCache(private val file: File, private val secrets: SecretBox) {

    fun save(configId: String, savedAt: Long, entries: List<VodEntry>, fullAt: Long = savedAt) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(secrets.encryptingStream(temp.outputStream()), VodBinary.BUFFER)).use { out ->
                out.writeInt(FORMAT)
                out.writeUTF(configId)
                out.writeLong(savedAt)
                out.writeLong(fullAt)
                out.writeInt(entries.size)
                entries.forEach { VodBinary.writeEntry(out, it) }
            }
            if (!temp.renameTo(file)) throw IOException("Unable to replace the catalog cache")
        } finally {
            temp.delete()
        }
    }

    /** Cached catalog of [configId]; null when absent, of another source or of an older format. */
    fun load(configId: String): CachedVodCatalog? {
        if (!file.exists()) return null
        val input = BufferedInputStream(file.inputStream())
        // The encrypted stream starts with its marker, read before the decryption (see ChannelCache)
        val magic = ByteArray(SecretBox.STREAM_MAGIC.size)
        if (input.read(magic) != magic.size || !SecretBox.isStreamMagic(magic)) {
            input.close()
            return null
        }
        DataInputStream(BufferedInputStream(secrets.decryptingStream(input, magic), VodBinary.BUFFER)).use { data ->
            val format = data.readInt()
            // Format 2 had no time of full load: the time it was saved
            if (format != FORMAT && format != FORMAT_WITHOUT_FULL_LOAD) return null
            if (data.readUTF() != configId) return null
            val savedAt = data.readLong()
            val fullAt = if (format == FORMAT) data.readLong() else savedAt
            val count = data.readInt()
            if (count !in 0..MAX_ENTRIES) return null
            val entries = ArrayList<VodEntry>(count)
            repeat(count) { VodBinary.readEntry(data)?.let(entries::add) }
            return CachedVodCatalog(savedAt, entries, fullAt)
        }
    }

    /** When the catalog kept for [configId] was saved and fully loaded, read from its start only; null when absent. */
    fun stamp(configId: String): Pair<Long, Long>? = try {
        VodBinary.read(file, secrets) { data ->
            val format = data.readInt()
            if (format != FORMAT && format != FORMAT_WITHOUT_FULL_LOAD) return@read null
            if (data.readUTF() != configId) return@read null
            val savedAt = data.readLong()
            savedAt to if (format == FORMAT) data.readLong() else savedAt
        }
    } catch (_: IOException) {
        null
    } catch (_: java.security.GeneralSecurityException) {
        null
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        const val FORMAT = 3
        private const val FORMAT_WITHOUT_FULL_LOAD = 2
        const val MAX_ENTRIES = 1_000_000
    }
}
