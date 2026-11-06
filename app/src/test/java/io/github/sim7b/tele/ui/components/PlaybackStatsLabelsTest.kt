package io.github.sim7b.tele.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatsLabelsTest {

    @Test
    fun `bitrates buffers and frame rates read simply`() {
        assertEquals("850 kb/s", bitrateLabel(850))
        assertEquals("8.4 Mb/s", bitrateLabel(8_400))
        assertEquals("12.5 s", bufferLabel(12_500))
        assertEquals("25 fps", fpsLabel(25f))
        assertEquals("29.97 fps", fpsLabel(29.97f))
    }
}
