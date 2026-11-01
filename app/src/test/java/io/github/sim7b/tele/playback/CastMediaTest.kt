package io.github.sim7b.tele.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMediaTest {

    @Test
    fun `the type of the stream comes from its extension`() {
        assertEquals(CastMedia.HLS, CastMedia.mimeType("http://host/live/user/pass/12.m3u8", live = true))
        assertEquals(CastMedia.HLS, CastMedia.mimeType("https://host/hls/index.M3U8?token=abc", live = true))
        assertEquals(CastMedia.DASH, CastMedia.mimeType("https://host/manifest.mpd", live = true))
        assertEquals(CastMedia.TS, CastMedia.mimeType("http://host/live/user/pass/12.ts", live = true))
        assertEquals(CastMedia.MP4, CastMedia.mimeType("http://host/movie/user/pass/7.mp4", live = false))
        assertEquals("video/x-matroska", CastMedia.mimeType("http://host/movie/user/pass/7.mkv", live = false))
    }

    @Test
    fun `a stream without extension is MPEG-TS when live, a file otherwise`() {
        // Dispatcharr channel
        assertEquals(CastMedia.TS, CastMedia.mimeType("http://dispatcharr:9191/proxy/ts/stream/0b6c-41f2", live = true))
        assertEquals(CastMedia.TS, CastMedia.mimeType("http://host:8080/1234", live = true))
        assertEquals(CastMedia.MP4, CastMedia.mimeType("http://host:8080/1234", live = false))
        // A dot in the host name is not an extension
        assertEquals(CastMedia.TS, CastMedia.mimeType("http://iptv.example.com", live = true))
    }

    @Test
    fun `HLS over plain HTTP is refused by the Chromecast, a file over HTTP is not`() {
        assertTrue(CastMedia.insecureAdaptive("http://149.62.177.107:9999/6ter/index.m3u8", live = true))
        assertTrue(CastMedia.insecureAdaptive("HTTP://host/manifest.mpd", live = true))
        assertFalse(CastMedia.insecureAdaptive("https://host/live/index.m3u8", live = true))
        assertFalse(CastMedia.insecureAdaptive("http://host/movie/u/p/7.mp4", live = false))
        assertFalse(CastMedia.insecureAdaptive("http://host/live/u/p/12.ts", live = true))
    }

    @Test
    fun `the HLS variants are tried first, the order is kept otherwise`() {
        val ts = "http://host/live/u/p/12.ts"
        val hls = "https://host/live/u/p/12.m3u8"
        val other = "http://backup/live/u/p/99.ts"
        assertEquals(listOf(hls, ts, other), CastMedia.castOrder(listOf(ts, hls, other), live = true))
        assertEquals(listOf(ts, other), CastMedia.castOrder(listOf(ts, other, ts), live = true))
        // HLS over plain HTTP: refused by the Chromecast, tried last
        val insecureHls = "http://host/live/u/p/12.m3u8"
        assertEquals(listOf(ts, insecureHls), CastMedia.castOrder(listOf(insecureHls, ts), live = true))
    }
}
