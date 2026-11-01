package io.github.sim7b.tele.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {

    private val minute = 60_000L
    private val now = 1_000 * minute

    private fun reminder(channel: String, startMinute: Long) =
        Reminder(channel, channel.uppercase(), "Show", startMinute * minute, (startMinute + 30) * minute)

    @Test
    fun `toggling adds a reminder in start order, then removes it`() {
        val later = reminder("tf1", 1_100)
        val sooner = reminder("m6", 1_050)
        val both = Reminders.toggle(Reminders.toggle(emptyList(), later), sooner)
        assertEquals(listOf(sooner, later), both)
        assertEquals(listOf(sooner), Reminders.toggle(both, later.copy(title = "Other title")))
    }

    @Test
    fun `a reminder is due a minute before its program, and a little after it started`() {
        val reminders = listOf(reminder("tf1", 1_005), reminder("m6", 1_001))
        assertEquals("m6", Reminders.due(reminders, now)?.channelId)
        assertNull(Reminders.due(reminders.take(1), now))
        // Missed while the app was closed: still shown at the start, then dropped
        assertEquals("tf1", Reminders.due(reminders.take(1), 1_009 * minute)?.channelId)
        assertNull(Reminders.due(reminders.take(1), 1_020 * minute))
        assertTrue(Reminders.pending(reminders, 1_020 * minute).isEmpty())
    }

    @Test
    fun `reminders survive a round trip, broken entries are skipped`() {
        val reminders = listOf(reminder("tf1", 1_005))
        assertEquals(reminders, Reminders.fromJson(Reminders.toJson(reminders)))
        assertEquals(1, Reminders.fromJson("""[{"channelId": "a", "start": 5}, {"title": "no channel"}, 3]""").size)
        assertTrue(Reminders.fromJson("{broken").isEmpty())
    }

    @Test
    fun `recent channels keep the last watched first, without duplicates`() {
        assertEquals(listOf("b", "a", "c"), Reminders.recent(listOf("a", "b", "c"), "b"))
        val many = (1..30).map { "c$it" }
        assertEquals(Reminders.MAX_RECENT_CHANNELS, Reminders.recent(many, "new").size)
    }
}
