package io.github.sim7b.tele.data.transfer

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/**
 * Backup of the configuration in a file (a USB key, the storage of the TV): the same content as a transfer
 * to another TV ([io.github.sim7b.tele.data.storage.SettingsRepository.exportSettings]), encrypted with a
 * code shown when it is made ([TransferCrypto]). Without the code, the file is useless: it holds the
 * credentials of the sources.
 */
object BackupFile {

    private const val MARK_KEY = "tele"
    private const val MARK = "backup"
    // A backup is a few hundred KB at most: anything bigger is not one
    const val MAX_BYTES = 4 * 1024 * 1024

    /** Content of the file: [plain] encrypted with [code], marked as a Télé backup. */
    fun seal(plain: String, code: String): String =
        JSONObject(TransferCrypto.seal(plain, code)).put(MARK_KEY, MARK).toString()

    /** The configuration of a backup; throws on a file that is not one, a wrong code or a modified file. */
    fun open(content: String, code: String): JSONObject {
        val json = try {
            JSONObject(content)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a backup")
        }
        require(json.optString(MARK_KEY) == MARK) { "Not a backup" }
        return JSONObject(TransferCrypto.open(content, code))
    }

    /** The file read, at most [MAX_BYTES]: a bigger one fails. */
    fun read(input: InputStream): String {
        val bytes = input.readNBytesCompat(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw IOException("Backup too large")
        return String(bytes, Charsets.UTF_8)
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
