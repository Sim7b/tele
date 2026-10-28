package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.storage.VodStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class VodBrowseTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun title(key: String, name: String, year: Int?, kind: VodKind = VodKind.MOVIE, genres: List<String> = emptyList(), tmdb: String? = null) =
        VodTitle(key, kind, name, year, null, null, null, genres, null, tmdb, null, null, emptyList())

    private val parasite = title("p", "Parasite", 2019, genres = listOf("Drame"))
    private val oldBoy = title("o", "Old Boy", 2003, genres = listOf("Thriller"))
    private val dune = title("d", "Dune : deuxième partie", 2024, genres = listOf("Science-fiction"), tmdb = "693134")
    private val shogun = title("s", "Shōgun", 2024, kind = VodKind.SERIES)
    private val titles = listOf(parasite, oldBoy, dune, shogun)
    private val facts = mapOf(
        "p" to TmdbFacts("496243", countries = listOf("KR"), studios = listOf(Studio("1", "Barunson")), popularity = 50.0),
        "o" to TmdbFacts("670", countries = listOf("KR"), studios = listOf(Studio("2", "Show East")), popularity = 30.0),
        "d" to TmdbFacts("693134", countries = listOf("US"), studios = listOf(Studio("3", "Legendary")), popularity = 200.0)
    )

    @Test
    fun `filters by country, studio, genre and decade, sorted by popularity`() {
        val korean = VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(country = "KR"), facts)
        assertEquals(listOf("p", "o"), korean.map { it.key })
        assertEquals(listOf("d"), VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(studio = Studio("3", "Legendary")), facts).map { it.key })
        assertEquals(listOf("o"), VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(decade = 2000), facts).map { it.key })
        assertEquals(listOf("p"), VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(genre = "Drame"), facts).map { it.key })
        assertEquals(listOf("d", "p", "o"), VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(), facts).map { it.key })
        assertEquals(listOf("d", "o", "p"), VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(sort = VodSort.TITLE), facts, Locale.FRANCE).map { it.key })
    }

    @Test
    fun `lists the values of the filters with their counts`() {
        val options = VodBrowse.options(titles, VodKind.MOVIE, facts)
        assertEquals(listOf("KR" to 2, "US" to 1), options.countries)
        assertEquals(3, options.studios.size)
        assertEquals(listOf(2020 to 1, 2010 to 1, 2000 to 1), options.decades)
    }

    @Test
    fun `finds titles by the start of their words, accents ignored`() {
        val index = VodBrowse.index(titles)
        assertEquals(listOf("d"), VodBrowse.search(index, "dune deux", facts).map { it.key })
        assertEquals(listOf("s"), VodBrowse.search(index, "shogun", facts).map { it.key })
        assertEquals(listOf("o"), VodBrowse.search(index, "boy", facts).map { it.key })
        assertTrue(VodBrowse.search(index, "oy", facts).isEmpty())
    }

    @Test
    fun `matches TMDB rankings by id, else by title and year`() {
        val matcher = VodRankings.Matcher(titles, facts)
        assertEquals("p", matcher.match(RankedItem(VodKind.MOVIE, "496243", "Gisaengchung", null, 2019))?.key)
        assertEquals("s", matcher.match(RankedItem(VodKind.SERIES, "126308", "Shogun", null, 2024))?.key)
        assertNull(matcher.match(RankedItem(VodKind.MOVIE, "1", "Inconnu", null, 2020)))
        assertEquals("d", matcher.byTmdbId(VodKind.MOVIE, "693134")?.key)
    }

    @Test
    fun `rows show the TMDB trends and the collections`() {
        val matcher = VodRankings.Matcher(titles, facts)
        val rankings = mapOf(Ranking.TRENDING to listOf(RankedItem(VodKind.MOVIE, "693134", "Dune", null, 2024), RankedItem(VodKind.MOVIE, "670", "Old Boy", null, 2003)))
        val collections = listOf(io.github.sim7b.tele.data.storage.VodCollection("c", "Ciné coréen", listOf("p", "o")))
        val rows = VodRows.build(titles, emptyMap(), emptyList(), collections, rankings, matcher)
        val trending = rows.first { it.kind == VodRow.Kind.TRENDING }
        assertEquals(listOf("d", "o"), trending.titles.map { it.key })
        assertTrue(trending.ranked)
        assertEquals("Ciné coréen", rows.first { it.kind == VodRow.Kind.COLLECTION }.name)
    }

    @Test
    fun `the TMDB index is kept on the device and read back`() {
        val file = folder.newFile("index.jsonl")
        file.writeText(
            """{"k":"p","id":"496243","f":true,"c":["KR"],"s":[["1","Barunson"]],"p":50.5,"at":1}
               |{"k":"x","f":false,"at":2}
               |""".trimMargin()
        )
        val index = VodIndex(file)
        index.load()
        assertEquals(listOf("KR"), index.facts("p")?.countries)
        assertEquals(Studio("1", "Barunson"), index.facts("p")?.studios?.single())
        assertEquals(false, index.facts("x")?.found)
        assertEquals(1, index.version.value)
    }

    @Test
    fun `display settings are kept, rows hidden and theater recordings recognized`() {
        val display = VodDisplay(
            hiddenRows = setOf(VodRow.Kind.TRENDING),
            posterSize = PosterSize.LARGE,
            hideTheater = true,
            defaults = mapOf(VodKind.MOVIE to VodFilter(country = "FR", sort = VodSort.TITLE))
        )
        assertEquals(display, VodDisplay.fromJson(display.toJson()))
        assertEquals(VodDisplay(), VodDisplay.fromJson(null))

        val cam = parasite.copy(entries = listOf(VodEntry("a", VodKind.MOVIE, "1", "Odyssey (CAM)", "Odyssey", 2026, qualityTags = listOf("CAM"))))
        assertTrue(VodDisplay.isTheaterCopy(cam))
        assertTrue(!VodDisplay.isTheaterCopy(parasite))

        val matcher = VodRankings.Matcher(titles, facts)
        val rankings = mapOf(Ranking.TRENDING to listOf(RankedItem(VodKind.MOVIE, "693134", "Dune", null, 2024)))
        val rows = VodRows.build(titles, emptyMap(), emptyList(), emptyList(), rankings, matcher, hidden = setOf(VodRow.Kind.TRENDING))
        assertTrue(rows.none { it.kind == VodRow.Kind.TRENDING })
    }

    @Test
    fun `titles are marked watched and taken out of continue watching`() {
        val file = folder.newFile("progress.json")
        file.delete()
        val store = VodStore(file)
        store.saveProgress("s#S1E1", "s", 10_000, 60_000)
        store.saveProgress("s#S1E2", "s", 60_000, 60_000)
        store.markWatched("p", "p")
        assertTrue(store.progress.value.getValue("p").finished)

        // Out of "Continue watching": the started episode is forgotten, the watched one kept
        store.clearTitleProgress("s")
        assertEquals(setOf("s#S1E2", "p"), store.progress.value.keys)
        store.clearProgress("p")
        assertEquals(setOf("s#S1E2"), store.progress.value.keys)
    }

    @Test
    fun `collections are created, filled, renamed, deleted and kept`() {
        val file = folder.newFile("state.json")
        file.delete()
        val store = VodStore(file)
        val id = store.createCollection("Soirée famille")
        store.toggleInCollection(id, "p")
        store.toggleInCollection(id, "o")
        store.toggleInCollection(id, "p")
        store.renameCollection(id, "Ciné coréen")

        val reloaded = VodStore(file)
        reloaded.load()
        val collection = reloaded.collections.value.single()
        assertEquals("Ciné coréen", collection.name)
        assertEquals(listOf("o"), collection.titleKeys)

        reloaded.deleteCollection(id)
        assertTrue(reloaded.collections.value.isEmpty())
    }
}
