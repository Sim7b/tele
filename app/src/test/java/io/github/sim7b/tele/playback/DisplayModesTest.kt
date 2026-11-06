package io.github.sim7b.tele.playback

import io.github.sim7b.tele.playback.DisplayModes.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayModesTest {

    // A 4K TV as the SHIELD lists it, and its 1080p modes
    private val uhd60 = Mode(1, 3840, 2160, 60f)
    private val uhd5994 = Mode(2, 3840, 2160, 59.94f)
    private val uhd50 = Mode(3, 3840, 2160, 50f)
    private val uhd24 = Mode(4, 3840, 2160, 24f)
    private val uhd23976 = Mode(5, 3840, 2160, 23.976f)
    private val uhd25 = Mode(6, 3840, 2160, 25f)
    private val fhd50 = Mode(7, 1920, 1080, 50f)
    private val modes = listOf(uhd60, uhd5994, uhd50, uhd24, uhd23976, uhd25, fhd50)

    @Test
    fun `a European channel goes to 50 Hz, in the same resolution, not 25 Hz`() {
        assertEquals(uhd50, DisplayModes.pick(modes, uhd60, 25f))
        assertEquals(uhd50, DisplayModes.pick(modes, uhd60, 50f))
    }

    @Test
    fun `a movie goes to its exact rate`() {
        assertEquals(uhd23976, DisplayModes.pick(modes, uhd60, 23.976f))
        assertEquals(uhd24, DisplayModes.pick(modes, uhd60, 24f))
    }

    @Test
    fun `nothing changes when the screen already fits, or the rate is unknown`() {
        assertNull(DisplayModes.pick(modes, uhd60, 30f))
        assertNull(DisplayModes.pick(modes, uhd60, 60f))
        // 29.97 on 60 Hz: 0.1 % apart, kept
        assertNull(DisplayModes.pick(modes, uhd60, 29.97f))
        assertNull(DisplayModes.pick(modes, uhd50, 25f))
        assertNull(DisplayModes.pick(modes, uhd60, null))
        assertNull(DisplayModes.pick(modes, uhd60, Float.NaN))
        assertNull(DisplayModes.pick(modes, uhd60, 0f))
    }

    @Test
    fun `without a fitting mode the screen stays as it is`() {
        val only60 = listOf(uhd60, Mode(8, 1920, 1080, 60f))
        assertNull(DisplayModes.pick(only60, uhd60, 25f))
        // A 50 Hz mode in another resolution only
        assertNull(DisplayModes.pick(listOf(uhd60, fhd50), uhd60, 25f))
    }

    @Test
    fun `a 50 Hz screen goes back to 59,94 Hz for an American channel`() {
        assertEquals(uhd5994, DisplayModes.pick(modes, uhd50, 29.97f))
    }
}
