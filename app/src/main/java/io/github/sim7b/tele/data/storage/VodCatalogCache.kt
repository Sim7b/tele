package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.vod.VodEntry
import io.github.sim7b.tele.data.vod.VodKind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Catalog of a source as last loaded, with the time it was loaded. */
class CachedVodCatalog(val savedAt: Long, val entries: List<VodEntry>)

/**
 * Movies and series of a source, for a library shown at once and reloaded in the background. A compact
 * binary format (catalogs hold tens of thousands of titles: parsing them as JSON took seconds on a TV),
 * encrypted: Xtream file URLs embed the account credentials. To be used off the main thread.
 */
class VodCatalogCache(private val file: File, private val secrets: SecretBox) {

    fun save(configId: String, savedAt: Long, entries: List<VodEntry>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(secrets.encryptingStream(temp.outputStream()), BUFFER)).use { out ->
                out.writeInt(FORMAT)
                out.writeUTF(configId)
                out.writeLong(savedAt)
                out.writeInt(entries.size)
                entries.forEach { write(out, it) }
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
        DataInputStream(BufferedInputStream(secrets.decryptingStream(input, magic), BUFFER)).use { data ->
            if (data.readInt() != FORMAT) return null
            if (data.readUTF() != configId) return null
            val savedAt = data.readLong()
            val count = data.readInt()
            if (count !in 0..MAX_ENTRIES) return null
            val entries = ArrayList<VodEntry>(count)
            repeat(count) { read(data)?.let(entries::add) }
            return CachedVodCatalog(savedAt, entries)
        }
    }

    fun clear() {
        file.delete()
    }

    private fun write(out: DataOutputStream, entry: VodEntry) {
        out.writeUTF(entry.sourceId)
        out.writeByte(entry.kind.ordinal)
        text(out, entry.providerId)
        text(out, entry.name)
        text(out, entry.title)
        out.writeInt(entry.year ?: 0)
        text(out, entry.posterUrl)
        text(out, entry.backdropUrl)
        text(out, entry.plot)
        strings(out, entry.genres)
        out.writeDouble(entry.rating ?: 0.0)
        text(out, entry.tmdbId)
        text(out, entry.imdbId)
        out.writeLong(entry.addedAt ?: 0L)
        text(out, entry.category)
        text(out, entry.streamUrl)
        strings(out, entry.qualityTags)
        strings(out, entry.languageTags)
        text(out, entry.uuid)
        text(out, entry.containerExtension)
        out.writeInt(entry.season ?: -1)
        out.writeInt(entry.episode ?: -1)
    }

    private fun read(data: DataInputStream): VodEntry? {
        val sourceId = data.readUTF()
        val kind = VodKind.entries.getOrNull(data.readUnsignedByte())
        val entry = VodEntry(
            sourceId = sourceId,
            kind = kind ?: VodKind.MOVIE,
            providerId = text(data).orEmpty(),
            name = text(data).orEmpty(),
            title = text(data).orEmpty(),
            year = data.readInt().takeIf { it > 0 },
            posterUrl = text(data),
            backdropUrl = text(data),
            plot = text(data),
            genres = strings(data),
            rating = data.readDouble().takeIf { it > 0 },
            tmdbId = text(data),
            imdbId = text(data),
            addedAt = data.readLong().takeIf { it > 0 },
            category = text(data),
            streamUrl = text(data),
            qualityTags = strings(data),
            languageTags = strings(data),
            uuid = text(data),
            containerExtension = text(data),
            season = data.readInt().takeIf { it >= 0 },
            episode = data.readInt().takeIf { it >= 0 }
        )
        return entry.takeIf { kind != null }
    }

    /** Nullable text; writeUTF holds up to 64 KB, longer texts (never expected) are cut. */
    private fun text(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(if (value.length > MAX_TEXT) value.take(MAX_TEXT) else value)
    }

    private fun text(data: DataInputStream): String? = if (data.readBoolean()) data.readUTF() else null

    private fun strings(out: DataOutputStream, values: List<String>) {
        out.writeShort(values.size.coerceAtMost(MAX_LIST))
        values.take(MAX_LIST).forEach { text(out, it) }
    }

    private fun strings(data: DataInputStream): List<String> {
        val size = data.readUnsignedShort()
        if (size == 0) return emptyList()
        return List(size) { text(data).orEmpty() }
    }

    private companion object {
        const val FORMAT = 2
        const val BUFFER = 64 * 1024
        const val MAX_ENTRIES = 1_000_000
        const val MAX_TEXT = 16_000
        const val MAX_LIST = 200
    }
}
