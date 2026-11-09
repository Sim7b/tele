package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.StreamSlots
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Use of the Télé API plugin by [DispatcharrClient], and the fallback on the standard API (Robolectric: logs). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TeleApiClientTest {

    private val server = MockWebServer()
    private val paths = mutableListOf<String>()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = DispatcharrClient(SourceConfig(name = "D", serverUrl = server.url("/").toString(), apiKey = "k"))

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private val info = """{"plugin": "tele_api", "api": 1, "features": ["channels", "slots", "sessions"]}"""

    private fun serve(routes: (RecordedRequest) -> MockResponse?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(paths) { paths.add(request.url.encodedPath) }
                return routes(request) ?: MockResponse.Builder().code(404).build()
            }
        }
    }

    private val standardChannels: (RecordedRequest) -> MockResponse? = { request ->
        when (request.url.encodedPath) {
            "/api/channels/channels/" -> json("""[{"id": 1, "uuid": "u1", "name": "TF1", "channel_number": 1, "channel_group_id": 5}]""")
            "/api/channels/groups/" -> json("""[{"id": 5, "name": "TNT"}]""")
            "/api/epg/epgdata/" -> json("[]")
            else -> null
        }
    }

    @Test
    fun `channels come from the plugin in one list when it is installed`() = runBlocking {
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json(info)
                TeleApi.CHANNELS_PATH -> json(
                    """{"api": 1, "count": 2, "results": [
                        {"id": 2, "uuid": "u2", "number": 2, "name": "France 2", "group": {"id": 5, "name": "TNT"}, "tvg_id": "F2.fr"},
                        {"id": 1, "uuid": "u1", "number": 1, "name": "TF1", "group": null, "logo_id": 9}]}"""
                )
                else -> null
            }
        }

        val channels = client().getChannels().getOrThrow()

        assertEquals(listOf("TF1", "France 2"), channels.map { it.name })
        assertEquals(Channel.NO_GROUP, channels[0].group)
        assertEquals("TNT", channels[1].group)
        assertEquals("F2.fr", channels[1].tvgId)
        assertTrue(channels[0].logoUrl!!.endsWith("/api/channels/logos/9/cache/"))
        assertTrue(channels[0].streamUrl.endsWith("/proxy/ts/stream/u1"))
        assertTrue("/api/channels/channels/" !in paths)
    }

    @Test
    fun `channels of the plugin are read page after page`() = runBlocking {
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json(info)
                TeleApi.CHANNELS_PATH -> {
                    val page = request.url.queryParameter("page")!!.toInt()
                    val size = if (page == 1) TeleApi.CHANNELS_PAGE_SIZE else 1
                    val items = (0 until size).joinToString(",") { """{"uuid": "p$page-$it", "name": "C"}""" }
                    json("""{"results": [$items]}""")
                }
                else -> null
            }
        }

        assertEquals(TeleApi.CHANNELS_PAGE_SIZE + 1, client().getChannels().getOrThrow().size)
        assertEquals(2, paths.count { it == TeleApi.CHANNELS_PATH })
    }

    @Test
    fun `without the plugin the standard API is used`() = runBlocking {
        serve { request ->
            // The web interface of Dispatcharr answers unknown paths with its HTML page
            if (request.url.encodedPath == TeleApi.INFO_PATH) MockResponse.Builder().body("<!doctype html><html></html>").build()
            else standardChannels(request)
        }

        val client = client()
        assertEquals("TNT", client.getChannels().getOrThrow().single().group)
        client.getChannels()

        // Probed once, then remembered
        assertEquals(1, paths.count { it == TeleApi.INFO_PATH })
    }

    @Test
    fun `a failing plugin page falls back on the standard API`() = runBlocking {
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json(info)
                TeleApi.CHANNELS_PATH -> MockResponse.Builder().code(500).build()
                else -> standardChannels(request)
            }
        }

        assertEquals("TF1", client().getChannels().getOrThrow().single().name)
    }

    @Test
    fun `free connections come from the plugin, without the admin stats`() = runBlocking {
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json(info)
                TeleApi.SLOTS_PATH -> json("""{"api": 1, "total": 4, "used": 3, "free": 1, "limited": true}""")
                else -> null
            }
        }

        assertEquals(StreamSlots(used = 3, total = 4), client().getStreamSlots())
        assertTrue("/proxy/stats/" !in paths)
    }

    @Test
    fun `parses the plugin answers defensively`() {
        assertNull(TeleApi.features(JSONObject("""{"api": 2, "features": ["channels"]}""")))
        assertNull(TeleApi.features(JSONObject("""{"api": 1}""")))
        assertEquals(setOf("slots"), TeleApi.features(JSONObject("""{"api": 1, "features": ["slots", ""]}""")))

        assertNull(TeleApi.parseSlots(JSONObject("""{"limited": false, "total": 0}""")))
        assertNull(TeleApi.parseSlots(JSONObject("""{"limited": true, "total": -2}""")))
        assertEquals(StreamSlots(used = 2, total = 2), TeleApi.parseSlots(JSONObject("""{"limited": true, "total": 2, "used": 9}""")))

        assertNull(TeleApi.parseChannel(JSONObject("""{"name": "no uuid"}"""), "http://d"))
        val channel = TeleApi.parseChannel(JSONObject("""{"uuid": "abcdef123", "number": 0, "logo_id": 0}"""), "http://d")!!
        assertEquals("#abcdef12", channel.name)
        assertNull(channel.channelNumber)
        assertNull(channel.logoUrl)
        assertEquals("abcdef123", channel.id)
    }
}
