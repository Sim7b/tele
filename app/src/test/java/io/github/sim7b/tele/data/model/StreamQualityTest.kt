package io.github.sim7b.tele.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {

    private fun stream(name: String, tags: List<String> = emptyList(), height: Int? = null) =
        StreamSource(name = name, url = "http://d/$name", qualityTags = tags, measuredHeight = height)

    @Test
    fun `measured resolution wins over the name`() {
        assertEquals(StreamQuality.HD, StreamQuality.of(stream("TF1 4K", listOf("4K"), height = 720)))
        assertEquals(StreamQuality.UHD, StreamQuality.of(stream("TF1 4K", listOf("4K"))))
        assertEquals(StreamQuality.UNKNOWN, StreamQuality.of(stream("TF1")))
    }

    @Test
    fun `parses Dispatcharr resolutions`() {
        assertEquals(1080, StreamQuality.parseHeight("1920x1080"))
        assertEquals(StreamQuality.FHD, StreamQuality.fromHeight(1080))
        assertEquals(StreamQuality.UHD, StreamQuality.fromHeight(2160))
        assertEquals(StreamQuality.SD, StreamQuality.fromHeight(576))
        assertNull(StreamQuality.parseHeight("unknown"))
        assertNull(StreamQuality.parseHeight(null))
    }

    private val streams = listOf(
        stream("sd", listOf("SD")),
        stream("hd", listOf("HD")),
        stream("uhd", listOf("4K")),
        stream("plain"),
        stream("fhd", height = 1080),
        stream("hd2", listOf("HD"))
    )

    private fun order(preference: QualityPreference) = preference.sort(streams).map { it.name }

    @Test
    fun `sorts by preference, keeping the server order within a quality`() {
        assertEquals(listOf("sd", "hd", "uhd", "plain", "fhd", "hd2"), order(QualityPreference.SERVER_ORDER))
        assertEquals(listOf("uhd", "fhd", "hd", "hd2", "plain", "sd"), order(QualityPreference.BEST))
        assertEquals(listOf("fhd", "hd", "hd2", "plain", "sd", "uhd"), order(QualityPreference.FHD_MAX))
        assertEquals(listOf("hd", "hd2", "sd", "plain", "fhd", "uhd"), order(QualityPreference.HD_MAX))
    }

    @Test
    fun `in server order the channel plays itself, unless its first stream failed lately`() {
        fun stream(name: String, failing: Boolean = false) = StreamSource(name = name, url = "u/$name", recentFailures = failing)
        val server = QualityPreference.SERVER_ORDER

        assertNull(server.automaticOrder(emptyList()))
        assertNull(server.automaticOrder(listOf(stream("a"), stream("b", failing = true))))
        // Dispatcharr would wait for its startup timeout on the first one again: the healthy ones first
        assertEquals(
            listOf("b", "c", "a"),
            server.automaticOrder(listOf(stream("a", failing = true), stream("b"), stream("c")))?.map { it.name }
        )
        // All failing: the server keeps its choice
        assertNull(server.automaticOrder(listOf(stream("a", failing = true), stream("b", failing = true))))
        // A quality preference always gives the order, the failing ones last
        assertEquals(
            listOf("b", "a"),
            QualityPreference.BEST.automaticOrder(listOf(stream("a", failing = true), stream("b")))?.map { it.name }
        )
    }

    @Test
    fun `two stalls in a short while lower the picture limit, for a while`() {
        var now = 0L
        val cap = QualityCap { now }
        fun stream(name: String, height: Int?) = StreamSource(name = name, url = "u/$name", measuredHeight = height)
        val streams = listOf(stream("uhd", 2160), stream("fhd", 1080), stream("hd", 720), stream("unknown", null))

        assertEquals(streams, cap.apply(streams))
        assertEquals(false, cap.onStall(StreamQuality.UHD))
        now += QualityCap.STALL_WINDOW_MS + 1
        // Too far apart: not a weak connection
        assertEquals(false, cap.onStall(StreamQuality.UHD))
        now += 10_000
        assertEquals(true, cap.onStall(StreamQuality.UHD))
        assertEquals(StreamQuality.FHD, cap.current())
        assertEquals(listOf("fhd", "hd", "unknown", "uhd"), cap.apply(streams).map { it.name })
        // Stalls again in Full HD: HD at most
        assertEquals(false, cap.onStall(StreamQuality.FHD))
        assertEquals(true, cap.onStall(StreamQuality.FHD))
        assertEquals(listOf("hd", "unknown", "uhd", "fhd"), cap.apply(streams).map { it.name })
        // An SD channel cannot go lower
        assertEquals(false, cap.onStall(StreamQuality.SD))
        assertEquals(false, cap.onStall(StreamQuality.SD))
        now += QualityCap.CAP_MS
        assertNull(cap.current())
        assertEquals(streams, cap.apply(streams))
    }

    @Test
    fun `a channel ordered on the server is played at once`() {
        assertTrue(QualityPreference.BEST.followsServerOrder(serverOrders = true, capped = false))
        assertTrue(QualityPreference.SERVER_ORDER.followsServerOrder(serverOrders = true, capped = false))
        // Another quality order, a weak connection, or a server that does not order the streams: they are read first
        assertFalse(QualityPreference.HD_MAX.followsServerOrder(serverOrders = true, capped = false))
        assertFalse(QualityPreference.BEST.followsServerOrder(serverOrders = true, capped = true))
        assertFalse(QualityPreference.BEST.followsServerOrder(serverOrders = false, capped = false))
    }
}
