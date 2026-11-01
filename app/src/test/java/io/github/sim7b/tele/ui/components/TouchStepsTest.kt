package io.github.sim7b.tele.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchStepsTest {

    @Test
    fun `a tap falls in the left, middle or right third`() {
        assertEquals(TouchSteps.Side.LEFT, TouchSteps.side(x = 10f, width = 900f))
        assertEquals(TouchSteps.Side.CENTER, TouchSteps.side(x = 450f, width = 900f))
        assertEquals(TouchSteps.Side.RIGHT, TouchSteps.side(x = 600f, width = 900f))
        // Not laid out yet: the middle
        assertEquals(TouchSteps.Side.CENTER, TouchSteps.side(x = 10f, width = 0f))
    }

    @Test
    fun `a swipe up goes to the next channel, down to the previous one, a short one does nothing`() {
        assertEquals(1, TouchSteps.swipe(dy = -200f, threshold = 150f))
        assertEquals(-1, TouchSteps.swipe(dy = 160f, threshold = 150f))
        assertEquals(0, TouchSteps.swipe(dy = -100f, threshold = 150f))
        assertEquals(0, TouchSteps.swipe(dy = -500f, threshold = 0f))
    }

    @Test
    fun `a drag to the left moves forward by whole steps and keeps the rest`() {
        // 2.5 steps to the left: 2 steps forward, half a step left over
        val steps = TouchSteps.steps(dx = -250f, stepPx = 100f)
        assertEquals(2, steps)
        assertEquals(-50f, TouchSteps.remainder(dx = -250f, steps = steps, stepPx = 100f), 0.001f)
        // To the right: back in time
        assertEquals(-1, TouchSteps.steps(dx = 130f, stepPx = 100f))
        assertEquals(30f, TouchSteps.remainder(dx = 130f, steps = -1, stepPx = 100f), 0.001f)
        // Less than a step: nothing yet
        assertEquals(0, TouchSteps.steps(dx = -60f, stepPx = 100f))
    }

    @Test
    fun `the progress bar gives the position under the finger, within the duration`() {
        assertEquals(30_000L, TouchSteps.seekPosition(x = 250f, width = 1000f, durationMs = 120_000L))
        assertEquals(0L, TouchSteps.seekPosition(x = -40f, width = 1000f, durationMs = 120_000L))
        assertEquals(120_000L, TouchSteps.seekPosition(x = 1500f, width = 1000f, durationMs = 120_000L))
        assertEquals(0L, TouchSteps.seekPosition(x = 250f, width = 0f, durationMs = 120_000L))
    }

    @Test
    fun `only a touch device gets the narrow layouts`() {
        // A TV keeps its layouts whatever its size
        assertFalse(ScreenSize(touch = false, width = 400.dp, height = 300.dp).compact)
        assertFalse(ScreenSize(touch = false, width = 400.dp, height = 800.dp).portrait)
        // Phone in portrait: narrow and tall
        val phone = ScreenSize(touch = true, width = 411.dp, height = 891.dp)
        assertTrue(phone.compact)
        assertTrue(phone.portrait)
        assertFalse(phone.short)
        // Phone in landscape: wide enough for the TV layouts, but low
        val landscape = ScreenSize(touch = true, width = 891.dp, height = 411.dp)
        assertFalse(landscape.compact)
        assertTrue(landscape.short)
        // Tablet: the TV layouts
        val tablet = ScreenSize(touch = true, width = 1280.dp, height = 800.dp)
        assertFalse(tablet.compact)
        assertFalse(tablet.short)
    }
}
