package io.github.sim7b.tele.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamNamesTest {

    @Test
    fun `cleans provider names`() {
        assertEquals("VIP: TF1 HD", StreamNames.clean("VIP: TF1 ᴴᴰ"))
        assertEquals("FR - TF1 FHD", StreamNames.clean("FR - TF1 FHD ◉"))
        assertEquals("TF1", StreamNames.clean("★ TF1 ★ -"))
    }

    @Test
    fun `infers quality tags`() {
        assertEquals(listOf("4K", "HEVC"), StreamNames.qualityTags("TF1 UHD H265"))
        assertEquals(listOf("FHD", "50 FPS"), StreamNames.qualityTags("TF1 1080p 50fps"))
        assertEquals(listOf("HD", "RAW"), StreamNames.qualityTags("TF1 ᴴᴰ ᴿᴬᵂ"))
        assertEquals(emptyList<String>(), StreamNames.qualityTags("TF1 HDR"))
    }
}
