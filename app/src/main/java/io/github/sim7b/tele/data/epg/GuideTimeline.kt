package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Programs of a channel over time, for the coming programs of the channel panel and the guide grid.
 * Every list of programs is sorted by start time (see [EpgResolver.currentProgram]).
 */
object GuideTimeline {

    /** Width of a time slot of the grid: its columns and its scrolling step. */
    const val SLOT_MS = 30 * 60 * 1000L

    /** Programs of [channel]: its own, else those of one of its grouped versions ("TF1 HD" + "TF1 FHD"). */
    fun programsOf(channel: Channel, schedules: Map<String, List<GuideProgram>>): List<GuideProgram> =
        schedules[channel.id]?.takeIf { it.isNotEmpty() }
            ?: channel.memberIds.firstNotNullOfOrNull { id -> schedules[id]?.takeIf { it.isNotEmpty() } }
            ?: emptyList()

    /** The program aired at [now] (when there is one) then the coming ones: what can still be watched. */
    fun fromNow(programs: List<GuideProgram>, now: Long): List<GuideProgram> {
        val current = EpgResolver.currentIndex(programs, now)
        val first = if (current != -1) current else firstStartedAfter(programs, now)
        if (first >= programs.size) return emptyList()
        // Overlapping programs: the ones ending before the current one are past
        return programs.subList(first, programs.size).filterIndexed { index, program -> index == 0 || program.endMillis > now }
    }

    /** Programs overlapping [from, to), in order. */
    fun inWindow(programs: List<GuideProgram>, from: Long, to: Long): List<GuideProgram> {
        if (programs.isEmpty() || to <= from) return emptyList()
        // Programs starting before the window: only the last few can still be running (as in currentIndex)
        val start = (firstStartedAfter(programs, from) - MAX_OVERLAP_LOOKBACK).coerceAtLeast(0)
        val result = ArrayList<GuideProgram>()
        for (i in start until programs.size) {
            val program = programs[i]
            if (program.startMillis >= to) break
            if (program.endMillis > from && program.endMillis > program.startMillis) result.add(program)
        }
        return result
    }

    /** Program of [programs] covering [time], or null (a hole in the guide). */
    fun programAt(programs: List<GuideProgram>, time: Long): GuideProgram? =
        EpgResolver.currentIndex(programs, time).takeIf { it != -1 }?.let { programs[it] }

    /** Program starting exactly at [start] (the one focused in the grid), or null. */
    fun startingAt(programs: List<GuideProgram>, start: Long): GuideProgram? {
        val index = firstStartedAfter(programs, start - 1)
        return programs.getOrNull(index)?.takeIf { it.startMillis == start }
    }

    /**
     * First program starting after [time]: from a program, its start gives the next one, even when the
     * next one starts a little before its end; from a hole of the guide, the program after the hole.
     */
    fun nextAfter(programs: List<GuideProgram>, time: Long): GuideProgram? {
        for (i in firstStartedAfter(programs, time) until programs.size) {
            if (programs[i].endMillis > programs[i].startMillis) return programs[i]
        }
        return null
    }

    /** Last program starting before [time] (the previous program, or the one before a hole), or null. */
    fun previousBefore(programs: List<GuideProgram>, time: Long): GuideProgram? {
        var index = firstStartedAfter(programs, time - 1) - 1
        while (index >= 0) {
            if (programs[index].endMillis > programs[index].startMillis) return programs[index]
            index--
        }
        return null
    }

    /** Start of the slot containing [time], aligned on the local half hours (time zones with odd offsets too). */
    fun slotStart(time: Long, zone: ZoneId): Long {
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(time)).totalSeconds * 1000L
        return Math.floorDiv(time + offset, SLOT_MS) * SLOT_MS - offset
    }

    /** Whole minutes from [from] to [to], rounded up ("1 min" left until the very end), 0 when past. */
    fun minutesBetween(from: Long, to: Long): Int =
        if (to <= from) 0 else ((to - from + 59_999L) / 60_000L).toInt()

    /** Days from the day of [now] to the day of [time] in [zone]: 0 today, 1 tomorrow, -1 yesterday. */
    fun dayOffset(time: Long, now: Long, zone: ZoneId): Int {
        val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(today, day).toInt()
    }

    /** Index of the first program starting after [time] (programs.size when none). */
    private fun firstStartedAfter(programs: List<GuideProgram>, time: Long): Int {
        var low = 0
        var high = programs.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (programs[mid].startMillis <= time) low = mid + 1 else high = mid
        }
        return low
    }

    private const val MAX_OVERLAP_LOOKBACK = 8
}
