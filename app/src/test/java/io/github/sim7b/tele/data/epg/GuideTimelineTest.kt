package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class GuideTimelineTest {

    private val minute = 60_000L

    private fun program(title: String, startMinute: Long, endMinute: Long) = GuideProgram(
        id = title,
        channelUuid = "c",
        title = title,
        startTimeIso = "",
        endTimeIso = "",
        startMillis = startMinute * minute,
        endMillis = endMinute * minute
    )

    // News 0-30, Movie 30-120, hole 120-150, Late 150-200
    private val programs = listOf(
        program("News", 0, 30),
        program("Movie", 30, 120),
        program("Late", 150, 200)
    )

    @Test
    fun `programs of a grouped channel come from one of its versions`() {
        val channel = Channel(id = "tf1", name = "TF1", group = "", streamUrl = "", memberIds = listOf("tf1-hd", "tf1-fhd"))
        val schedules = mapOf("tf1" to emptyList(), "tf1-fhd" to programs)
        assertEquals(programs, GuideTimeline.programsOf(channel, schedules))
        assertTrue(GuideTimeline.programsOf(channel.copy(memberIds = emptyList()), schedules).isEmpty())
    }

    @Test
    fun `from now starts with the program on air, else the next one`() {
        assertEquals(listOf("Movie", "Late"), GuideTimeline.fromNow(programs, 45 * minute).map { it.title })
        // In the hole: the next program only
        assertEquals(listOf("Late"), GuideTimeline.fromNow(programs, 130 * minute).map { it.title })
        assertTrue(GuideTimeline.fromNow(programs, 300 * minute).isEmpty())
    }

    @Test
    fun `the window keeps the programs overlapping it`() {
        assertEquals(listOf("News", "Movie"), GuideTimeline.inWindow(programs, 20 * minute, 60 * minute).map { it.title })
        assertEquals(listOf("Movie", "Late"), GuideTimeline.inWindow(programs, 100 * minute, 160 * minute).map { it.title })
        assertTrue(GuideTimeline.inWindow(programs, 125 * minute, 140 * minute).isEmpty())
        assertTrue(GuideTimeline.inWindow(emptyList(), 0, 60 * minute).isEmpty())
    }

    @Test
    fun `moves to the next and previous programs, over the holes`() {
        // From a program: its start
        assertEquals("Movie", GuideTimeline.nextAfter(programs, 0)?.title)
        assertEquals("Late", GuideTimeline.nextAfter(programs, 30 * minute)?.title)
        assertNull(GuideTimeline.nextAfter(programs, 150 * minute))
        assertEquals("Movie", GuideTimeline.previousBefore(programs, 150 * minute)?.title)
        assertEquals("News", GuideTimeline.previousBefore(programs, 30 * minute)?.title)
        assertNull(GuideTimeline.previousBefore(programs, 0))
        // From a hole
        assertEquals("Late", GuideTimeline.nextAfter(programs, 130 * minute)?.title)
        assertEquals("Movie", GuideTimeline.previousBefore(programs, 130 * minute)?.title)
    }

    @Test
    fun `a next program starting a little early is not skipped`() {
        val overlapping = listOf(program("A", 0, 31), program("B", 30, 60), program("C", 60, 90))
        assertEquals("B", GuideTimeline.nextAfter(overlapping, 0)?.title)
        assertEquals("C", GuideTimeline.nextAfter(overlapping, 30 * minute)?.title)
        assertEquals("A", GuideTimeline.programAt(overlapping, 30 * minute)?.title)
        assertEquals("B", GuideTimeline.startingAt(overlapping, 30 * minute)?.title)
        assertNull(GuideTimeline.startingAt(overlapping, 45 * minute))
    }

    @Test
    fun `slots are aligned on the local half hours`() {
        val utc = ZoneId.of("UTC")
        assertEquals(30 * minute, GuideTimeline.slotStart(59 * minute, utc))
        assertEquals(60 * minute, GuideTimeline.slotStart(60 * minute, utc))
        // Kathmandu is UTC+05:45 (since 1986): 2026-11-01 22:40 UTC is 04:25 there, its slot starts at 22:15 UTC
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        val evening = 1_793_572_200_000L
        assertEquals(evening - 15 * minute, GuideTimeline.slotStart(evening + 10 * minute, kathmandu))
    }

    @Test
    fun `minutes are rounded up and days counted in the local time zone`() {
        assertEquals(1, GuideTimeline.minutesBetween(0, 10_000))
        assertEquals(23, GuideTimeline.minutesBetween(0, 23 * minute))
        assertEquals(0, GuideTimeline.minutesBetween(10, 0))

        val paris = ZoneId.of("Europe/Paris")
        // 2026-11-01 22:30 and 23:30 UTC: 23:30 then 00:30 in Paris
        val evening = 1_793_572_200_000L
        assertEquals(0, GuideTimeline.dayOffset(evening, evening, paris))
        assertEquals(1, GuideTimeline.dayOffset(evening + 60 * minute, evening, paris))
        assertEquals(-1, GuideTimeline.dayOffset(evening - 24 * 60 * minute, evening, paris))
    }
}
