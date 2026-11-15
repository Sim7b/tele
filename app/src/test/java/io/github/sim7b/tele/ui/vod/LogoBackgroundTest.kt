package io.github.sim7b.tele.ui.vod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoBackgroundTest {

    @Test
    fun `a logo on a filled box is opaque`() {
        assertTrue(LogoBackground.isOpaque(300, 100) { _, _ -> 255 })
    }

    @Test
    fun `a wordmark on a transparent background is not`() {
        // Opaque letters in the middle, touching the left and right edges halfway up
        assertFalse(LogoBackground.isOpaque(300, 100) { x, y -> if (y in 30..70 || (x in 20..280 && y in 10..90)) 255 else 0 })
        assertFalse(LogoBackground.isOpaque(300, 100) { _, _ -> 0 })
    }

    @Test
    fun `an empty image is not opaque`() {
        assertFalse(LogoBackground.isOpaque(0, 0) { _, _ -> 255 })
        assertTrue(LogoBackground.isOpaque(1, 1) { _, _ -> 255 })
    }
}
