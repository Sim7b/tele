package io.github.sim7b.tele.data.search

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSearchTest {

    private fun channel(name: String, number: Int? = null, program: String? = null) = Channel(
        id = name,
        name = name,
        group = "",
        streamUrl = "http://example/$name",
        number = number,
        currentProgram = program?.let { EpgProgram(title = it, startTime = "20:00", endTime = "21:00") }
    )

    private val channels = listOf(
        channel("France 2 HD", number = 2),
        channel("TF1", number = 1, program = "Koh-Lanta"),
        channel("France Télévisions Info"),
        channel("M6 HD", number = 6),
        channel("Canal+ Sport", program = "Ligue des champions")
    )

    private fun names(query: String) = ChannelSearch.search(channels, query).map { it.name }

    @Test
    fun `ignores case, accents and punctuation`() {
        assertEquals(listOf("France Télévisions Info"), names("france televisions"))
        assertEquals(listOf("Canal+ Sport"), names("CANAL SPORT"))
    }

    @Test
    fun `matches spoken spellings with spaces`() {
        assertEquals(listOf("TF1"), names("tf 1"))
        assertEquals(listOf("M6 HD"), names("M 6"))
    }

    @Test
    fun `ranks name prefixes first, then numbers and programs`() {
        assertEquals(listOf("France 2 HD", "France Télévisions Info"), names("France"))
        assertEquals(listOf("TF1"), names("koh lanta"))
        assertEquals(listOf("Canal+ Sport"), names("champions"))
        assertEquals(listOf("M6 HD"), names("6"))
    }

    @Test
    fun `blank queries and limits`() {
        assertEquals(emptyList<String>(), names("  - "))
        assertEquals(1, ChannelSearch.search(channels, "france", limit = 1).size)
    }
}
