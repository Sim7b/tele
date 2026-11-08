package io.github.sim7b.tele.data.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodQualityTest {

    private fun version(name: String, resolution: String? = null, codec: String? = null, hdr: Boolean = false, tags: List<String> = emptyList()) =
        VodVersion(sourceId = "s", sourceName = "Maison", url = "http://example.test/1", name = name, resolution = resolution, videoCodec = codec, hdr = hdr, qualityTags = tags)

    @Test
    fun `the resolution measured by the server comes first`() {
        assertEquals("4K HEVC HDR", version("Dune", "2160p", "HEVC", hdr = true).qualityLabel)
        assertEquals("1080p H.264", version("Dune", "1080p", "H.264").qualityLabel)
        assertEquals("SD", version("Dune", "576p").qualityLabel)
    }

    @Test
    fun `else the tags, else the file name tell it`() {
        assertEquals("1080p", version("Dune", tags = listOf("FHD")).qualityLabel)
        assertEquals("4K HEVC Dolby Vision", version("Dune.Part.Two.2024.2160p.DV.x265.mkv").qualityLabel)
        assertEquals("720p HDR", version("Shogun S01E01 720p HDR10").qualityLabel)
        // Filmed in a theater: said first
        assertEquals("CAM 1080p", version("Odyssey 2026 HDCAM 1080p").qualityLabel)
    }

    @Test
    fun `nothing known, no quality`() {
        assertNull(version("Dune").qualityLabel)
        // A word that only contains a tag does not count
        assertNull(version("Camping Paradis").qualityLabel)
    }
}
