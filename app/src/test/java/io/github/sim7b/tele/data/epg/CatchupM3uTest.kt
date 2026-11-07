package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.parser.M3uParser
import io.github.sim7b.tele.data.remote.M3uClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class CatchupM3uTest {

    // 2026-11-01 18:30 UTC, a program from 16:00 to 17:00
    private val now = 1_793_557_800_000L
    private val start = 1_793_548_800_000L
    private val end = start + 60 * 60_000L
    private val utc = ZoneId.of("UTC")

    @Test
    fun `the placeholders of a template are filled from the program`() {
        assertEquals(
            "http://tv.example/c.ts?start=1793548800&end=1793552400&dur=3600&min=60&now=1793557800",
            Catchup.fill("http://tv.example/c.ts?start={utc}&end=\${end}&dur={duration}&min={duration:60}&now={lutc}", start, end, now, utc)
        )
        assertEquals("http://tv.example/2026/11/01/16-00-00/c.ts", Catchup.fill("http://tv.example/{Y}/{m}/{d}/{H}-{M}-{S}/c.ts", start, end, now, utc))
        assertEquals("http://tv.example/c.ts?t=20261101160000", Catchup.fill("http://tv.example/c.ts?t={utc:YmdHMS}", start, end, now, utc))
        // Offset: how long ago it started
        assertEquals("http://tv.example/c?o=150", Catchup.fill("http://tv.example/c?o={offset:60}", start, end, now, utc))
        // A broken date pattern: no URL rather than a wrong one
        assertNull(Catchup.fill("http://tv.example/c?t={utc:QQQQQQ}", start, end, now, utc))
    }

    @Test
    fun `each catch-up mode makes its replay url`() {
        val live = "http://tv.example/live/42.ts"
        assertEquals(
            "http://tv.example/live/42.ts?utc=1793548800&lutc=1793557800",
            Catchup.m3uReplayUrl("shift", null, live, start, end, now)
        )
        assertEquals(
            "http://tv.example/live/42.ts?token=x&utc=1793548800&lutc=1793557800",
            Catchup.m3uReplayUrl("shift", null, "$live?token=x", start, end, now)
        )
        assertEquals(
            "http://tv.example/live/42.ts?archive=1793548800",
            Catchup.m3uReplayUrl("append", "?archive={utc}", live, start, end, now)
        )
        assertEquals(
            "http://archive.example/42/1793548800-3600.ts",
            Catchup.m3uReplayUrl("default", "http://archive.example/42/{utc}-{duration}.ts", live, start, end, now)
        )
        assertEquals(
            "http://fl.example/ch/index-1793548800-3600.m3u8?token=x",
            Catchup.m3uReplayUrl("flussonic", null, "http://fl.example/ch/index.m3u8?token=x", start, end, now)
        )
        assertEquals(
            "http://fl.example/ch/timeshift_abs-1793548800.ts",
            Catchup.m3uReplayUrl("fs", null, "http://fl.example/ch/mpegts", start, end, now)
        )
        // Without its template, or an unknown mode, or not a URL: no replay
        assertNull(Catchup.m3uReplayUrl("default", null, live, start, end, now))
        assertNull(Catchup.m3uReplayUrl("vod", null, live, start, end, now))
        assertNull(Catchup.m3uReplayUrl("default", "{utc}", live, start, end, now))
    }

    @Test
    fun `an Xtream channel keeps its timeshift url, an M3U one its template`() {
        val xtream = Channel(id = "x", name = "X", group = "", streamUrl = "http://tv.example/live/u/p/9.ts", catchupDays = 3, catchupZone = "UTC")
        assertEquals("http://tv.example/timeshift/u/p/60/2026-11-01:16-00/9.ts", Catchup.replayUrl(xtream, start, end, now))
        val m3u = xtream.copy(streamUrl = "http://tv.example/9.ts", catchupMode = "shift")
        assertEquals("http://tv.example/9.ts?utc=1793548800&lutc=1793557800", Catchup.replayUrl(m3u, start, end, now))
    }

    @Test
    fun `the playlist announces the replay of its channels`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="default" catchup-days="7" catchup-source="http://arc.example/a/{utc}.ts",A
            http://tv.example/a.ts
            #EXTINF:-1 tvg-id="b" timeshift="3",B
            http://tv.example/b.ts
            #EXTINF:-1 tvg-id="c" catchup="append" catchup-source="?t={utc}",C
            http://tv.example/c.ts
            #EXTINF:-1 tvg-id="d",D
            http://tv.example/d.ts
        """.trimIndent()
        val entries = M3uParser.parse(playlist.byteInputStream()).entries
        val channels = M3uClient.toChannels(entries, "m3u-")
        assertEquals(listOf("default", "shift", "append", null), channels.map { it.catchupMode })
        // Days said, or the usual default when not
        assertEquals(listOf(7, 3, 5, 0), channels.map { it.catchupDays })
        assertEquals("http://arc.example/a/{utc}.ts", channels[0].catchupSource)
    }
}
