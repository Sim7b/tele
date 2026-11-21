package io.github.sim7b.tele.data.sport

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.storage.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportEventsTest {

    private val hour = 60 * 60_000L
    private val now = 1_000 * hour

    private fun channel(id: String, members: List<String> = emptyList()) =
        Channel(id = id, name = id.uppercase(), group = "Sport", streamUrl = "http://example.test/$id", memberIds = members)

    private fun program(channel: String, title: String, start: Long, end: Long, category: String? = null) =
        GuideProgram(id = "$channel$start", channelUuid = channel, title = title, startTimeIso = "", endTimeIso = "", startMillis = start, endMillis = end, category = category)

    @Test
    fun `the followed names are cleaned`() {
        assertEquals(listOf("PSG", "Stade Toulousain", "Ligue 1"), SportEvents.clean(" PSG, Stade Toulousain\nLigue 1 ; psg ,, "))
        assertEquals(SportEvents.MAX_FOLLOWED, SportEvents.clean((1..120).joinToString(",") { "Team $it" }).size)
    }

    @Test
    fun `a program matches every word of a followed name, as whole words`() {
        val followed = listOf(listOf("psg"), listOf("ligue", "1"))
        assertTrue(SportEvents.matches(followed, program("c", "PSG - Marseille", 0, 1)))
        assertTrue(SportEvents.matches(followed, program("c", "Lens - Nantes", 0, 1, category = "Football · Ligue 1")))
        assertFalse(SportEvents.matches(followed, program("c", "PSGX magazine", 0, 1)))
        assertFalse(SportEvents.matches(followed, program("c", "Ligue des champions", 0, 1)))
    }

    @Test
    fun `on air first, then coming ones, then replays, each once`() {
        val schedules = mapOf(
            "a" to listOf(
                program("a", "Avant-match PSG", now - 3 * hour, now - 2 * hour),
                program("a", "PSG - OM", now - hour / 2, now + hour),
                program("a", "PSG - Lyon", now + 3 * 24 * hour, now + 3 * 24 * hour + hour)
            ),
            "b" to listOf(program("b", "Rugby : Stade Toulousain - Racing", now + 2 * hour, now + 4 * hour)),
            "hd" to listOf(program("hd", "PSG - OM", now - hour / 2, now + hour))
        )
        val channels = listOf(channel("a"), channel("b"), channel("hd"))
        val found = SportEvents.find(listOf("PSG", "Stade Toulousain"), channels, schedules, now, replayable = { c, _ -> c.id == "a" })
        // Too far ahead: not shown; the same match on two channels: once
        assertEquals(listOf("PSG - OM", "Rugby : Stade Toulousain - Racing", "Avant-match PSG"), found.map { it.program.title })
        // Without replay, the past is not shown
        assertEquals(2, SportEvents.find(listOf("PSG", "Stade Toulousain"), channels, schedules, now).size)
        assertTrue(SportEvents.find(emptyList(), channels, schedules, now).isEmpty())
    }

    @Test
    fun `the coming matches get a reminder, once each`() {
        val a = channel("a")
        val b = channel("b")
        val events = listOf(
            SportEvent(a, program("a", "PSG - OM", now - hour / 2, now + hour)),
            SportEvent(a, program("a", "PSG - Lyon", now + 2 * hour, now + 4 * hour)),
            SportEvent(b, program("b", "PSG : le flash", now + 3 * hour, now + 3 * hour + 10 * 60_000)),
            SportEvent(b, program("b", "PSG - Nice", now + 30 * hour, now + 32 * hour)),
            SportEvent(b, program("b", "PSG - Lens", now + 5 * hour, now + 7 * hour)),
            SportEvent(b, program("b", "PSG - Brest", now + 6 * hour, now + 8 * hour))
        )
        val set = listOf(Reminder("b", "B", "PSG - Lens", now + 5 * hour, now + 7 * hour))
        val alerts = SportEvents.alerts(events, set, setOf(SportEvents.alertKey("b", now + 6 * hour)), now)
        // Not the one on air, the short one, the one beyond a day, the one already set nor the one already offered
        assertEquals(listOf(Reminder("a", "A", "PSG - Lyon", now + 2 * hour, now + 4 * hour)), alerts)
    }
}
