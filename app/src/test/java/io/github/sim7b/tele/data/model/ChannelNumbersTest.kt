package io.github.sim7b.tele.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelNumbersTest {

    private fun channel(id: String, number: Int? = null) = Channel(id = id, name = id, group = "", streamUrl = "http://x/$id", number = number)

    @Test
    fun `uses the numbers of the source`() {
        val channels = listOf(channel("tf1", 1), channel("m6", 6), channel("arte", 7))
        assertEquals("m6", ChannelNumbers.find(channels, 6)?.id)
        assertNull(ChannelNumbers.find(channels, 2))
        assertNull(ChannelNumbers.find(channels, 0))
    }

    @Test
    fun `falls back to the position when no channel is numbered`() {
        val channels = listOf(channel("a"), channel("b"), channel("c"))
        assertEquals("b", ChannelNumbers.find(channels, 2)?.id)
        assertNull(ChannelNumbers.find(channels, 4))
    }

    @Test
    fun `appends digits up to the limit`() {
        assertEquals("12", ChannelNumbers.append("1", 2))
        assertEquals("5", ChannelNumbers.append("1234", 5))
    }
}
