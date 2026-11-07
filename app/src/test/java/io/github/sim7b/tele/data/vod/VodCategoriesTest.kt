package io.github.sim7b.tele.data.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodCategoriesTest {

    @Test
    fun `the country of a category comes from a code in uppercase or a country name`() {
        assertEquals("FR", VodCategories.country("FR | FILMS ACTION"))
        assertEquals("BE", VodCategories.country("|BE| SERIES"))
        assertEquals("GB", VodCategories.country("UK - MOVIES"))
        assertEquals("FR", VodCategories.country("FRANCE CINEMA"))
        assertEquals("DE", VodCategories.country("ALLEMAGNE"))
        assertEquals("US", VodCategories.country("USA | Movies"))
        // Not countries: lowercase words, quality, "AR" for Arabic, nothing at all
        assertNull(VodCategories.country("Films de it"))
        assertNull(VodCategories.country("4K UHD"))
        assertNull(VodCategories.country("AR | AFLAM"))
        assertNull(VodCategories.country("NETFLIX"))
    }

    @Test
    fun `categories without a country are studios and platforms, or none`() {
        val estimator = VodLanguageGuess.Estimator()
        assertEquals(VodCategories.STUDIOS, VodCategories.hint("NETFLIX", estimator).country)
        assertEquals(VodCategories.STUDIOS, VodCategories.hint("Disney+ Séries", estimator).country)
        assertEquals(VodCategories.STUDIOS, VodCategories.hint("|MARVEL| COLLECTION", estimator).country)
        assertEquals(VodCategories.NO_COUNTRY, VodCategories.hint("4K UHD", estimator).country)
        assertEquals(VodCategories.NO_COUNTRY, VodCategories.hint("Films d'action", estimator).country)
        // A country first: the platform of a country stays with it
        assertEquals("FR", VodCategories.hint("FR | NETFLIX", estimator).country)
        // A word that only contains one: not a studio
        assertFalse(VodCategories.isStudio("MAXIMUM"))
        // Chosen with a country, the studios come along
        val categories = listOf(VodCategory(VodKind.MOVIE, "1", "FR | FILMS"), VodCategory(VodKind.MOVIE, "2", "NETFLIX"), VodCategory(VodKind.MOVIE, "3", "4K"))
        val hints = categories.associate { it.key to VodCategories.hint(it.name, estimator) }
        assertEquals(setOf("MOVIE:1", "MOVIE:2"), VodCategories.matching(categories, hints, setOf("FR", VodCategories.STUDIOS), emptySet(), emptySet()))
    }

    @Test
    fun `adult, anime and religious categories are told by their names`() {
        assertEquals(setOf(VodCategories.ADULT), VodCategories.themes("FR | XXX"))
        assertEquals(setOf(VodCategories.ADULT), VodCategories.themes("Adultes 18+"))
        assertEquals(setOf(VodCategories.ADULT), VodCategories.themes("+18 PORN"))
        assertEquals(setOf(VodCategories.ANIME), VodCategories.themes("FR | ANIMÉS"))
        assertEquals(setOf(VodCategories.ANIME), VodCategories.themes("MANGA VOSTFR"))
        assertEquals(setOf(VodCategories.RELIGIOUS), VodCategories.themes("Films religieux"))
        assertEquals(setOf(VodCategories.RELIGIOUS), VodCategories.themes("BIBLE | FILMS"))
        // Neither words inside others, nor a year
        assertTrue(VodCategories.themes("ANIMATION 2018").isEmpty())
        assertTrue(VodCategories.themes("FR | FILMS 1918").isEmpty())
    }

    @Test
    fun `adult categories only come with their theme, the themes add their categories`() {
        val estimator = VodLanguageGuess.Estimator()
        val categories = listOf(
            VodCategory(VodKind.MOVIE, "1", "FR | FILMS"),
            VodCategory(VodKind.MOVIE, "2", "FR | XXX"),
            VodCategory(VodKind.SERIES, "3", "ANIME"),
            VodCategory(VodKind.MOVIE, "4", "EN | MOVIES")
        )
        val hints = categories.associate { it.key to VodCategories.hint(it.name, estimator) }
        fun keys(countries: Set<String> = emptySet(), themes: Set<String> = emptySet()) =
            VodCategories.matching(categories, hints, countries, emptySet(), emptySet(), themes).map { it.substringAfter(':') }
        // Nothing chosen: everything but the adult ones
        assertEquals(listOf("1", "3", "4"), keys())
        assertEquals(listOf("1"), keys(countries = setOf("FR")))
        // France and anime: both; anime only: anime only
        assertEquals(listOf("1", "3"), keys(countries = setOf("FR"), themes = setOf(VodCategories.ANIME)))
        assertEquals(listOf("3"), keys(themes = setOf(VodCategories.ANIME)))
        // Adult chosen: the adult ones come too
        assertEquals(listOf("1", "2"), keys(countries = setOf("FR"), themes = setOf(VodCategories.ADULT)))
    }

    @Test
    fun `the choice keeps the categories matching every criterion given`() {
        val estimator = VodLanguageGuess.Estimator()
        val categories = listOf(
            VodCategory(VodKind.MOVIE, "1", "FR | FILMS"),
            VodCategory(VodKind.MOVIE, "2", "FR | VOSTFR"),
            VodCategory(VodKind.SERIES, "3", "EN | SERIES"),
            VodCategory(VodKind.MOVIE, "4", "NETFLIX")
        )
        val hints = categories.associate { it.key to VodCategories.hint(it.name, estimator) }
        fun keys(countries: Set<String> = emptySet(), audio: Set<String> = emptySet(), subtitles: Set<String> = emptySet()) =
            VodCategories.matching(categories, hints, countries, audio, subtitles).map { it.substringAfter(':') }

        assertEquals(listOf("1", "2", "3", "4"), keys())
        assertEquals(listOf("1", "2"), keys(countries = setOf("FR")))
        assertEquals(listOf("1"), keys(audio = setOf("fr")))
        assertEquals(listOf("2"), keys(subtitles = setOf("fr")))
        assertEquals(listOf("3"), keys(audio = setOf("en")))
        assertTrue(keys(countries = setOf("FR"), audio = setOf("en")).isEmpty())
    }

    @Test
    fun `selections survive a round trip and include what they should`() {
        val chosen = VodSelection(all = false, keys = setOf("MOVIE:1", "SERIES:3"))
        val saved = VodCategories.toJson(mapOf("a" to chosen, "b" to VodSelection.ALL))
        assertEquals(mapOf("a" to chosen, "b" to VodSelection.ALL), VodCategories.fromJson(saved))
        assertTrue(VodCategories.fromJson("{broken").isEmpty())

        assertTrue(chosen.includes(VodCategory(VodKind.MOVIE, "1", "FR")))
        assertFalse(chosen.includes(VodCategory(VodKind.SERIES, "1", "FR")))
        assertTrue(VodSelection.ALL.includes(VodCategory(VodKind.SERIES, "9", "Any")))
    }

    @Test
    fun `new categories like the chosen ones are suggested`() {
        val categories = listOf(
            VodCategory(VodKind.MOVIE, "1", "FR | FILMS"),
            VodCategory(VodKind.MOVIE, "2", "EN | MOVIES"),
            VodCategory(VodKind.MOVIE, "3", "FR | NOUVEAUTES"),
            VodCategory(VodKind.MOVIE, "4", "DE | FILME")
        )
        val chosen = VodSelection(all = false, keys = setOf("MOVIE:1"), known = setOf("MOVIE:1", "MOVIE:2"))
        assertEquals(listOf("3"), VodCategories.suggestions(categories, chosen).map { it.id })
        // Nothing recorded, everything loaded or nothing chosen: no suggestion
        assertTrue(VodCategories.suggestions(categories, chosen.copy(known = emptySet())).isEmpty())
        assertTrue(VodCategories.suggestions(categories, VodSelection.ALL).isEmpty())
        assertTrue(VodCategories.suggestions(categories, VodSelection.NONE.copy(known = setOf("MOVIE:1"))).isEmpty())

        val saved = VodCategories.toJson(mapOf("a" to chosen))
        assertEquals(chosen, VodCategories.fromJson(saved)["a"])
    }
}
