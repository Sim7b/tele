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
        val korean = VodBrowse.filter(titles, VodKind.MOVIE, VodFilter(countries = setOf("KR")), facts)
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
    fun `the catalogs of the streaming services become rows named after them`() {
        val matcher = VodRankings.Matcher(titles, facts)
        val rankings = mapOf(
            Ranking.NETFLIX_SERIES to listOf(RankedItem(VodKind.SERIES, "126308", "Shogun", null, 2024)),
            Ranking.ON_THE_AIR to listOf(RankedItem(VodKind.SERIES, "126308", "Shogun", null, 2024))
        )
        val rows = VodRows.build(titles, emptyMap(), emptyList(), emptyList(), rankings, matcher)
        val netflix = rows.single { it.kind == VodRow.Kind.PLATFORM }
        assertEquals("Netflix", netflix.name)
        assertEquals(listOf("s"), netflix.titles.map { it.key })
        assertEquals(listOf("s"), rows.single { it.kind == VodRow.Kind.ON_THE_AIR }.titles.map { it.key })
        // The service, in the country of the TV (else the United States), by popularity, included in the subscription
        val query = VodRankings.query(Ranking.NETFLIX_MOVIES, 2, "FR").toMap()
        assertEquals("8", query["with_watch_providers"])
        assertEquals("FR", query["watch_region"])
        assertEquals("flatrate", query["with_watch_monetization_types"])
        assertEquals("2", query["page"])
        assertEquals("US", VodRankings.query(Ranking.PRIME_VIDEO_SERIES, 1, null).toMap()["watch_region"])
        assertNull(VodRankings.query(Ranking.POPULAR_MOVIES, 1, "FR").toMap()["region"])
        assertEquals("FR", VodRankings.query(Ranking.NOW_PLAYING, 1, "FR").toMap()["region"])
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
    fun `facts of the server are given to the titles of their TMDB ids and kept`() {
        val file = folder.newFile("index.jsonl")
        val index = VodIndex(file)
        val dune = VodTitle("dune", VodKind.MOVIE, "Dune", 2021, null, null, null, emptyList(), null, "438631", null, null, emptyList())
        val noId = VodTitle("x", VodKind.MOVIE, "X", 2021, null, null, null, emptyList(), null, null, null, null, emptyList())
        val answer = org.json.JSONObject(
            """{"facts": {"438631": {"v": 7.8, "n": 12000, "p": 81.2, "l": "en", "c": ["us"], "s": [["1", "Legendary"]]}, "x": {"v": 1}}, "until": 5.5}"""
        )
        val (server, until) = io.github.sim7b.tele.data.remote.TeleApi.vodFacts(answer)!!
        assertEquals(5.5, until, 0.0)
        assertEquals(setOf("438631"), server.keys)
        val byKind = server.mapKeys { "movie:${it.key}" }
        assertTrue(index.importServerFacts(listOf(dune, noId), byKind, now = 10))
        assertEquals(12000, index.facts("dune")?.votes)
        assertEquals(listOf("US"), index.facts("dune")?.countries)
        assertNull(index.facts("x"))
        // The same facts again: nothing written, no new version
        assertTrue(!index.importServerFacts(listOf(dune), byKind, now = 20))
        // Kept on the device
        val again = VodIndex(file)
        again.load()
        assertEquals(7.8, again.facts("dune")!!.vote, 0.0)
    }

    @Test
    fun `display settings are kept, rows hidden and theater recordings recognized`() {
        val display = VodDisplay(
            hiddenRows = setOf(VodRow.Kind.TRENDING),
            posterSize = PosterSize.LARGE,
            hideTheater = true,
            defaults = mapOf(VodKind.MOVIE to VodFilter(countries = setOf("FR", "BE"), audio = "fr", subtitles = "en", sort = VodSort.TITLE))
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

    @Test
    fun `the rows of a tab are the ones of a full sort of each row`() {
        val random = kotlin.random.Random(7)
        val genres = listOf("Action", "Drame", "Comédie", "Horreur", "Animation")
        val titles = (0 until 600).map { i ->
            VodTitle(
                "movie:$i", VodKind.MOVIE, "T$i", if (i % 7 == 0) null else 1990 + random.nextInt(35), null, null, null,
                // Sometimes the same genre twice: the title comes once in its row
                List(1 + random.nextInt(3)) { genres[random.nextInt(genres.size)] }, if (i % 5 == 0) null else random.nextInt(100) / 10.0,
                null, null, random.nextLong(1_000), emptyList()
            )
        }
        val facts = titles.filter { it.key.hashCode() % 3 == 0 }
            .associate { it.key to TmdbFacts("1", popularity = random.nextDouble(), vote = random.nextInt(100) / 10.0, votes = random.nextInt(2_000)) }
        fun ratingOf(title: VodTitle) = VodBrowse.rating(title, facts[title.key])
        val byRating = compareByDescending<VodTitle> { ratingOf(it) ?: 0.0 }.thenByDescending { it.year ?: 0 }
        val expectedTop = titles.filter { ratingOf(it) != null }.sortedWith(byRating).take(40)
        val shelves = VodBrowse.shelves(titles, facts)
        assertEquals(expectedTop, shelves.first { it.kind == VodShelf.Kind.TOP_RATED }.titles)
        shelves.filter { it.kind == VodShelf.Kind.GENRE }.forEach { shelf ->
            assertEquals(titles.filter { shelf.genre in it.genres }.sortedWith(byRating).take(40), shelf.titles)
        }
        assertEquals(genres.size, shelves.count { it.kind == VodShelf.Kind.GENRE })
        // Sorts read each value once, same order as before
        assertEquals(
            titles.sortedWith(compareByDescending<VodTitle> { facts[it.key]?.popularity ?: 0.0 }.thenByDescending { it.rating ?: 0.0 }),
            VodBrowse.sort(titles, VodSort.POPULARITY, facts)
        )
        assertEquals(titles.sortedByDescending { ratingOf(it) ?: 0.0 }, VodBrowse.sort(titles, VodSort.RATING, facts))
    }
}
