package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class XtreamLoadTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.url.queryParameter("action")) {
                    null -> """{"user_info":{"auth":1,"status":"Active","username":"user","allowed_output_formats":["ts"]}}"""
                    "get_live_categories" -> """[{"category_id":"1","category_name":"News"}]"""
                    "get_live_streams" ->
                        """[{"stream_id":20,"name":"B","category_id":"1","num":2},{"stream_id":10,"name":"A","category_id":"9","num":1},{"stream_id":20,"name":"B again"}]"""
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

    @Test
    fun `loads the live streams with their category, without duplicates`() = runBlocking {
        val config = SourceConfig(
            id = "12345678-abcd",
            name = "X",
            serverUrl = server.url("/").toString(),
            type = SourceType.XTREAM,
            username = "user",
            password = "secret"
        )

        val channels = XtreamClient.loadChannels(config).getOrThrow().channels

        assertEquals(listOf("xt-12345678-10", "xt-12345678-20"), channels.map { it.id })
        assertEquals(listOf("Other", "News"), channels.map { it.group })
    }
}
