package io.github.sim7b.tele.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.github.sim7b.tele.ui.components.clockLabel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimeshiftBufferTest {

    /** A live stream: the bytes given to [send], then [end] or [fail]. */
    private class FakeStream : DataSource {
        private val queue = LinkedBlockingQueue<Any>()
        private var current: ByteArray? = null
        private var position = 0
        fun send(bytes: ByteArray) = queue.put(bytes)
        fun end() = queue.put(END)
        fun fail(error: IOException) = queue.put(error)
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytes = current?.takeIf { position < it.size } ?: when (val next = queue.take()) {
                END -> return C.RESULT_END_OF_INPUT
                is IOException -> throw next
                else -> (next as ByteArray).also {
                    current = it
                    position = 0
                }
            }
            val count = minOf(length, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, count)
            position += count
            return count
        }
        override fun getUri(): Uri? = null
        override fun close() = Unit
        companion object { val END = Any() }
    }

    private val dir: File = Files.createTempDirectory("timeshift").toFile()
    private var now = 0L

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun readAll(buffer: TimeshiftBuffer, from: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val read = buffer.read(from + done, out, done, size - done)
            if (read < 0) break
            done += read
        }
        return out.copyOf(done)
    }

    @Test
    fun `the live channel is read back as it was received, waiting for the live data`() {
        val stream = FakeStream()
        val buffer = TimeshiftBuffer(File(dir, "a"), stream, Uri.EMPTY, clock = { now }, freeSpace = { Long.MAX_VALUE })
        buffer.start()
        val first = ByteArray(1000) { it.toByte() }
        stream.send(first)
        assertArrayEquals(first, readAll(buffer, 0, 1000))
        // The reader waits for what comes next
        val second = ByteArray(500) { 7 }
        Thread { Thread.sleep(100); stream.send(second) }.start()
        assertArrayEquals(second, readAll(buffer, 1000, 500))
        stream.end()
        assertEquals(C.RESULT_END_OF_INPUT, buffer.read(1500, ByteArray(10), 0, 10))
        buffer.close()
    }

    @Test
    fun `a network failure reaches the player once it has read what came before`() {
        val stream = FakeStream()
        val buffer = TimeshiftBuffer(File(dir, "b"), stream, Uri.EMPTY, clock = { now }, freeSpace = { Long.MAX_VALUE })
        buffer.start()
        stream.send(ByteArray(100) { 1 })
        stream.fail(IOException("HTTP 503"))
        assertEquals(100, readAll(buffer, 0, 100).size)
        try {
            buffer.read(100, ByteArray(10), 0, 10)
            fail("The failure is expected")
        } catch (e: IOException) {
            assertEquals("HTTP 503", e.message)
        }
        buffer.close()
    }

    @Test
    fun `past the time kept, the oldest parts go and the offsets follow the time received`() {
        val buffer = TimeshiftBuffer(File(dir, "c"), FakeStream(), Uri.EMPTY, maxDurationMs = 10_000, clock = { now }, freeSpace = { Long.MAX_VALUE })
        val chunk = ByteArray(TimeshiftBuffer.CHUNK_BYTES.toInt())
        // One chunk every 4 s, written straight (no network thread)
        repeat(5) {
            buffer.append(chunk, chunk.size)
            now += 4_000
        }
        buffer.append(ByteArray(10), 10)
        // The chunks written more than 10 s ago are gone
        assertTrue(buffer.startOffset() > 0)
        assertEquals(buffer.startOffset(), buffer.offsetAt(0))
        assertEquals(4 * TimeshiftBuffer.CHUNK_BYTES, buffer.offsetAt(16_000))
        assertTrue(buffer.windowMs() <= 12_000)
        try {
            buffer.read(0, ByteArray(10), 0, 10)
            fail("A deleted position is expected to fail")
        } catch (_: IOException) {
        }
        buffer.close()
        assertFalse(File(dir, "c").exists())
    }

    @Test
    fun `short of storage the oldest parts go too`() {
        var free = Long.MAX_VALUE
        val buffer = TimeshiftBuffer(File(dir, "d"), FakeStream(), Uri.EMPTY, reserveBytes = 1_000, clock = { now }, freeSpace = { free })
        val chunk = ByteArray(TimeshiftBuffer.CHUNK_BYTES.toInt())
        buffer.append(chunk, chunk.size)
        buffer.append(chunk, chunk.size)
        assertEquals(0, buffer.startOffset())
        free = 10
        buffer.append(ByteArray(10), 10)
        assertEquals(2 * TimeshiftBuffer.CHUNK_BYTES, buffer.startOffset())
        buffer.close()
    }

    @Test
    fun `durations are written as on a clock`() {
        assertEquals("0:05", clockLabel(5_400))
        assertEquals("2:05", clockLabel(125_000))
        assertEquals("1:02:05", clockLabel(3_725_000))
        assertEquals("0:00", clockLabel(-10))
    }
}
