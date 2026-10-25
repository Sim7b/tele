package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.GuideProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgResolverTest {

    private fun program(id: String, start: Long, end: Long) = GuideProgram(
        id = id,
        channelUuid = "channel",
        title = id,
        startTimeIso = "",
        endTimeIso = "",
        startMillis = start,
        endMillis = end
    )

    @Test
    fun `normalizes names for matching`() {
        assertEquals("france2", EpgResolver.normalizeName("France 2"))
        assertEquals("france2", EpgResolver.normalizeName("France2.fr"))
        assertEquals("france2", EpgResolver.normalizeName("FRANCE 2"))
        assertEquals("canal+", EpgResolver.normalizeName("Canal+"))
        assertEquals("telematin", EpgResolver.normalizeName("Télématin"))
    }

    @Test
    fun `parses ISO timestamps with Z or offset`() {
        assertEquals(1_000L, EpgResolver.parseIsoToMillis("1970-01-01T00:00:01Z"))
        assertEquals(1_000L, EpgResolver.parseIsoToMillis("1970-01-01T01:00:01+01:00"))
        assertEquals(0L, EpgResolver.parseIsoToMillis("not a date"))
        assertEquals(0L, EpgResolver.parseIsoToMillis(null))
    }

    @Test
    fun `computes progress clamped between 0 and 1`() {
        assertEquals(0.5f, EpgResolver.progress(1_000, 3_000, 2_000), 0.0001f)
        assertEquals(0f, EpgResolver.progress(1_000, 3_000, 0), 0.0001f)
        assertEquals(1f, EpgResolver.progress(1_000, 3_000, 5_000), 0.0001f)
        assertEquals(0f, EpgResolver.progress(3_000, 1_000, 2_000), 0.0001f)
    }

    @Test
    fun `finds the current program and the next one`() {
        val programs = listOf(
            program("a", 0, 60_000),
            program("b", 60_000, 120_000),
            program("c", 120_000, 180_000)
        )

        val current = EpgResolver.currentProgram(programs, 90_000)!!
        assertEquals("b", current.title)
        assertEquals("c", current.nextTitle)
        assertEquals(0.5f, current.progress, 0.0001f)
        assertNull(EpgResolver.currentProgram(programs, 200_000))
    }

    @Test
    fun `drops a cached program once it has ended`() {
        val current = EpgResolver.currentProgram(listOf(program("a", 60_000, 120_000)), 90_000)!!

        assertEquals(0.75f, EpgResolver.refreshCached(current, 105_000)!!.progress, 0.0001f)
        assertNull(EpgResolver.refreshCached(current, 120_000))
    }
}
