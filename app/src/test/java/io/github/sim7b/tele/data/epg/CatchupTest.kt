package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.remote.XtreamClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {

    private val hour = 60 * 60 * 1000L
    // 2026-11-01 18:30 UTC
    private val now = 1_793_557_800_000L

    private fun program(start: Long, end: Long) =
        GuideProgram(id = "p", channelUuid = "c", title = "News", startTimeIso = "", endTimeIso = "", startMillis = start, endMillis = end)

    @Test
    fun `the timeshift url keeps the server path and writes the start in the server time zone`() {
        val start = now - 2 * hour
        val url = Catchup.xtreamReplayUrl("http://tv.example:8080/live/user/secret/1234.ts", start, start + 45 * 60_000L + 1, "Europe/Paris")
        // 16:30 UTC is 17:30 in Paris (winter time); 45 min and 1 ms rounded up to 46 min
        assertEquals("http://tv.example:8080/timeshift/user/secret/46/2026-11-01:17-30/1234.ts", url)

        val inFolder = Catchup.xtreamReplayUrl("https://tv.example/panel/live/u/p/77.m3u8", start, start + hour, "UTC")
        assertEquals("https://tv.example/panel/timeshift/u/p/60/2026-11-01:16-30/77.ts", inFolder)
    }

    @Test
    fun `no timeshift url for other urls`() {
        assertNull(Catchup.xtreamReplayUrl("http://tv.example/proxy/ts/stream/abc", 0, hour, null))
        assertNull(Catchup.xtreamReplayUrl("http://tv.example/live/u/p/1.ts", hour, hour, null))
        assertNull(Catchup.xtreamReplayUrl("not a url", 0, hour, null))
    }

    @Test
    fun `only the past programs kept by the provider can be replayed`() {
        val channel = Channel(id = "c", name = "TF1", group = "", streamUrl = "", catchupDays = 2)
        assertTrue(Catchup.isReplayable(channel, program(now - 3 * hour, now - 2 * hour), now))
        // On air or to come: live
        assertFalse(Catchup.isReplayable(channel, program(now - hour, now + hour), now))
        // Older than the replay of the provider
        assertFalse(Catchup.isReplayable(channel, program(now - 72 * hour, now - 71 * hour), now))
        // No replay on this channel
        assertFalse(Catchup.isReplayable(channel.copy(catchupDays = 0), program(now - 3 * hour, now - 2 * hour), now))
    }

    @Test
    fun `a program on air kept by the provider can be started over`() {
        val channel = Channel(id = "c", name = "TF1", group = "", streamUrl = "", catchupDays = 2)
        assertTrue(Catchup.canStartOver(channel, program(now - hour, now + hour), now))
        // Just started: live is as good
        assertFalse(Catchup.canStartOver(channel, program(now - 30_000, now + hour), now))
        // Over (a replay), or to come
        assertFalse(Catchup.canStartOver(channel, program(now - 2 * hour, now - hour), now))
        assertFalse(Catchup.canStartOver(channel, program(now + hour, now + 2 * hour), now))
        // Started before what the provider keeps, or no replay on the channel
        assertFalse(Catchup.canStartOver(channel.copy(catchupDays = 1), program(now - 25 * hour, now + hour), now))
        assertFalse(Catchup.canStartOver(channel.copy(catchupDays = 0), program(now - hour, now + hour), now))
    }

    @Test
    fun `xtream archive fields give the days of replay`() {
        assertEquals(7, XtreamClient.catchupDays(JSONObject("""{"tv_archive": 1, "tv_archive_duration": "7"}""")))
        assertEquals(3, XtreamClient.catchupDays(JSONObject("""{"tv_archive": "1", "tv_archive_duration": 3}""")))
        assertEquals(0, XtreamClient.catchupDays(JSONObject("""{"tv_archive": 0, "tv_archive_duration": "7"}""")))
        assertEquals(0, XtreamClient.catchupDays(JSONObject("""{"tv_archive": 1, "tv_archive_duration": "999"}""")))
        assertEquals(0, XtreamClient.catchupDays(JSONObject("""{"tv_archive": 1}""")))
    }

    @Test
    fun `dispatcharr catch-up url from the live url of the channel`() {
        val start = 1_794_254_400_000L
        val url = Catchup.dispatcharrReplayUrl("https://tv.example/proxy/ts/stream/abc-123", start, start + 40 * 60_000L)
        assertEquals("https://tv.example/proxy/catchup/abc-123?start=1794254400&duration=40", url)
        // Behind a path prefix
        assertEquals(
            "https://example.org/dispatcharr/proxy/catchup/u1?start=1794254400&duration=1",
            Catchup.dispatcharrReplayUrl("https://example.org/dispatcharr/proxy/ts/stream/u1", start, start + 30_000L)
        )
        assertNull(Catchup.dispatcharrReplayUrl("https://tv.example/proxy/ts/stream/", start, start + 60_000L))
        assertNull(Catchup.dispatcharrReplayUrl("http://provider.example/live/u/p/1.ts", start, start + 60_000L))
        assertNull(Catchup.dispatcharrReplayUrl("https://tv.example/proxy/ts/stream/abc", start, start))

        val channel = io.github.sim7b.tele.data.model.DispatcharrChannel(
            id = "1", uuid = "abc", name = "TF1", group = "", streamUrl = "https://tv.example/proxy/ts/stream/abc", catchupDays = 7
        ).toChannel()
        assertEquals(Catchup.DISPATCHARR, channel.catchupMode)
        assertEquals(
            "https://tv.example/proxy/catchup/abc?start=1794254400&duration=40",
            Catchup.replayUrl(channel, start, start + 40 * 60_000L, start + 3_600_000L)
        )
    }
}
