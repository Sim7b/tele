package io.github.sim7b.tele.data.subtitles

import androidx.media3.common.MimeTypes
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** A subtitle file added to a stream: [uri] on the device, its [language] and the [label] shown. */
data class ExternalSubtitle(val uri: String, val mimeType: String, val language: String, val label: String)

/** A subtitle downloaded for a movie or an episode, kept on the device. */
data class CachedSubtitle(val file: File, val fileId: Long, val language: String) {
    val mimeType: String get() = SubtitleCache.mimeType(file.extension)
}

/**
 * Subtitles downloaded from OpenSubtitles, kept on the device by movie or episode: playing it again adds
 * them without any request (each download counts in the daily quota). The oldest go past [maxFiles].
 */
class SubtitleCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {

    /** Subtitles of [itemKey] (a progress key: title, or title and episode), the latest first. */
    fun list(itemKey: String): List<CachedSubtitle> {
        val prefix = prefix(itemKey)
        return dir.listFiles { file -> file.name.startsWith("${prefix}_") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file -> parse(file) }
    }

    fun save(itemKey: String, fileId: Long, language: String, extension: String, bytes: ByteArray): CachedSubtitle {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Unable to create the subtitles folder")
        val safeLanguage = language.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it == '-' }.take(8).ifEmpty { "und" }
        val safeExtension = extension.lowercase(Locale.ROOT).takeIf { it in EXTENSIONS } ?: "srt"
        val file = File(dir, "${prefix(itemKey)}_${fileId}_$safeLanguage.$safeExtension")
        val temp = File(dir, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Unable to save the subtitles")
        }
        prune()
        return CachedSubtitle(file, fileId, safeLanguage)
    }

    private fun prune() {
        val files = dir.listFiles { file -> !file.name.endsWith(".tmp") }.orEmpty()
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    private fun parse(file: File): CachedSubtitle? {
        if (file.extension !in EXTENSIONS) return null
        // prefix_fileId_language.extension
        val parts = file.nameWithoutExtension.split('_')
        if (parts.size != 3) return null
        val fileId = parts[1].toLongOrNull() ?: return null
        return CachedSubtitle(file, fileId, parts[2])
    }

    /** First 16 hex digits of the SHA-256 of the key: no title in the file names. */
    private fun prefix(itemKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(itemKey.toByteArray()).take(8).joinToString("") { "%02x".format(Locale.ROOT, it) }

    companion object {
        private const val MAX_FILES = 300
        private val EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml")

        fun mimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}
