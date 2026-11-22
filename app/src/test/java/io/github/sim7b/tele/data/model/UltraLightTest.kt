package io.github.sim7b.tele.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraLightTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `a device short of memory is a small one`() {
        // A 1 GB box, a box declared low on memory, a small app heap
        assertTrue(UltraLight.isLowEnd(gb, 192, lowRamDevice = false))
        assertTrue(UltraLight.isLowEnd(3 * gb, 256, lowRamDevice = true))
        assertTrue(UltraLight.isLowEnd(2 * gb, 128, lowRamDevice = false))
        // A 2 GB Google TV, and values the system could not give
        assertFalse(UltraLight.isLowEnd(2 * gb, 192, lowRamDevice = false))
        assertFalse(UltraLight.isLowEnd(0, 0, lowRamDevice = false))
    }

    @Test
    fun `automatic follows the device, the other choices win`() {
        assertTrue(UltraLight.isActive(UltraLightMode.AUTO, lowEnd = true))
        assertFalse(UltraLight.isActive(UltraLightMode.AUTO, lowEnd = false))
        assertTrue(UltraLight.isActive(UltraLightMode.ON, lowEnd = false))
        assertFalse(UltraLight.isActive(UltraLightMode.OFF, lowEnd = true))
        assertEquals(listOf(UltraLightMode.ON, UltraLightMode.OFF, UltraLightMode.AUTO), UltraLightMode.entries.map { it.next() })
        assertEquals(UltraLightMode.AUTO, UltraLightMode.of("broken"))
        assertEquals(UltraLightMode.OFF, UltraLightMode.of("OFF"))
    }
}
