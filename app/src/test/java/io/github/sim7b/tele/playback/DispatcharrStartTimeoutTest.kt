package io.github.sim7b.tele.playback

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class DispatcharrStartTimeoutTest {

    // The read timeout each request gets, seen by the next interceptor (no network)
    private fun readTimeoutOf(url: String): Int {
        var seen = 0
        val client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(DispatcharrStartTimeout)
            .addInterceptor { chain ->
                seen = chain.readTimeoutMillis()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
        return seen
    }

    @Test
    fun `Dispatcharr channels are given time to fill their buffer`() {
        assertEquals(30_000, readTimeoutOf("https://tv.example/proxy/ts/stream/abc?skip_redirect=true"))
        assertEquals(10_000, readTimeoutOf("https://provider.example/live/u/p/1.ts"))
        assertEquals(10_000, readTimeoutOf("https://tv.example/proxy/vod/movie/abc"))
    }

    @Test
    fun `a Dispatcharr start is not taken for a frozen stream too soon`() {
        assertTrue(ReconnectPolicy.isDispatcharrLive("/proxy/ts/stream/abc"))
        assertFalse(ReconnectPolicy.isDispatcharrLive("/live/u/p/1.ts"))
        assertEquals(
            ReconnectPolicy.DISPATCHARR_START_STALL_MS,
            ReconnectPolicy.stallTimeoutMs(live = true, hasPlayed = false, dispatcharr = true)
        )
        assertTrue(ReconnectPolicy.DISPATCHARR_START_STALL_MS > ReconnectPolicy.DISPATCHARR_FIRST_BYTES_S * 1000)
        assertEquals(ReconnectPolicy.PLAYING_STALL_MS, ReconnectPolicy.stallTimeoutMs(live = true, hasPlayed = true, dispatcharr = true))
    }
}
