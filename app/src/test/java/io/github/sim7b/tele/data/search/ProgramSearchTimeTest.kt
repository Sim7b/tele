package io.github.sim7b.tele.data.search

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ProgramSearchTimeTest {

    private val zone = ZoneId.of("Europe/Paris")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    // Saturday 7 November 2026, 15:00 in Paris
    private val now = at(7, 15)

    private val channel = Channel(id = "c", name = "C", group = "", streamUrl = "http://example.test/c")

    private fun program(title: String, start: Long, end: Long, category: String? = null) =
        GuideProgram(id = "$start", channelUuid = "c", title = title, startTimeIso = "", endTimeIso = "", startMillis = start, endMillis = end, category = category)

    private val schedules = mapOf(
        "c" to listOf(
            program("Le Journal", at(7, 13), at(7, 13, 30)),
            program("Après-midi foot", at(7, 14), at(7, 16), category = "Sport"),
            program("Le Journal", at(7, 20), at(7, 20, 40)),
            program("Le Grand Film", at(7, 21), at(7, 23), category = "Film"),
            program("Le Journal", at(8, 13), at(8, 13, 30)),
            program("Rugby", at(8, 15), at(8, 17), category = "Sport")
        )
    )

    private fun search(query: String, replayable: Boolean = false): List<String> {
        val index = ProgramSearch.index(listOf(channel), schedules, now) { _, _ -> replayable }
        return ProgramSearch.search(index, query, now, zone = zone).map { "${it.program.title} ${it.program.startMillis}" }
    }

    @Test
    fun `words of time narrow the programs`() {
        assertEquals(listOf("Le Journal ${at(7, 20)}"), search("journal ce soir"))
        assertEquals(listOf("Le Journal ${at(8, 13)}"), search("journal demain"))
        assertEquals(listOf("Le Journal ${at(7, 20)}"), search("journal 20h"))
        assertEquals(listOf("Le Journal ${at(7, 20)}"), search("journal 20:15"))
        assertEquals(listOf("Le Grand Film ${at(7, 21)}"), search("film 9pm"))
        // Only a time: what is on then
        assertEquals(listOf("Après-midi foot ${at(7, 14)}"), search("maintenant"))
        // An hour past today: tomorrow's
        assertEquals(listOf("Le Journal ${at(8, 13)}"), search("journal 13h"))
        assertEquals(listOf("Rugby ${at(8, 15)}"), search("sport demain"))
    }

    @Test
    fun `the genre is searched too, and past programs come when they can be replayed`() {
        assertEquals(listOf("Le Grand Film ${at(7, 21)}"), search("film"))
        assertEquals(listOf("Après-midi foot ${at(7, 14)}", "Rugby ${at(8, 15)}"), search("sport"))
        // Replayable: after the coming ones, the latest first
        assertEquals(
            listOf("Le Journal ${at(7, 20)}", "Le Journal ${at(8, 13)}", "Le Journal ${at(7, 13)}"),
            search("journal", replayable = true)
        )
    }

    @Test
    fun `numbers of a name are not taken for an hour`() {
        val query = ProgramSearch.parse("tf1 hd", now, zone)
        assertEquals(listOf("tf1", "hd"), query.words)
        assertNull(query.window)
        // "24h" is not an hour: a word of the title
        assertEquals(listOf("24h", "chrono"), ProgramSearch.parse("24h chrono", now, zone).words)
        // "soir" alone stays a word ("Grand Soir")
        assertEquals(listOf("grand", "soir"), ProgramSearch.parse("grand soir", now, zone).words)
        assertEquals(at(8, 19), ProgramSearch.parse("demain soir", now, zone).window?.first)
    }
}
