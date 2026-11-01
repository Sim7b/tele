package io.github.sim7b.tele.data.vod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodLanguagesTest {

    private fun guess(name: String, group: String?) =
        VodLanguageGuess.estimate(name, VodNames.parse(name).languageTags, group)

    @Test
    fun `the group gives the language of its titles`() {
        assertEquals(VodLanguages(setOf("fr")), guess("Dune (2024)", "FR | FILMS ACTION"))
        assertEquals(VodLanguages(setOf("en")), guess("Dune (2024)", "|EN| NETFLIX MOVIES"))
        assertEquals(VodLanguages(setOf("ar")), guess("Dune (2024)", "ARABIC MOVIES"))
        assertEquals(VodLanguages(setOf("es")), guess("Dune (2024)", "Películas Latino"))
        assertTrue(guess("Dune (2024)", "Action").isEmpty)
        assertTrue(guess("Dune (2024)", null).isEmpty)
    }

    @Test
    fun `subtitled versions keep their original audio`() {
        assertEquals(VodLanguages(subtitles = setOf("fr")), guess("Shōgun S01E01", "FR | SÉRIES VOSTFR"))
        assertEquals(VodLanguages(subtitles = setOf("fr")), guess("Pachinko S01E01 VOSTFR", "FR | SÉRIES"))
        assertEquals(VodLanguages(subtitles = setOf("fr")), guess("Old Boy", "Films VOST FR"))
    }

    @Test
    fun `the name tells through its prefix and its tags only`() {
        assertEquals(VodLanguages(setOf("fr")), guess("FR | Dune (2024)", null))
        assertEquals(VodLanguages(setOf("fr")), guess("Dune (2024) MULTI", "FR | 4K"))
        assertEquals(VodLanguages(setOf("fr")), guess("The Bear S01E02 FHD VF", null))
        // Words of a title are no language: "French", "It", "De"
        assertTrue(guess("The French Connection (1971)", null).isEmpty)
        assertTrue(guess("It (2017)", "Horreur").isEmpty)
        assertTrue(guess("English Teacher S01E01", null).isEmpty)
        // A title in uppercase in a group of another language
        assertEquals(VodLanguages(setOf("de")), guess("IT (2017)", "DE | HORROR"))
    }

    @Test(timeout = 2_000)
    fun `long uppercase names are read at once`() {
        val name = "THE LORD OF THE RINGS THE RETURN OF THE KING EXTENDED EDITION REMASTERED ".repeat(4) + "(2003)"
        repeat(1_000) { assertTrue(guess(name, null).isEmpty) }
        assertEquals(VodLanguages(setOf("fr")), guess("VOD FR - Dune (2024)", null))
    }

    @Test
    fun `titles gather the languages of their files and filter by them`() {
        val entries = listOf(
            VodEntry("a", VodKind.MOVIE, "1", "Parasite (2019)", "Parasite", 2019, category = "FR | FILMS"),
            VodEntry("a", VodKind.MOVIE, "2", "Parasite (2019) VOSTFR", "Parasite", 2019, languageTags = listOf("VOSTFR"), category = "KR | MOVIES"),
            VodEntry("a", VodKind.MOVIE, "3", "Dune (2024)", "Dune", 2024, category = "EN | MOVIES")
        )
        val titles = VodLibrary.build(entries)
        val parasite = titles.first { it.title == "Parasite" }
        assertEquals(VodLanguages(setOf("fr"), setOf("fr")), parasite.languages)

        val french = VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(audio = "fr"), emptyMap())
        assertEquals(listOf("Parasite"), french.map { it.title })
        val subtitled = VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(subtitles = "fr"), emptyMap())
        assertEquals(listOf("Parasite"), subtitled.map { it.title })
        val options = VodBrowse.options(titles, VodKind.MOVIE, emptyMap())
        assertEquals(setOf("fr" to 1, "en" to 1), options.audio.toSet())
    }

    @Test
    fun `several countries match any of them, and a former single country is read back`() {
        val facts = mapOf(
            "m:a:2019" to TmdbFacts("1", countries = listOf("KR")),
            "m:b:2020" to TmdbFacts("2", countries = listOf("FR")),
            "m:c:2021" to TmdbFacts("3", countries = listOf("US"))
        )
        val titles = listOf("a" to 2019, "b" to 2020, "c" to 2021).map { (name, year) ->
            VodTitle("m:$name:$year", VodKind.MOVIE, name, year, null, null, null, emptyList(), null, null, null, null, emptyList())
        }
        val kept = VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(countries = setOf("KR", "FR"), sort = VodSort.TITLE), facts)
        assertEquals(listOf("a", "b"), kept.map { it.title })

        val former = JSONObject("""{"defaults":{"MOVIE":{"country":"FR","sort":"TITLE"}}}""")
        assertEquals(setOf("FR"), VodDisplay.fromJson(former).defaults[VodKind.MOVIE]?.countries)
    }
}
