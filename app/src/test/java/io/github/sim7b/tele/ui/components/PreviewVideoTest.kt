package io.github.sim7b.tele.ui.components

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewVideoTest {

    @Test
    fun `a wide space is covered by a taller 16 by 9 picture, cropped at the top and bottom`() {
        assertEquals(DpSize(960.dp, 540.dp), coverSize(960.dp, 335.dp))
    }

    @Test
    fun `a narrow space is covered by a wider 16 by 9 picture, cropped at the sides`() {
        assertEquals(DpSize(320.dp, 180.dp), coverSize(160.dp, 180.dp))
    }

    @Test
    fun `a 16 by 9 space keeps its size`() {
        assertEquals(DpSize(320.dp, 180.dp), coverSize(320.dp, 180.dp))
    }
}
