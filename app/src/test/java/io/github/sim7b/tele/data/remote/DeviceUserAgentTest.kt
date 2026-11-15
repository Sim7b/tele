package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceUserAgentTest {

    private val id = "0a1b2c3d4e5f"
    private val withId = "TV-AndroidTV/1.0 (Android TV; Tele-$id)"

    @Before
    fun setUp() {
        DeviceUserAgent.installId = id
    }

    @After
    fun tearDown() {
        DeviceUserAgent.installId = null
    }

    @Test
    fun `adds the install id to the default user agent`() {
        assertEquals(withId, DeviceUserAgent.dispatcharr)
        assertEquals(HttpClients.DEFAULT_USER_AGENT, DeviceUserAgent.withId(HttpClients.DEFAULT_USER_AGENT, null))
        assertEquals(HttpClients.DEFAULT_USER_AGENT, DeviceUserAgent.withId(HttpClients.DEFAULT_USER_AGENT, "not-hex"))
        assertEquals("Custom", DeviceUserAgent.forDispatcharr("Custom"))
        assertEquals(withId, DeviceUserAgent.forDispatcharr(" "))
    }

    @Test
    fun `new ids are 12 hex digits`() {
        val ids = (1..20).map { DeviceUserAgent.newId() }.toSet()
        assertTrue(ids.all { Regex("^[0-9a-f]{12}$").matches(it) })
        assertEquals(20, ids.size)
    }

    @Test
    fun `strips the install id`() {
        assertEquals(HttpClients.DEFAULT_USER_AGENT, DeviceUserAgent.strip(withId))
        assertEquals("Custom", DeviceUserAgent.strip("Custom"))
        val request = Request.Builder().url("http://provider.example/").header("User-Agent", withId).build()
        assertEquals(HttpClients.DEFAULT_USER_AGENT, DeviceUserAgent.withoutDeviceId(request).header("User-Agent"))
    }

    @Test
    fun `only Dispatcharr streams carry the install id`() {
        assertEquals(withId, SourceConfig(name = "D", serverUrl = "d.example").playerUserAgent)
        assertNull(SourceConfig(name = "X", serverUrl = "x.example", type = SourceType.XTREAM).playerUserAgent)
    }

    @Test
    fun `the install id goes to the Dispatcharr server, never to the host it redirects to`() = runBlocking {
        MockWebServer().use { dispatcharr ->
            MockWebServer().use { provider ->
                dispatcharr.start()
                provider.start()
                val target = provider.url("/me").newBuilder().host("127.0.0.1").build()
                dispatcharr.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())
                provider.enqueue(MockResponse.Builder().body("{}").build())
                val config = SourceConfig(name = "D", serverUrl = dispatcharr.url("/").toString().replace("127.0.0.1", "localhost"), apiKey = "k")

                DispatcharrClient(config).getCurrentUser()

                assertEquals(withId, dispatcharr.takeRequest().headers["User-Agent"])
                assertEquals(HttpClients.DEFAULT_USER_AGENT, provider.takeRequest().headers["User-Agent"])
            }
        }
    }
}
