package io.github.sim7b.tele.data.parser

import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.UserFacingException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.Reader

internal fun responseTooLarge() = UserFacingException(UiText.Res(R.string.error_response_too_large))

internal fun invalidResponse() = UserFacingException(UiText.Res(R.string.error_invalid_response))

/** Stream failing once more than [maxBytes] have been read (decompressed playlists and guides). */
internal open class CappedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) onRead(byteArrayOf(it.toByte()), 0, 1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) onRead(b, off, it) }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }

    override fun markSupported() = false

    protected open fun onRead(b: ByteArray, off: Int, len: Int) {
        count += len
        if (count > maxBytes) throw responseTooLarge()
    }
}

/**
 * Line reader keeping at most [maxLength] characters per line (the rest is skipped), so that a single huge line
 * cannot exhaust memory. Lines end with \n, \r\n or \r.
 */
internal class BoundedLineReader(private val reader: Reader, private val maxLength: Int) {
    private val buffer = CharArray(64 * 1024)
    private var pos = 0
    private var end = 0
    private var skipLineFeed = false
    private val line = StringBuilder()

    /** Next line without its terminator, or null at the end of the stream. */
    fun readLine(): String? {
        line.setLength(0)
        var started = false
        while (true) {
            if (pos == end) {
                end = reader.read(buffer, 0, buffer.size).coerceAtLeast(0)
                pos = 0
                if (end == 0) return if (started) line.toString() else null
            }
            if (skipLineFeed) {
                skipLineFeed = false
                if (buffer[pos] == '\n') {
                    pos++
                    continue
                }
            }
            started = true
            val start = pos
            while (pos < end && buffer[pos] != '\n' && buffer[pos] != '\r') pos++
            val room = maxLength - line.length
            if (room > 0) line.append(buffer, start, minOf(pos - start, room))
            if (pos < end) {
                skipLineFeed = buffer[pos] == '\r'
                pos++
                return line.toString()
            }
        }
    }
}
