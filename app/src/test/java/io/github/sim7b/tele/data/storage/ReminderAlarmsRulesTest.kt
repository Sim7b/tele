package io.github.sim7b.tele.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderAlarmsRulesTest {

    private val minute = 60_000L
    private val now = 1_000 * minute

    private fun reminder(channel: String, startMinute: Long) =
        Reminder(channel, channel.uppercase(), "Show", startMinute * minute, (startMinute + 30) * minute)

    @Test
    fun `the next alarm is a minute before the next program`() {
        val reminders = listOf(reminder("tf1", 1_030), reminder("m6", 1_010), reminder("past", 990))
        assertEquals(1_009 * minute, Reminders.nextAlarmAt(reminders, now))
        assertNull(Reminders.nextAlarmAt(listOf(reminder("past", 990)), now))
    }

    @Test
    fun `an alarm notifies the reminders come since the previous one, each once`() {
        val first = reminder("m6", 1_010)
        val sameTime = reminder("tf1", 1_010)
        val later = reminder("arte", 1_015)
        val reminders = listOf(first, sameTime, later)
        // Alarm at 1009: both programs of 1010
        assertEquals(listOf(first, sameTime), Reminders.dueAll(reminders, lastFiredAt = now, now = 1_009 * minute))
        // Alarm at 1014: only the later one, not those of 1010 again
        assertEquals(listOf(later), Reminders.dueAll(reminders, lastFiredAt = 1_009 * minute, now = 1_014 * minute))
        // An alarm long late: a program on for a while is not notified any more
        assertTrue(Reminders.dueAll(listOf(first), lastFiredAt = now, now = 1_030 * minute).isEmpty())
    }
}
