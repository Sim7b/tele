package io.github.sim7b.tele.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
