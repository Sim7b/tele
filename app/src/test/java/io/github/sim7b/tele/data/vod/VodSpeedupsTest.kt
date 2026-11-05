package io.github.sim7b.tele.data.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.Normalizer
import java.util.Locale

class VodSpeedupsTest {

    @Test
    fun `the prefix of a name is read like the former regex, codes joined by a dash too`() {
        assertEquals("FR", VodLanguageGuess.namePrefix("FR | Dune"))
        assertEquals("EN", VodLanguageGuess.namePrefix(" |EN| Dune"))
        assertEquals("VOD FR", VodLanguageGuess.namePrefix("VOD FR - Dune"))
        assertEquals("FR", VodLanguageGuess.namePrefix("FR: Dune"))
        assertEquals("BE-FR", VodLanguageGuess.namePrefix("BE-FR - Interns (Hippocrate)"))
        assertEquals("FR", VodLanguageGuess.namePrefix("FR- Dune"))
        // Not prefixes: a title, a long uppercase word, no separator after the codes
        assertNull(VodLanguageGuess.namePrefix("It: Chapter Two"))
        assertNull(VodLanguageGuess.namePrefix("DUNES | x"))
        assertNull(VodLanguageGuess.namePrefix("FR Dune"))
        assertNull(VodLanguageGuess.namePrefix("FR -Dune"))
        assertEquals(setOf("fr"), VodLanguageGuess.estimate("BE-FR - Interns", emptyList(), null).audio)
    }

    @Test
    fun `the plain ASCII key is the same as the normalized one`() {
        fun slow(title: String) = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT).replace("&", " and ").filter { it.isLetterOrDigit() }
        listOf("Fast & Furious 7", "The Lord of the Rings: Part 2", "WALL-E", "  x_y.z 2024 ", "Amélie").forEach {
            assertEquals(slow(it), VodNames.key(it))
        }
    }

    @Test
    fun `the title loses a prefix of codes joined by a dash`() {
        assertEquals("Interns (Hippocrate)", VodNames.parse("BE-FR - Interns (Hippocrate)").title)
        assertEquals("It: Chapter Two", VodNames.parse("It: Chapter Two").title)
    }
}
