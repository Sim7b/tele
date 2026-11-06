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

    @Test
    fun `a Dispatcharr channel is offered to the Chromecast as fragmented MP4 and HLS before its raw MPEG-TS`() {
        val ts = "http://dispatcharr:9191/proxy/ts/stream/0b6c-41f2"
        assertEquals(listOf("$ts?output_format=fmp4", "$ts?output_format=m3u8", ts), CastMedia.castVariants(ts))
        assertEquals(CastMedia.MP4, CastMedia.mimeType("$ts?output_format=fmp4", live = true))
        assertEquals(CastMedia.HLS, CastMedia.mimeType("$ts?output_format=m3u8", live = true))
        // A format already asked, or another server: as it is
        assertEquals(listOf("$ts?output_format=m3u8"), CastMedia.castVariants("$ts?output_format=m3u8"))
        assertEquals(listOf("http://host/live/u/p/12.ts"), CastMedia.castVariants("http://host/live/u/p/12.ts"))
        // A query kept
        assertEquals("$ts?profile=2&output_format=fmp4", CastMedia.castVariants("$ts?profile=2").first())
    }

    @Test
    fun `over HTTP the fragmented MP4 comes first, over HTTPS the HLS`() {
        val http = "http://dispatcharr:9191/proxy/ts/stream/abc"
        assertEquals(
            listOf("$http?output_format=fmp4", http, "$http?output_format=m3u8"),
            CastMedia.castOrder(listOf(http), live = true)
        )
        val https = "https://dispatcharr.example.com/proxy/ts/stream/abc"
        assertEquals(
            listOf("$https?output_format=m3u8", "$https?output_format=fmp4", https),
            CastMedia.castOrder(listOf(https), live = true)
        )
    }

    @Test
    fun `a movie in MP4 is tried before the other files`() {
        val mkv = "http://host/movie/u/p/7.mkv"
        val mp4 = "http://host/movie/u/p/7.mp4"
        assertEquals(listOf(mp4, mkv), CastMedia.castOrder(listOf(mkv, mp4), live = false))
    }
}
