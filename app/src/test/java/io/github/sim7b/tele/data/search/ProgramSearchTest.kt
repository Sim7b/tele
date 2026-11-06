package io.github.sim7b.tele.data.search

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramSearchTest {

    private val now = 1_000_000L

    private fun channel(id: String, members: List<String> = emptyList()) =
        Channel(id = id, name = id, group = "", streamUrl = "http://example.test/$id", memberIds = members)

    private fun program(channel: String, title: String, start: Long, end: Long) =
        GuideProgram(id = "$channel$start", channelUuid = channel, title = title, startTimeIso = "", endTimeIso = "", startMillis = start, endMillis = end)

    @Test
    fun `programs on air come first, then the coming ones by start, past ones never`() {
        val schedules = mapOf(
            "a" to listOf(
                program("a", "Le Journal de 20 h", now - 5_000, now - 1_000),
                program("a", "Journal de la nuit", now + 9_000, now + 10_000)
            ),
            "b" to listOf(
                program("b", "Le Journal", now - 1_000, now + 1_000),
                program("b", "Le Journal du soir", now + 5_000, now + 6_000)
            )
        )
        val index = ProgramSearch.index(listOf(channel("a"), channel("b")), schedules, now)
        val titles = ProgramSearch.search(index, "journal", now).map { it.program.title }
        assertEquals(listOf("Le Journal", "Le Journal du soir", "Journal de la nuit"), titles)
        // Accents and case ignored, each word starting a word of the title
        assertEquals(listOf("Le Journal du soir"), ProgramSearch.search(index, "JOUR SOIR", now).map { it.program.title })
        assertTrue(ProgramSearch.search(index, "ournal", now).isEmpty())
    }

    @Test
    fun `a program of grouped channels comes once`() {
        val shared = program("hd", "Match", now - 1_000, now + 1_000)
        val schedules = mapOf("hd" to listOf(shared), "fhd" to listOf(shared))
        val index = ProgramSearch.index(listOf(channel("hd"), channel("tf1", members = listOf("fhd"))), schedules, now)
        assertEquals(1, ProgramSearch.search(index, "match", now).size)
    }
}
