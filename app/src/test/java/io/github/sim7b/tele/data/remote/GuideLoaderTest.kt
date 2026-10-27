package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.storage.GuideCache
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** XMLTV guide only downloaded again when it changed, and kept on disk between two launches. */
// Robolectric: the XMLTV parser is the Android XmlPullParser
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class GuideLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private var etag = "\"v1\""
    private var title = "Journal"
    private var fullDownloads = 0
    private val now = System.currentTimeMillis()

    private fun xmltvTime(millis: Long) =
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.headers["If-None-Match"] == etag) return MockResponse.Builder().code(304).build()
                fullDownloads++
                val body = """
                    <tv>
                      <channel id="tf1.fr"><display-name>TF1</display-name></channel>
                      <programme channel="tf1.fr" start="${xmltvTime(now - 600_000)}" stop="${xmltvTime(now + 600_000)}">
                        <title>$title</title>
                      </programme>
                    </tv>
                """.trimIndent()
                return MockResponse.Builder().addHeader("ETag", etag).body(body).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val channels = listOf(Channel(id = "c1", name = "TF1", group = "TNT", streamUrl = "http://x/1", tvgId = "tf1.fr"))
    private fun urls() = listOf(server.url("/guide.xml").toString())

    @Test
    fun `an unchanged guide is not downloaded again`() = runBlocking {
        val first = GuideLoader.load(urls(), channels, "ua", now = now)
        assertEquals("Journal", first.programs["c1"]?.single()?.title)
        assertFalse(first.unchanged)

        // Stored on disk, read back at the next launch
        val cache = GuideCache(folder.newFile("guide.jsonl"))
        cache.save(assertNotNullAndGet(first.cache))
        val previous = cache.load(keepFrom = now - GuideLoader.PAST_WINDOW_MS)

        val second = GuideLoader.load(urls(), channels, "ua", previous, now = now + 60_000)
        assertTrue(second.unchanged)
        assertEquals("Journal", second.programs["c1"]?.single()?.title)
        assertEquals(1, fullDownloads)

        // Changed on the server: downloaded again
        etag = "\"v2\""
        title = "Météo"
        val third = GuideLoader.load(urls(), channels, "ua", second.cache, now = now + 120_000)
        assertFalse(third.unchanged)
        assertEquals("Météo", third.programs["c1"]?.single()?.title)
        assertEquals(2, fullDownloads)
    }

    @Test
    fun `other channels or an old guide mean a full download`() = runBlocking {
        val first = GuideLoader.load(urls(), channels, "ua", now = now)
        val renamed = channels.map { it.copy(name = "TF1 HD") }
        assertFalse(GuideLoader.load(urls(), renamed, "ua", first.cache, now = now).unchanged)
        assertFalse(GuideLoader.load(urls(), channels, "ua", first.cache, now = now + 13 * 3_600_000L).unchanged)
        assertEquals(3, fullDownloads)
    }

    @Test
    fun `the cache does not keep the guide URLs in clear`() = runBlocking {
        val url = server.url("/guide.xml?username=me&password=secret").toString()
        val guide = GuideLoader.load(listOf(url), channels, "ua", now = now)
        val file = folder.newFile("guide.jsonl")
        GuideCache(file).save(assertNotNullAndGet(guide.cache))
        assertFalse(file.readText().contains("secret"))
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
