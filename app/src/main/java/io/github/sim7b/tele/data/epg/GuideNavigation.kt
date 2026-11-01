package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.GuideProgram
import java.time.ZoneId

/**
 * Position in the guide grid: the focused [row], the [cursor] time kept when moving up and down
 * (the program of the new row is the one aired at that time), the start of the focused program
 * ([focusedStart], -1 in a hole of the guide) and the left edge of the visible hours ([viewStart]).
 */
data class GuidePosition(
    val row: Int,
    val cursor: Long,
    val focusedStart: Long,
    val viewStart: Long
)

/**
 * Remote moves in the guide grid. [span] is the visible duration, [minStart] the left edge of the
 * grid (the slot of now: past programs cannot be watched), [maxTime] the end of the guide.
 */
class GuideNavigation(
    private val span: Long,
    private val minStart: Long,
    private val maxTime: Long,
    private val zone: ZoneId
) {
    private val slot = GuideTimeline.SLOT_MS

    /** Program focused in [programs] (those of the focused row), null in a hole of the guide. */
    fun focused(programs: List<GuideProgram>, position: GuidePosition): GuideProgram? =
        position.focusedStart.takeIf { it >= 0 }?.let { GuideTimeline.startingAt(programs, it) }
            ?: GuideTimeline.programAt(programs, position.cursor)

    /** ▶: the next program, else (no program here) the next half hour. */
    fun right(programs: List<GuideProgram>, position: GuidePosition): GuidePosition {
        val focused = focused(programs, position)
        val next = GuideTimeline.nextAfter(programs, focused?.startMillis ?: position.cursor)
        if (next != null && next.startMillis < maxTime) return focusOn(next, position)
        if (focused != null) return position
        val time = GuideTimeline.slotStart(position.cursor, zone) + slot
        return if (time >= maxTime) position else focusOnTime(time, position)
    }

    /** ◀: the previous program still on air or to come, else (no program here) the previous half hour. */
    fun left(programs: List<GuideProgram>, position: GuidePosition): GuidePosition {
        val focused = focused(programs, position)
        val previous = GuideTimeline.previousBefore(programs, focused?.startMillis ?: position.cursor)
        if (previous != null && previous.endMillis > minStart) return focusOn(previous, position)
        if (focused != null) return position
        val slotStart = GuideTimeline.slotStart(position.cursor, zone)
        val time = if (slotStart < position.cursor) slotStart else position.cursor - slot
        return if (time < minStart) position else focusOnTime(time, position)
    }

    /** ▲ ▼: the program of [row] aired at the cursor time. */
    fun toRow(row: Int, programs: List<GuideProgram>, position: GuidePosition): GuidePosition =
        position.copy(row = row, focusedStart = GuideTimeline.programAt(programs, position.cursor)?.startMillis ?: -1)

    /** Back to the program on air now, its half hour at the left of the grid. */
    fun now(row: Int, programs: List<GuideProgram>, now: Long): GuidePosition =
        GuidePosition(row, now, GuideTimeline.programAt(programs, now)?.startMillis ?: -1, maxOf(minStart, GuideTimeline.slotStart(now, zone)))

    /** Touch: the program of [row] aired at [time] (a tap on the grid), the visible hours kept. */
    fun tapped(row: Int, programs: List<GuideProgram>, time: Long, position: GuidePosition): GuidePosition =
        position.copy(row = row, cursor = time, focusedStart = GuideTimeline.programAt(programs, time)?.startMillis ?: -1)

    /** Touch: the visible hours moved by [slots] half hours (a drag), within the start of the grid and the end of the guide. */
    fun scrolled(position: GuidePosition, slots: Int): GuidePosition {
        val lastStart = maxOf(minStart, GuideTimeline.slotStart(maxTime - span, zone))
        return position.copy(viewStart = (position.viewStart + slots * slot).coerceIn(minStart, lastStart))
    }

    /** Left edge of the visible hours so that [start, end) can be seen, scrolling by half hours. */
    fun reveal(viewStart: Long, start: Long, end: Long): Long = when {
        // Starts in the last visible half hour or later: it moves to the second column
        start >= viewStart + span - slot -> maxOf(minStart, GuideTimeline.slotStart(start, zone) - slot)
        // Almost entirely before the visible hours
        start < viewStart && end <= viewStart + slot -> maxOf(minStart, GuideTimeline.slotStart(start, zone))
        else -> maxOf(minStart, viewStart)
    }

    private fun focusOn(program: GuideProgram, position: GuidePosition): GuidePosition {
        val viewStart = reveal(position.viewStart, program.startMillis, program.endMillis)
        return position.copy(cursor = maxOf(program.startMillis, viewStart), focusedStart = program.startMillis, viewStart = viewStart)
    }

    private fun focusOnTime(time: Long, position: GuidePosition): GuidePosition {
        val viewStart = reveal(position.viewStart, time, time + slot)
        return position.copy(cursor = time, focusedStart = -1, viewStart = viewStart)
    }
}
