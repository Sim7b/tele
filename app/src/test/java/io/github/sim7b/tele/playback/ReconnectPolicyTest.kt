package io.github.sim7b.tele.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `more attempts once the stream has played`() {
        assertEquals(3, ReconnectPolicy.maxAttempts(hasPlayed = false))
        assertEquals(6, ReconnectPolicy.maxAttempts(hasPlayed = true))
    }

    @Test
    fun `exponential backoff capped at 8 seconds`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), (0 until 6).map { ReconnectPolicy.delayMs(it) })
    }
}
