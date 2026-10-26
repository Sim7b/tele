package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.model.SourceConfig
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DispatcharrCacheTest {

    private val server = MockWebServer()

    @Volatile
    private var groupName = "News"

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.encodedPath) {
                    "/api/channels/groups/" -> """[{"id":1,"name":"$groupName"}]"""
                    "/api/channels/channels/" ->
                        """[{"id":1,"uuid":"u1","name":"A","channel_group_id":1},{"id":2,"uuid":"u1","name":"A bis"}]"""
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

    private fun client() = DispatcharrClient(SourceConfig(name = "D", serverUrl = server.url("/").toString(), apiKey = "k"))

    @Test
    fun `groups stay cached until the caches are cleared`() = runBlocking {
        val client = client()
        assertEquals("News", client.getChannels().getOrThrow().first().group)

        groupName = "Sports"
        assertEquals("News", client.getChannels().getOrThrow().first().group)

        client.clearCaches()
        assertEquals("Sports", client.getChannels().getOrThrow().first().group)
    }

    @Test
    fun `skips channels with an already seen uuid`() = runBlocking {
        assertEquals(listOf("A"), client().getChannels().getOrThrow().map { it.name })
    }
}
