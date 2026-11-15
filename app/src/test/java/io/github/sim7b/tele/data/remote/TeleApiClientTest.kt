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
                        {"id": 1, "uuid": "u1", "number": 1, "name": "TF1", "group": null, "logo_id": 9,
                         "now": {"title": "Journal", "start": "2026-11-09T20:00:00+00:00", "end": "2026-11-09T20:40:00+00:00"},
                         "next": {"title": "Film", "start": "not a date", "end": "2026-11-09T22:40:00+00:00"}}]}"""
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
        // The current program comes with the channel; a malformed one is left out
        val program = channels[0].programs.single()
        assertEquals("Journal", program.title)
        assertEquals(1_794_254_400_000L, program.startMillis)
        assertEquals(40 * 60_000L, program.endMillis - program.startMillis)
        assertTrue(channels[1].programs.isEmpty())
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
    fun `sessions left open by this TV are closed through the plugin`() = runBlocking {
        val bodies = mutableListOf<String>()
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json(info)
                TeleApi.RELEASE_PATH -> {
                    synchronized(bodies) { bodies.add(request.body?.utf8() ?: "") }
                    json("""{"api": 1, "released": ["live:abc:client_1"]}""")
                }
                else -> null
            }
        }
        DeviceUserAgent.installId = "0a1b2c3d4e5f"
        try {
            assertEquals(1, client().releaseThisDevice())
            assertEquals(true, JSONObject(bodies.single()).getBoolean("this_device"))

            // A custom User-Agent does not tell this TV from another one behind the same address
            val custom = DispatcharrClient(SourceConfig(name = "D", serverUrl = server.url("/").toString(), apiKey = "k", customUserAgent = "VLC"))
            assertEquals(0, custom.releaseThisDevice())
            assertEquals(1, bodies.size)
        } finally {
            DeviceUserAgent.installId = null
        }
    }

    @Test
    fun `measured VOD languages are read by category`() = runBlocking {
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json("""{"api": 1, "features": ["vod_languages"]}""")
                TeleApi.VOD_LANGUAGES_PATH -> json(
                    """{"api": 1, "categories": [
                        {"id": 3, "name": "FR | MULTI", "type": "movie", "audio": ["fr", "en"], "subtitles": []},
                        {"id": 4, "name": "NETFLIX", "type": "series", "audio": ["en", "<script>", 5], "subtitles": ["fr"]},
                        {"id": 5, "type": "movie", "audio": ["en"]}]}"""
                )
                else -> null
            }
        }

        val measured = client().getVodLanguages()

        assertEquals(setOf("movie:FR | MULTI", "series:NETFLIX"), measured.keys)
        assertEquals(setOf("fr", "en") to emptySet<String>(), measured["movie:FR | MULTI"])
        assertEquals(setOf("en") to setOf("fr"), measured["series:NETFLIX"])
    }

    @Test
    fun `a movie without description is measured on the server`() = runBlocking {
        var status = "queued"
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json("""{"api": 1, "features": ["vod_media"]}""")
                TeleApi.VOD_MEDIA_PATH -> {
                    assertEquals("movie", request.url.queryParameter("kind"))
                    if (status == "ok") {
                        json("""{"api": 1, "status": "ok", "info": {"video": {"codec_name": "hevc", "width": 3840, "height": 2160},
                            "audio": [{"codec_name": "eac3", "channels": 6, "tags": {"language": "fr"}}], "subtitles": []}}""")
                    } else {
                        json("""{"api": 1, "status": "$status"}""")
                    }
                }
                else -> null
            }
        }
        val client = client()

        assertEquals(DispatcharrClient.VodMedia(pending = true, info = null), client.getVodMedia("movie", "u"))
        status = "ok"
        val media = io.github.sim7b.tele.data.vod.ProviderMedia.from(client.getVodMedia("movie", "u")?.info)
        assertEquals("2160p", media.resolution)
        assertEquals("fr", media.audio.single().language)
        status = "failed"
        assertEquals(DispatcharrClient.VodMedia(pending = false, info = null), client.getVodMedia("movie", "u"))
    }

    @Test
    fun `streams that failed lately are marked`() = runBlocking {
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json("""{"api": 1, "features": ["stream_health"]}""")
                "/api/m3u/accounts/" -> json("""[{"id": 1, "name": "P", "is_active": true}]""")
                "/api/channels/channels/42/streams/" -> json(
                    """[{"id": 7, "name": "A", "m3u_account": 1, "stream_hash": "a"},
                        {"id": 8, "name": "B", "m3u_account": 1, "stream_hash": "b"}]"""
                )
                TeleApi.STREAM_HEALTH_PATH -> {
                    assertEquals("7,8", request.url.queryParameter("ids"))
                    json("""{"api": 1, "streams": {"7": {"score": 1.2, "unhealthy": true}, "8": {"score": 0, "unhealthy": false}}}""")
                }
                else -> null
            }
        }

        val streams = client().getChannelStreams("42").getOrThrow()

        assertEquals(listOf(true, false), streams.map { it.recentFailures })
    }

    @Test
    fun `the guide of the plugin is not sent again while unchanged`() = runBlocking {
        val ifNoneMatch = mutableListOf<String?>()
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json("""{"api": 1, "features": ["guide"]}""")
                TeleApi.GUIDE_PATH -> {
                    ifNoneMatch.add(request.headers["If-None-Match"])
                    if (request.headers["If-None-Match"] == "\"v1\"") {
                        MockResponse.Builder().code(304).build()
                    } else {
                        MockResponse.Builder().addHeader("ETag", "\"v1\"").addHeader("Content-Type", "application/json").body(
                            """{"api": 1, "results": [{"uuid": "u1", "programs": [
                                {"id": "5", "title": "Journal", "start": "2026-11-09T20:00:00+00:00", "end": "2026-11-09T20:40:00+00:00"}]}]}"""
                        ).build()
                    }
                }
                else -> null
            }
        }
        val client = client()

        val first = client.getEpgGrid().getOrThrow()
        val second = client.getEpgGrid().getOrThrow()

        assertEquals("u1", first.single().channelUuid)
        assertEquals("Journal", first.single().programs.single().title)
        assertEquals(first, second)
        assertEquals(listOf(null, "\"v1\""), ifNoneMatch)
        assertTrue("/api/epg/grid/" !in paths)
    }

    @Test
    fun `subtitles are searched and downloaded through the plugin`() = runBlocking {
        var searched: String? = null
        serve { request ->
            when (request.url.encodedPath) {
                TeleApi.INFO_PATH -> json("""{"plugin": "tele_api", "api": 1, "features": ["subtitles"]}""")
                TeleApi.SUBTITLES_SEARCH_PATH -> {
                    searched = request.url.query
                    json("""{"data": [{"attributes": {"language": "fr", "release": "Dune", "download_count": 3, "files": [{"file_id": 7}]}}]}""")
                }
                TeleApi.SUBTITLES_DOWNLOAD_PATH -> MockResponse.Builder().addHeader("X-Subtitle-Extension", "srt")
                    .addHeader("X-Subtitle-Remaining", "4").body("1\n00:00:01,000 --> 00:00:02,000\nSalut\n").build()
                else -> null
            }
        }
        val client = client()
        assertTrue(client.hasServerSubtitles())
        val query = io.github.sim7b.tele.data.subtitles.SubtitleQuery(title = "Dune", tmdbId = "438631")
        val answer = client.searchSubtitles(io.github.sim7b.tele.data.subtitles.OpenSubtitles.searchUrl(query, listOf("fr")))
        assertEquals("languages=fr&tmdb_id=438631", searched)
        assertEquals(7L, io.github.sim7b.tele.data.subtitles.OpenSubtitles.parseSearch(answer, listOf("fr")).single().fileId)
        val (bytes, extension, remaining) = client.downloadSubtitle(7)
        assertTrue(String(bytes).contains("Salut"))
        assertEquals("srt" to 4, extension to remaining)
    }

    @Test
    fun `parses the plugin answers defensively`() {
        assertNull(TeleApi.features(JSONObject("""{"api": 2, "features": ["channels"]}""")))
        assertNull(TeleApi.features(JSONObject("""{"api": 1}""")))
        assertEquals(setOf("slots"), TeleApi.features(JSONObject("""{"api": 1, "features": ["slots", ""]}""")))

        assertEquals("Maintenance tonight", TeleApi.message(JSONObject("""{"message": "  Maintenance tonight "}""")))
        assertNull(TeleApi.message(JSONObject("""{"message": null}""")))
        assertEquals(500, TeleApi.message(JSONObject(JSONObject().put("message", "x".repeat(900)).toString()))?.length)

        assertEquals(mapOf("5" to "438631"), TeleApi.tmdbIds(JSONObject("""{"ids": {"5": "438631", "x": "1", "6": "0", "7": "abc"}}""")))

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
