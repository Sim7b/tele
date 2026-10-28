package io.github.sim7b.tele.data.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodNamesTest {

    @Test
    fun `reads title, year, quality and languages whatever the conventions`() {
        val dune = VodNames.parse("FR | Dune: Part Two (2024) [4K] MULTI")
        assertEquals("Dune: Part Two", dune.title)
        assertEquals(2024, dune.year)
        assertEquals(listOf("4K"), dune.qualityTags)
        assertEquals(listOf("MULTI"), dune.languageTags)

        val trailing = VodNames.parse("Dune Part Two 2024 FHD VF")
        assertEquals("Dune Part Two", trailing.title)
        assertEquals(2024, trailing.year)
        assertEquals(listOf("1080p"), trailing.qualityTags)
        assertEquals(listOf("VF"), trailing.languageTags)

        assertEquals("Avatar", VodNames.parse("|EN| Avatar (2009) VOSTFR").title)
        assertEquals(listOf("VOSTFR"), VodNames.parse("Avatar (2009) VOSTFR").languageTags)
    }

    @Test
    fun `keeps titles that look like tags or years`() {
        assertEquals("2012", VodNames.parse("2012 (2009)").title)
        assertEquals(2009, VodNames.parse("2012 (2009)").year)
        assertEquals("It: Chapter Two", VodNames.parse("It: Chapter Two (2019)").title)
        assertEquals("Ma vie - Le film", VodNames.parse("Ma vie - Le film").title)
        assertNull(VodNames.parse("Le Comte de Monte-Cristo").year)
        // Country of the version, in brackets
        assertEquals("black-ish", VodNames.parse("black-ish (US)").title)
        assertEquals("100 % physique : Asie", VodNames.parse("100 % physique : Asie (KR)").title)
        // Theater recordings
        assertEquals("The Odyssey", VodNames.parse("The Odyssey (CAM)").title)
        assertEquals(listOf("TS"), VodNames.parse("Street Fighter (TELESYNC)").qualityTags)
    }

    @Test
    fun `reads season and episode numbers`() {
        val episode = VodNames.parse("FR | The Bear S03E02 FHD")
        assertEquals("The Bear", episode.title)
        assertEquals(3, episode.season)
        assertEquals(2, episode.episode)
        assertEquals(1, VodNames.parse("Shōgun S01 E05").season)
        assertEquals(5, VodNames.parse("Shōgun S01 E05").episode)
    }

    @Test
    fun `groups the same title across sources`() {
        val a = VodEntry("a", VodKind.MOVIE, "1", "FR | Dune: Part Two (2024) 4K", "Dune: Part Two", 2024, tmdbId = "693134", posterUrl = "p")
        val b = VodEntry("b", VodKind.MOVIE, "2", "Dune: Part Two 2024 FHD", "Dune: Part Two", 2024)
        val c = VodEntry("b", VodKind.MOVIE, "3", "Dune (2021)", "Dune", 2021)
        val titles = VodLibrary.build(listOf(a, b, c))
        assertEquals(2, titles.size)
        assertEquals(2, titles.first { it.year == 2024 }.entries.size)
        assertEquals("693134", titles.first { it.year == 2024 }.tmdbId)
    }

    @Test
    fun `reads provider genres and ratings`() {
        assertEquals(listOf("Action", "Drame", "Thriller"), VodLibrary.splitGenres("action, Drame / Thriller"))
        assertEquals(7.4, VodLibrary.rating("74%")!!, 0.001)
        assertEquals(7.4, VodLibrary.rating("3.7", fiveBased = true)!!, 0.001)
        assertNull(VodLibrary.rating("0"))
    }
}
