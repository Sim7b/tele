package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.model.SourceConfig
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DispatcharrClientTest {

    private val dispatcharr = MockWebServer()
    private val provider = MockWebServer()

    @Before
    fun setUp() {
        dispatcharr.start()
        provider.start()
    }

    @After
    fun tearDown() {
        dispatcharr.close()
        provider.close()
    }

    private fun config(apiKey: String) = SourceConfig(
        name = "D",
        serverUrl = dispatcharr.url("/").toString().replace("127.0.0.1", "localhost"),
        apiKey = apiKey
    )

    /** URL of the provider on another host name than the Dispatcharr server. */
    private fun providerUrl(path: String) = provider.url(path).newBuilder().host("127.0.0.1").build()

    @Test
    fun `sends the API key to the Dispatcharr host only, not to redirect targets`() = runBlocking {
        dispatcharr.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", providerUrl("/me").toString()).build()
        )
        provider.enqueue(MockResponse.Builder().body("""{"first_name":"Jean","username":"jean"}""").build())

        val user = DispatcharrClient(config("secret")).getCurrentUser()

        assertEquals("Jean" to "jean", user)
        assertEquals("secret", dispatcharr.takeRequest().headers["X-API-Key"])
        assertNull(provider.takeRequest().headers["X-API-Key"])
    }

    @Test
    fun `connection test sends the tested key, not to redirect targets`() = runBlocking {
        dispatcharr.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", providerUrl("/channels").toString()).build()
        )
        provider.enqueue(MockResponse.Builder().body("[]").build())

        val result = DispatcharrClient(config("active")).testConnection(config("tested"))

        assertEquals(0, result.getOrNull())
        assertEquals("tested", dispatcharr.takeRequest().headers["X-API-Key"])
        assertNull(provider.takeRequest().headers["X-API-Key"])
    }

    @Test
    fun `does not send the API key to another port of the same host`() = runBlocking {
        val otherPort = provider.url("/me").newBuilder().host("localhost").build()
        dispatcharr.enqueue(MockResponse.Builder().code(302).addHeader("Location", otherPort.toString()).build())
        provider.enqueue(MockResponse.Builder().body("{}").build())

        DispatcharrClient(config("secret")).getCurrentUser()

        assertEquals("secret", dispatcharr.takeRequest().headers["X-API-Key"])
        assertNull(provider.takeRequest().headers["X-API-Key"])
    }

    @Test
    fun `chosen streams are played through the Dispatcharr proxy, not from the provider`() = runBlocking {
        dispatcharr.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/m3u/accounts/" -> MockResponse.Builder().body("""[{"id": 1, "name": "Neo", "is_active": true}]""").build()
                else -> MockResponse.Builder().body(
                    """[{"id": 7, "name": "FR - TF1 FHD", "m3u_account": 1, "stream_hash": "abc123",
                        "url": "http://provider.example/live/user/pass/1.ts"}]"""
                ).build()
            }
        }

        val streams = DispatcharrClient(config("secret")).getChannelStreams("42").getOrThrow()

        val base = config("secret").normalizedUrl
        assertEquals("$base/proxy/ts/stream/abc123?skip_redirect=true", streams.single().url)
        assertEquals("Neo", streams.single().provider)
    }
}
