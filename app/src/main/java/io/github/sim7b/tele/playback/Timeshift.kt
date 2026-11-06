package io.github.sim7b.tele.playback

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** Where the live channel is: how much is kept, how far behind live it plays, paused or not. */
data class TimeshiftState(val windowMs: Long, val behindMs: Long, val paused: Boolean)

/**
 * The live channel kept on the device while it plays, to pause it and go back in it: read from the network by
 * its own thread (through [upstream], the player's HTTP stack: same key, same errors) into [CHUNK_BYTES] files,
 * the oldest ones deleted past [maxDurationMs], past [maxBytes] or when the storage gets under [reserveBytes].
 * The player reads it back with [TimeshiftDataSource]; a network failure reaches it once it has read
 * everything before it.
 */
@OptIn(UnstableApi::class)
class TimeshiftBuffer(
    private val dir: File,
    private val upstream: DataSource,
    private val uri: Uri,
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val maxBytes: Long = MAX_BYTES,
    private val reserveBytes: Long = RESERVE_BYTES,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val freeSpace: () -> Long = { dir.usableSpace }
) {

    private class Chunk(val start: Long, val file: File) {
        var length = 0L
        var lastWriteMs = 0L
        val end get() = start + length
    }

    // Time a byte offset was received (every MARK_INTERVAL_MS): where to go back to
    private class Mark(val timeMs: Long, val offset: Long)

    private val lock = Object()
    private val chunks = ArrayDeque<Chunk>()
    private val marks = ArrayDeque<Mark>()
    private var output: FileOutputStream? = null
    private var end = 0L
    private var failure: IOException? = null
    private var finished = false
    @Volatile
    private var closed = false
    private var thread: Thread? = null

    fun start() {
        dir.mkdirs()
        thread = Thread(::record, "timeshift").apply {
            isDaemon = true
            start()
        }
    }

    private fun record() {
        val buffer = ByteArray(READ_BYTES)
        try {
            upstream.open(DataSpec(uri))
            while (!closed) {
                val read = upstream.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) append(buffer, read)
            }
        } catch (e: IOException) {
            synchronized(lock) { failure = e }
        } finally {
            try {
                upstream.close()
            } catch (_: IOException) {
                // Already gone
            }
            synchronized(lock) {
                finished = true
                output?.close()
                output = null
                if (closed) deleteAll()
                lock.notifyAll()
            }
        }
    }

    /** Called by the recording thread only; written under the lock, the readers see whole writes. */
    internal fun append(data: ByteArray, length: Int) = synchronized(lock) {
        if (closed) return
        val now = clock()
        var chunk = chunks.lastOrNull()
        if (chunk == null || chunk.length >= CHUNK_BYTES) {
            output?.close()
            dir.mkdirs()
            chunk = Chunk(end, File(dir, "${end}.ts"))
            chunks.addLast(chunk)
            output = FileOutputStream(chunk.file)
        }
        if (marks.isEmpty() || now - marks.last().timeMs >= MARK_INTERVAL_MS) marks.addLast(Mark(now, end))
        output!!.write(data, 0, length)
        chunk.length += length
        chunk.lastWriteMs = now
        end += length
        evict(now)
        lock.notifyAll()
    }

    private fun evict(now: Long) {
        while (chunks.size > 1) {
            val oldest = chunks.first()
            val bytes = end - oldest.start
            val tooOld = now - oldest.lastWriteMs > maxDurationMs
            if (!tooOld && bytes <= maxBytes && freeSpace() >= reserveBytes) break
            chunks.removeFirst()
            oldest.file.delete()
            val start = chunks.first().start
            while (marks.size > 1 && marks[1].offset <= start) marks.removeFirst()
        }
    }

    /** First byte still kept. */
    fun startOffset(): Long = synchronized(lock) { chunks.firstOrNull()?.start ?: 0L }

    /**
     * Reads at [position] into [target]: waits for the live data, then the end of the recording (its failure,
     * or [C.RESULT_END_OF_INPUT]). A position already deleted fails.
     */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        val chunk: Chunk
        val available: Long
        synchronized(lock) {
            while (position >= end && failure == null && !finished && !closed) lock.wait(WAIT_MS)
            if (closed) return C.RESULT_END_OF_INPUT
            if (position >= end) {
                failure?.let { throw it }
                return C.RESULT_END_OF_INPUT
            }
            chunk = chunks.lastOrNull { it.start <= position } ?: throw IOException("Timeshift position deleted")
            if (position < chunks.first().start) throw IOException("Timeshift position deleted")
            available = chunk.end - position
        }
        val count = minOf(length.toLong(), available).toInt()
        RandomAccessFile(chunk.file, "r").use { file ->
            file.seek(position - chunk.start)
            return file.read(target, offset, count)
        }
    }

    /** Byte offset received at [timeMs] (the oldest kept before it). */
    fun offsetAt(timeMs: Long): Long = synchronized(lock) {
        val start = chunks.firstOrNull()?.start ?: 0L
        (marks.lastOrNull { it.timeMs <= timeMs }?.offset ?: start).coerceAtLeast(start)
    }

    /** How long the kept part lasts. */
    fun windowMs(): Long = synchronized(lock) {
        val first = marks.firstOrNull() ?: return 0L
        (clock() - first.timeMs).coerceAtLeast(0)
    }

    /** Stops the recording; its files go once its thread ends. */
    fun close() {
        closed = true
        synchronized(lock) {
            lock.notifyAll()
            if (finished || thread == null) deleteAll()
        }
    }

    private fun deleteAll() {
        output?.close()
        output = null
        chunks.forEach { it.file.delete() }
        chunks.clear()
        marks.clear()
        dir.delete()
    }

    companion object {
        const val MAX_DURATION_MS = 60 * 60_000L
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
        // Left free for the system and the other apps
        const val RESERVE_BYTES = 2L * 1024 * 1024 * 1024
        // Below it at the start, no timeshift: the channel plays straight from the network
        const val MIN_FREE_BYTES = RESERVE_BYTES + 256L * 1024 * 1024
        internal const val CHUNK_BYTES = 4L * 1024 * 1024
        private const val READ_BYTES = 64 * 1024
        private const val MARK_INTERVAL_MS = 500L
        private const val WAIT_MS = 250L
    }
}

/** The player reading a [TimeshiftBuffer] from byte [from] (a spot in the past, or the live edge). */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val buffer: TimeshiftBuffer, private val from: Long) : BaseDataSource(false) {

    private var uri: Uri? = null
    private var position = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = from + dataSpec.position
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = this.buffer.read(position, buffer, offset, length)
        if (read > 0) {
            position += read
            bytesTransferred(read)
        }
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    class Factory(private val buffer: TimeshiftBuffer, private val from: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(buffer, from)
    }
}
