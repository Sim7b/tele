package io.github.sim7b.tele.data.remote

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PosterFallbackTest {

    private val server = MockWebServer()
    private val paths = mutableListOf<String>()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun serve(poster: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(paths) { paths.add(request.url.encodedPath) }
                return when (request.url.encodedPath) {
                    "${TeleApi.VOD_POSTER_PATH}12/" -> poster
                    "/api/vod/vodlogos/12/cache/" -> MockResponse.Builder().addHeader("Content-Type", "image/png").body("png").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
    }

    private fun load(): String {
        val client = OkHttpClient.Builder().addInterceptor(PosterFallback).build()
        return client.newCall(Request.Builder().url(server.url("${TeleApi.VOD_POSTER_PATH}12/?w=342")).build()).execute().use { it.body.string() }
    }

    @Test
    fun `only light posters of the plugin have a fallback`() {
        assertEquals(
            "https://tv.example/api/vod/vodlogos/42/cache/",
            PosterFallback.fallbackOf("https://tv.example${TeleApi.VOD_POSTER_PATH}42/?w=342".toHttpUrl()).toString()
        )
        assertNull(PosterFallback.fallbackOf("https://tv.example/api/vod/vodlogos/42/cache/".toHttpUrl()))
        assertNull(PosterFallback.fallbackOf("https://tv.example${TeleApi.VOD_POSTER_PATH}../x/".toHttpUrl()))
    }

    @Test
    fun `a poster of the plugin is used when it comes`() {
        serve(MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body("jpeg").build())
        assertEquals("jpeg", load())
    }

    @Test
    fun `the image of Dispatcharr is loaded when the plugin fails or is gone`() {
        serve(MockResponse.Builder().code(500).build())
        assertEquals("png", load())
        serve(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html></html>").build())
        assertEquals("png", load())
    }
}
