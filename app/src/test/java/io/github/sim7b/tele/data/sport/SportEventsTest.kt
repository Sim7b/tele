package io.github.sim7b.tele.data.sport

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
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
}
