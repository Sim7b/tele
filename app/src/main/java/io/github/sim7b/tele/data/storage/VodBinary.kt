package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.vod.StreamingPlatform
import io.github.sim7b.tele.data.vod.VodAudio
import io.github.sim7b.tele.data.vod.VodDetails
import io.github.sim7b.tele.data.vod.VodEntry
import io.github.sim7b.tele.data.vod.VodEpisode
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodLanguages
import io.github.sim7b.tele.data.vod.VodRow
import io.github.sim7b.tele.data.vod.VodSeason
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.data.vod.VodVersion
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Compact binary form of the movies and series kept on the device (catalogs, library, sheets, rows): JSON took
 * seconds on a TV for tens of thousands of titles. Always written encrypted ([write]): Xtream file URLs embed the
 * account credentials. Every count read is bounded: a broken file fails, it never allocates without limit.
 */
internal object VodBinary {

    const val BUFFER = 64 * 1024
    private const val MAX_TEXT = 16_000
    private const val MAX_LIST = 200
    private const val MAX_ITEMS = 1_000_000

    /** Writes [file] encrypted, beside then renamed: never half a file. */
    fun write(file: File, secrets: SecretBox, block: (DataOutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(secrets.encryptingStream(temp.outputStream()), BUFFER)).use(block)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw IOException("Unable to replace ${file.name}")
            }
        } finally {
            temp.delete()
        }
    }

    /** Reads [file] written by [write]; null when it is missing or not one of ours. */
    fun <T> read(file: File, secrets: SecretBox, block: (DataInputStream) -> T?): T? {
        if (!file.isFile) return null
        val input = BufferedInputStream(file.inputStream())
        // The encrypted stream starts with its marker, read before the decryption (see ChannelCache)
        val magic = ByteArray(SecretBox.STREAM_MAGIC.size)
        if (input.read(magic) != magic.size || !SecretBox.isStreamMagic(magic)) {
            input.close()
            return null
        }
        return DataInputStream(BufferedInputStream(secrets.decryptingStream(input, magic), BUFFER)).use(block)
    }

    // ---------------------------------------------------------------- Basic values

    /** Nullable text; writeUTF holds up to 64 KB, longer texts (never expected) are cut. */
    fun text(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(if (value.length > MAX_TEXT) value.take(MAX_TEXT) else value)
    }

    fun text(data: DataInputStream): String? = if (data.readBoolean()) data.readUTF() else null

    fun strings(out: DataOutputStream, values: Collection<String>) {
        out.writeShort(values.size.coerceAtMost(MAX_LIST))
        values.take(MAX_LIST).forEach { text(out, it) }
    }

    fun strings(data: DataInputStream): List<String> {
        val size = data.readUnsignedShort()
        if (size == 0) return emptyList()
        return List(size) { text(data).orEmpty() }
    }

    private fun optInt(out: DataOutputStream, value: Int?) = out.writeInt(value ?: Int.MIN_VALUE)
    private fun optInt(data: DataInputStream): Int? = data.readInt().takeIf { it != Int.MIN_VALUE }

    /** A count of items, refused past [max]. */
    fun count(data: DataInputStream, max: Int = MAX_ITEMS): Int {
        val count = data.readInt()
        if (count !in 0..max) throw IOException("Unexpected count $count")
        return count
    }

    fun <T> list(out: DataOutputStream, items: List<T>, write: (DataOutputStream, T) -> Unit) {
        out.writeInt(items.size)
        items.forEach { write(out, it) }
    }

    fun <T> list(data: DataInputStream, read: (DataInputStream) -> T?): List<T> {
        val count = count(data)
        val items = ArrayList<T>(count.coerceAtMost(4096))
        repeat(count) { read(data)?.let(items::add) }
        return items
    }

    // ---------------------------------------------------------------- Entries (catalogs)

    fun writeEntry(out: DataOutputStream, entry: VodEntry) {
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

    fun readEntry(data: DataInputStream): VodEntry? {
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

    // ---------------------------------------------------------------- Titles (library, rows)

    fun writeTitle(out: DataOutputStream, title: VodTitle) {
        out.writeUTF(title.key)
        out.writeByte(title.kind.ordinal)
        text(out, title.title)
        optInt(out, title.year)
        text(out, title.posterUrl)
        text(out, title.backdropUrl)
        text(out, title.plot)
        strings(out, title.genres)
        out.writeDouble(title.rating ?: -1.0)
        text(out, title.tmdbId)
        text(out, title.imdbId)
        out.writeLong(title.addedAt ?: Long.MIN_VALUE)
        list(out, title.entries, ::writeEntry)
        strings(out, title.languages.audio)
        strings(out, title.languages.subtitles)
    }

    fun readTitle(data: DataInputStream): VodTitle? {
        val key = data.readUTF()
        val kind = VodKind.entries.getOrNull(data.readUnsignedByte())
        val title = VodTitle(
            key = key,
            kind = kind ?: VodKind.MOVIE,
            title = text(data).orEmpty(),
            year = optInt(data),
            posterUrl = text(data),
            backdropUrl = text(data),
            plot = text(data),
            genres = strings(data),
            rating = data.readDouble().takeIf { it >= 0 },
            tmdbId = text(data),
            imdbId = text(data),
            addedAt = data.readLong().takeIf { it != Long.MIN_VALUE },
            entries = list(data, ::readEntry),
            languages = VodLanguages(strings(data).toSet(), strings(data).toSet())
        )
        return title.takeIf { kind != null }
    }

    fun writeRow(out: DataOutputStream, row: VodRow) {
        out.writeByte(row.kind.ordinal)
        list(out, row.titles, ::writeTitle)
        text(out, row.name)
        out.writeBoolean(row.ranked)
        text(out, row.collectionId)
        text(out, row.platform?.name)
    }

    fun readRow(data: DataInputStream): VodRow? {
        val kind = VodRow.Kind.entries.getOrNull(data.readUnsignedByte())
        val row = VodRow(
            kind = kind ?: VodRow.Kind.TRENDING,
            titles = list(data, ::readTitle),
            name = text(data),
            ranked = data.readBoolean(),
            collectionId = text(data),
            platform = text(data)?.let { name -> StreamingPlatform.entries.firstOrNull { it.name == name } }
        )
        return row.takeIf { kind != null }
    }

    // ---------------------------------------------------------------- Sheets

    private fun writeVersion(out: DataOutputStream, version: VodVersion) {
        text(out, version.sourceId)
        text(out, version.sourceName)
        text(out, version.url)
        text(out, version.name)
        text(out, version.resolution)
        text(out, version.videoCodec)
        out.writeBoolean(version.hdr)
        optInt(out, version.bitrateKbps)
        strings(out, version.qualityTags)
        list(out, version.audio) { o, audio ->
            text(o, audio.language)
            text(o, audio.codec)
            optInt(o, audio.channels)
        }
        strings(out, version.languageTags)
        strings(out, version.subtitles)
        optInt(out, version.durationSecs)
    }

    private fun readVersion(data: DataInputStream): VodVersion = VodVersion(
        sourceId = text(data).orEmpty(),
        sourceName = text(data).orEmpty(),
        url = text(data).orEmpty(),
        name = text(data).orEmpty(),
        resolution = text(data),
        videoCodec = text(data),
        hdr = data.readBoolean(),
        bitrateKbps = optInt(data),
        qualityTags = strings(data),
        audio = list(data) { VodAudio(text(it), text(it), optInt(it)) },
        languageTags = strings(data),
        subtitles = strings(data),
        durationSecs = optInt(data)
    )

    private fun writeEpisode(out: DataOutputStream, episode: VodEpisode) {
        text(out, episode.key)
        out.writeInt(episode.season)
        out.writeInt(episode.number)
        text(out, episode.title)
        text(out, episode.plot)
        optInt(out, episode.durationSecs)
        text(out, episode.stillUrl)
        text(out, episode.airDate)
        list(out, episode.versions, ::writeVersion)
    }

    private fun readEpisode(data: DataInputStream): VodEpisode = VodEpisode(
        key = text(data).orEmpty(),
        season = data.readInt(),
        number = data.readInt(),
        title = text(data).orEmpty(),
        plot = text(data),
        durationSecs = optInt(data),
        stillUrl = text(data),
        airDate = text(data),
        versions = list(data, ::readVersion)
    )

    fun writeDetails(out: DataOutputStream, details: VodDetails) {
        writeTitle(out, details.title)
        optInt(out, details.durationSecs)
        text(out, details.director)
        strings(out, details.cast)
        list(out, details.versions, ::writeVersion)
        list(out, details.seasons) { o, season ->
            o.writeInt(season.number)
            list(o, season.episodes, ::writeEpisode)
        }
        text(out, details.trailerKey)
    }

    fun readDetails(data: DataInputStream): VodDetails? {
        val title = readTitle(data) ?: return null
        return VodDetails(
            title = title,
            durationSecs = optInt(data),
            director = text(data),
            cast = strings(data),
            versions = list(data, ::readVersion),
            seasons = list(data) { VodSeason(it.readInt(), list(it, ::readEpisode)) },
            trailerKey = text(data)
        )
    }
}
