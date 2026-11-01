package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.GuideProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class GuideNavigationTest {

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

    // Now is minute 40: the grid starts at 30 and shows 2 h 30
    private val now = 40 * minute
    private val navigation = GuideNavigation(span = 150 * minute, minStart = 30 * minute, maxTime = 600 * minute, zone = ZoneId.of("UTC"))

    // Early 0-35 (on air until 35), Show 35-60, Film 60-200, hole, Night 260-400
    private val programs = listOf(
        program("Early", 0, 35),
        program("Show", 35, 60),
        program("Film", 60, 200),
        program("Night", 260, 400)
    )
    private val empty = emptyList<GuideProgram>()

    private fun title(position: GuidePosition) = navigation.focused(programs, position)?.title

    @Test
    fun `right goes from program to program and scrolls by half hours`() {
        val start = navigation.now(row = 0, programs = programs, now = now)
        assertEquals("Show", title(start))
        assertEquals(30 * minute, start.viewStart)

        val film = navigation.right(programs, start)
        assertEquals("Film", title(film))
        assertEquals(30 * minute, film.viewStart)

        // Night starts after the visible hours (30-180): the grid moves so it is in the second column
        val night = navigation.right(programs, film)
        assertEquals("Night", title(night))
        assertEquals(210 * minute, night.viewStart)
        assertEquals(260 * minute, night.cursor)

        // End of the guide: stays
        assertEquals(night, navigation.right(programs, night))
    }

    @Test
    fun `left goes back but never before the programs still on air`() {
        val film = navigation.right(programs, navigation.now(0, programs, now))
        val show = navigation.left(programs, film)
        assertEquals("Show", title(show))
        // Early ended at 35, before now... but after the start of the grid (30): still reachable
        val early = navigation.left(programs, show)
        assertEquals("Early", title(early))
        assertEquals(30 * minute, early.cursor)
        assertEquals(early, navigation.left(programs, early))
    }

    @Test
    fun `moving up and down keeps the time`() {
        val other = listOf(program("News", 30, 90), program("Talk", 90, 150))
        val film = navigation.right(programs, navigation.now(0, programs, now))
        val down = navigation.toRow(1, other, film)
        assertEquals(1, down.row)
        assertEquals("News", navigation.focused(other, down)?.title)
        // The cursor stays at the start of the film: back up, the film again
        assertEquals("Film", title(navigation.toRow(0, programs, down)))
    }

    @Test
    fun `a row without guide moves by half hours`() {
        val start = navigation.now(0, empty, now)
        assertNull(navigation.focused(empty, start))
        val next = navigation.right(empty, start)
        assertEquals(60 * minute, next.cursor)
        assertEquals(-1L, next.focusedStart)
        val back = navigation.left(empty, next)
        assertEquals(30 * minute, back.cursor)
        // Not before the start of the grid
        assertEquals(back, navigation.left(empty, back))
    }

    @Test
    fun `holes of the guide are jumped over`() {
        val hole = GuidePosition(row = 0, cursor = 220 * minute, focusedStart = -1, viewStart = 150 * minute)
        assertNull(title(hole))
        assertEquals("Night", title(navigation.right(programs, hole)))
        assertEquals("Film", title(navigation.left(programs, hole)))
    }

    @Test
    fun `reveal keeps a visible program in place`() {
        assertEquals(30 * minute, navigation.reveal(30 * minute, 60 * minute, 200 * minute))
        assertEquals(30 * minute, navigation.reveal(120 * minute, 35 * minute, 60 * minute))
        assertEquals(120 * minute, navigation.reveal(120 * minute, 60 * minute, 200 * minute))
    }

    @Test
    fun `with replay the grid goes back in the past, back to now returns to the half hour of now`() {
        // Grid open from minute 0 (replay), now is minute 100
        val replayNavigation = GuideNavigation(span = 150 * minute, minStart = 0, maxTime = 600 * minute, zone = ZoneId.of("UTC"))
        val start = replayNavigation.now(row = 0, programs = programs, now = 100 * minute)
        assertEquals("Film", replayNavigation.focused(programs, start)?.title)
        assertEquals(90 * minute, start.viewStart)
        // Back over the past programs, the grid scrolls left
        val show = replayNavigation.left(programs, start)
        assertEquals("Show", replayNavigation.focused(programs, show)?.title)
        assertEquals(30 * minute, show.viewStart)
        assertEquals("Early", replayNavigation.focused(programs, replayNavigation.left(programs, show))?.title)
    }

    @Test
    fun `a tap focuses the program aired at the tapped time, the hours stay`() {
        val start = navigation.now(row = 0, programs = programs, now = now)
        val film = navigation.tapped(row = 3, programs = programs, time = 120 * minute, position = start)
        assertEquals(3, film.row)
        assertEquals("Film", title(film))
        assertEquals(start.viewStart, film.viewStart)
        // In a hole: no program, the cursor on the tapped time
        val hole = navigation.tapped(row = 3, programs = programs, time = 220 * minute, position = start)
        assertNull(title(hole))
        assertEquals(-1L, hole.focusedStart)
        assertEquals(220 * minute, hole.cursor)
    }

    @Test
    fun `a drag moves the hours by half hours within the grid`() {
        val start = navigation.now(row = 0, programs = programs, now = now)
        val later = navigation.scrolled(start, slots = 2)
        assertEquals(90 * minute, later.viewStart)
        // The focus does not move with the hours
        assertEquals(start.focusedStart, later.focusedStart)
        assertEquals(start.cursor, later.cursor)
        // Never before the start of the grid, never past the end of the guide (600 - 150 = 450)
        assertEquals(30 * minute, navigation.scrolled(later, slots = -10).viewStart)
        assertEquals(450 * minute, navigation.scrolled(start, slots = 100).viewStart)
    }
}
