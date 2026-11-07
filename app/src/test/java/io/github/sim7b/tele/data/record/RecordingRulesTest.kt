package io.github.sim7b.tele.data.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRulesTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L

    private fun recording(start: Long, end: Long, state: RecordingState = RecordingState.SCHEDULED, channel: String = "c") = Recording(
        id = RecordingRules.idOf(channel, start),
        channelId = channel,
        channelName = "C",
        sourceId = "s",
        title = "Show",
        startMillis = start,
        endMillis = end,
        url = "http://example.test/c.ts",
        state = state
    )

    @Test
    fun `only streams written as they come can be recorded`() {
        assertTrue(RecordingRules.canRecord("http://example.test/live/1.ts"))
        assertTrue(RecordingRules.canRecord("https://dispatcharr.test/proxy/ts/stream/uuid"))
        assertFalse(RecordingRules.canRecord("http://example.test/live/1.m3u8?token=x"))
        assertFalse(RecordingRules.canRecord("file:///sdcard/x.ts"))
    }

    @Test
    fun `a recording is written from a minute before its program to a few after, a broken guide entry capped`() {
        val show = recording(now + 10 * minute, now + 70 * minute)
        assertEquals(now + 9 * minute, RecordingRules.beginAt(show))
        assertEquals(now + 73 * minute, RecordingRules.stopAt(show))
        val endless = recording(now, now + 100 * 60 * minute)
        assertEquals(now + RecordingRules.MAX_DURATION_MS, RecordingRules.stopAt(endless))
    }

    @Test
    fun `the due, missed and next recordings`() {
        val onAir = recording(now - 10 * minute, now + 20 * minute)
        val later = recording(now + 60 * minute, now + 90 * minute, channel = "d")
        val missed = recording(now - 120 * minute, now - 60 * minute, channel = "e")
        val done = recording(now - 30 * minute, now + 10 * minute, RecordingState.DONE, channel = "f")
        // Cut off by a restart of the app: written again
        val interrupted = recording(now - 5 * minute, now + 5 * minute, RecordingState.RECORDING, channel = "g")
        val all = listOf(onAir, later, missed, done, interrupted)
        assertEquals(listOf(onAir, interrupted), RecordingRules.due(all, now))
        assertEquals(listOf(missed), RecordingRules.missed(all, now))
        assertEquals(now + 59 * minute, RecordingRules.nextBeginAt(all, now))
        assertNull(RecordingRules.nextBeginAt(listOf(done), now))
    }

    @Test
    fun `asked twice, a program is cancelled, and the list survives a round trip without its broken entries`() {
        val show = recording(now, now + 30 * minute)
        val list = RecordingRules.toggle(emptyList(), show)
        assertEquals(listOf(show), list)
        assertTrue(RecordingRules.toggle(list, show.copy(title = "Other")).isEmpty())

        val saved = RecordingRules.toJson(listOf(show, show.copy(id = "../../etc", startMillis = now + 1)))
        // The id is a file name: one that is not ours is dropped
        assertEquals(listOf(show), RecordingRules.fromJson(saved))
        assertTrue(RecordingRules.fromJson("not json").isEmpty())
    }
}
