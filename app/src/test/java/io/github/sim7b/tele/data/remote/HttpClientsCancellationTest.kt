package io.github.sim7b.tele.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpClientsCancellationTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `reads the response when not cancelled`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("hello").build())
        val call = HttpClients.base.newCall(Request.Builder().url(server.url("/")).build())

        assertEquals("hello", call.executeCancellable { it.body.string() })
    }

    @Test
    fun `cancelling the coroutine stops a slow download`() = runBlocking {
        // 1 MB delivered 1 KB every 100 ms: about 100 s without cancellation
        server.enqueue(
            MockResponse.Builder().body("x".repeat(1024 * 1024)).throttleBody(1024, 100, TimeUnit.MILLISECONDS).build()
        )
        val call = HttpClients.base.newCall(Request.Builder().url(server.url("/")).build())

        val job = launch(Dispatchers.IO) {
            runCatching { call.executeCancellable { it.body.string() } }
        }
        delay(300)
        withTimeout(5_000) {
            job.cancel()
            job.join()
        }
        assertTrue(call.isCanceled())
    }
}
