package io.github.sim7b.tele.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferSizeTest {

    @Test
    fun `sizes grow and stay consistent`() {
        BufferSize.entries.forEach { size ->
            assertTrue(size.startMs <= size.afterStallMs)
            assertTrue(size.afterStallMs <= size.minMs)
            assertTrue(size.minMs <= size.maxMs)
        }
        BufferSize.entries.zipWithNext().forEach { (smaller, larger) -> assertTrue(smaller.maxMs < larger.maxMs) }
        // The large ones are capped in size too (a 4K stream must not fill the memory)
        assertTrue(!BufferSize.LARGE.timeOverSize && !BufferSize.MAXIMUM.timeOverSize)
    }

    @Test
    fun `the setting goes through them and is read back`() {
        assertEquals(BufferSize.LARGE, BufferSize.NORMAL.next())
        assertEquals(BufferSize.SMALL, BufferSize.MAXIMUM.next())
        assertEquals(BufferSize.MAXIMUM, BufferSize.of("MAXIMUM"))
        assertEquals(BufferSize.NORMAL, BufferSize.of("unknown"))
        assertEquals(BufferSize.NORMAL, BufferSize.of(null))
    }
}
