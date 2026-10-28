package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Movies and series of an Xtream account, as the panels describe them. */
class XtreamVodTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.queryParameter("action")) {
                    null -> """{"user_info":{"auth":1,"status":"Active","username":"user"}}"""
                    "get_vod_categories" -> """[{"category_id":"1","category_name":"FR | Films 4K"}]"""
                    "get_series_categories" -> """[{"category_id":"2","category_name":"Séries"}]"""
                    "get_vod_streams" ->
                        """[{"stream_id":7,"name":"FR | Dune: Part Two (2024) 4K MULTI","stream_icon":"http://img/dune.jpg","rating":"8.5","added":"1717000000","category_id":"1","container_extension":"mkv","tmdb":"693134"}]"""
                    "get_series" ->
                        """[{"series_id":3,"name":"Shōgun (2024)","cover":"http://img/shogun.jpg","plot":"Japon, 1600.","genre":"Drame, Histoire","releaseDate":"2024-02-27","rating":"8.6","category_id":"2"}]"""
                    "get_vod_info" ->
                        """{"info":{"tmdb_id":"693134","plot":"Paul Atréides…","cast":"Timothée Chalamet, Zendaya","director":"Denis Villeneuve","duration_secs":9960,
                           "video":{"codec_name":"hevc","width":3840,"height":1608,"color_transfer":"smpte2084"},
                           "audio":{"codec_name":"eac3","channels":6,"tags":{"language":"fre"}},"bitrate":18000},
                           "movie_data":{"stream_id":7,"container_extension":"mkv"}}"""
                    "get_series_info" ->
                        """{"info":{"plot":"Japon, 1600."},"episodes":{"1":[
                           {"id":"101","episode_num":1,"title":"Anjin","container_extension":"mp4","season":1,"info":{"duration_secs":4200,"video":{"codec_name":"h264","width":1920,"height":1080}}},
                           {"id":"105","episode_num":5,"title":"Shōgun S01E05","container_extension":"mkv","season":1,"info":{}}]}}"""
                    else -> "[]"
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config() = SourceConfig(
        id = "src",
        name = "Maison",
        serverUrl = server.url("/").toString(),
        type = SourceType.XTREAM,
        username = "user",
        password = "secret"
    )

    @Test
    fun `lists the movies and series with what the names and lists tell`() = runBlocking {
        val entries = XtreamVod.loadCatalog(config())
        val movie = entries.first { it.kind == VodKind.MOVIE }
        assertEquals("Dune: Part Two", movie.title)
        assertEquals(2024, movie.year)
        assertEquals("693134", movie.tmdbId)
        assertEquals("FR | Films 4K", movie.category)
        assertEquals(listOf("4K"), movie.qualityTags)
        // Built with the sheet, not kept in the catalog
        assertEquals(null, movie.streamUrl)

        val series = entries.first { it.kind == VodKind.SERIES }
        assertEquals("Shōgun", series.title)
        assertEquals(2024, series.year)
        assertEquals(listOf("Drame", "Histoire"), series.genres)
    }

    @Test
    fun `describes the file of a movie and the episodes of a series`() = runBlocking {
        val entries = XtreamVod.loadCatalog(config())
        val movie = XtreamVod.movieDetails(config(), entries.first { it.kind == VodKind.MOVIE })
        val file = movie.versions.single()
        assertTrue(file.url.endsWith("/movie/user/secret/7.mkv"))
        assertEquals("2160p", file.resolution)
        assertEquals("HEVC", file.videoCodec)
        assertTrue(file.hdr)
        assertEquals(listOf(VodAudio("fre", "E-AC3", 6)), file.audio)
        assertEquals(listOf("Timothée Chalamet", "Zendaya"), movie.cast)
        assertEquals("2160p HEVC HDR", file.qualityLabel)

        val series = XtreamVod.seriesDetails(config(), entries.first { it.kind == VodKind.SERIES })
        assertEquals(listOf("S1E1", "S1E5"), series.episodes.map { it.key })
        assertEquals("Anjin", series.episodes[0].title)
        // A title that only repeats the series name and number is left out
        assertEquals("", series.episodes[1].title)
        assertTrue(series.episodes[1].versions.single().url.endsWith("/series/user/secret/105.mkv"))
        assertEquals("1080p", series.episodes[0].versions.single().resolution)
    }
}
