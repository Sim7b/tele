package io.github.sim7b.tele.data.vod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlatformsTest {

    private fun title(key: String, year: Int?, rating: Double? = null, genres: List<String> = emptyList()) =
        VodTitle(key, VodKind.MOVIE, key, year, null, null, null, genres, rating, null, null, null, emptyList())

    @Test
    fun `each streaming service has a list of movies and one of series`() {
        StreamingPlatform.entries.forEach { platform ->
            assertEquals(VodKind.MOVIE, Ranking.of(platform, VodKind.MOVIE)?.kind)
            assertEquals(platform, Ranking.of(platform, VodKind.SERIES)?.platform)
        }
    }

    @Test
    fun `the catalog of a service is its subscription titles in the country of the TV, by popularity`() {
        val query = VodRankings.platformQuery(StreamingPlatform.CANAL_PLUS, 3, "FR").toMap()
        assertEquals("3", query["page"])
        assertEquals("381", query["with_watch_providers"])
        assertEquals("FR", query["watch_region"])
        assertEquals("flatrate", query["with_watch_monetization_types"])
        assertEquals("popularity.desc", query["sort_by"])
        assertEquals("US", VodRankings.platformQuery(StreamingPlatform.MAX, 1, null).toMap()["watch_region"])
    }

    @Test
    fun `a list is read up to its last page, its limit and the 500 pages of TMDB`() {
        assertEquals(3, VodRankings.lastPage(totalPages = 3, maxPages = 40))
        assertEquals(40, VodRankings.lastPage(totalPages = 900, maxPages = 40))
        assertEquals(500, VodRankings.lastPage(totalPages = 9000, maxPages = 9000))
        assertEquals(0, VodRankings.lastPage(totalPages = 0, maxPages = 40))
    }

    @Test
    fun `a logo is the PNG of the TMDB network, never another address`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w300/wwemzKWzjKYJFfCeiB57q3r4Bcm.png",
            VodRankings.logoOf(JSONObject("""{"logo_path": "/wwemzKWzjKYJFfCeiB57q3r4Bcm.png"}"""))
        )
        assertNull(VodRankings.logoOf(JSONObject("""{"logo_path": "https://evil.example/logo.png"}""")))
        assertNull(VodRankings.logoOf(JSONObject("""{"logo_path": "/../../x.png"}""")))
        // Android draws no SVG
        assertNull(VodRankings.logoOf(JSONObject("""{"logo_path": "/abc.svg"}""")))
        assertNull(VodRankings.logoOf(JSONObject("{}")))
    }

    @Test
    fun `each service has its own network for its logo`() {
        assertEquals(StreamingPlatform.entries.size, StreamingPlatform.entries.map { it.networkId }.toSet().size)
    }

    @Test
    fun `a service opens on its most popular titles, then its latest releases, best rated and genres`() {
        val popular = listOf(
            title("hit", 2015, rating = 6.0, genres = listOf("Action")),
            title("recent", 2025, rating = 7.0, genres = listOf("Action")),
            title("classic", 1990, rating = 9.0, genres = listOf("Action")),
            title("other", 2020, genres = listOf("Action"))
        )
        val shelves = VodBrowse.platformShelves(popular, emptyMap())
        assertEquals(listOf(VodShelf.Kind.POPULAR, VodShelf.Kind.LATEST, VodShelf.Kind.TOP_RATED, VodShelf.Kind.GENRE), shelves.map { it.kind })
        // The order of TMDB kept for the popular ones
        assertEquals(listOf("hit", "recent", "classic", "other"), shelves[0].titles.map { it.key })
        assertEquals("recent", shelves[1].titles.first().key)
        assertEquals("classic", shelves[2].titles.first().key)
        assertEquals("Action", shelves[3].genre)
        assertTrue(VodBrowse.platformShelves(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `the own productions of a service come first, apart from the titles it only shows`() {
        val popular = listOf(title("licensed", 2020, genres = listOf("Drama")))
        val originals = listOf(title("original", 2024))
        val shelves = VodBrowse.platformShelves(popular, emptyMap(), originals)
        assertEquals(VodShelf.Kind.ORIGINALS, shelves.first().kind)
        assertEquals(listOf("original"), shelves.first().titles.map { it.key })
        assertEquals(listOf("licensed"), shelves.first { it.kind == VodShelf.Kind.POPULAR }.titles.map { it.key })
        // Only its productions in the library
        assertEquals(listOf(VodShelf.Kind.ORIGINALS), VodBrowse.platformShelves(emptyList(), emptyMap(), originals).map { it.kind })
    }

    @Test
    fun `the studios of a service are the companies with exactly its name`() {
        val json = JSONObject(
            """{"results": [{"id": 5358, "name": "CANAL+"}, {"id": 104, "name": "Canal+ Polska"}, {"id": 0, "name": "Canal+"}, {"name": "Canal+"}]}"""
        )
        assertEquals(listOf(5358), VodRankings.companiesNamed(json, "Canal+"))
        assertTrue(VodRankings.companiesNamed(JSONObject("{}"), "Canal+").isEmpty())
    }

    @Test
    fun `each service has networks for its series and studios for its movies`() {
        StreamingPlatform.entries.forEach {
            assertTrue(it.networkIds.isNotEmpty())
            assertTrue(it.studios.isNotEmpty())
        }
    }

    @Test
    fun `the platform rows of the home carry their service`() {
        val titles = listOf(VodTitle("m", VodKind.MOVIE, "Dune", 2021, null, null, null, emptyList(), null, "438631", null, null, emptyList()))
        val rankings = mapOf(Ranking.NETFLIX_MOVIES to listOf(RankedItem(VodKind.MOVIE, "438631", "Dune", null, 2021)))
        val rows = VodRows.build(titles, emptyMap(), emptyList(), rankings = rankings, matcher = VodRankings.Matcher(titles, emptyMap()))
        val row = rows.single { it.kind == VodRow.Kind.PLATFORM }
        assertEquals(StreamingPlatform.NETFLIX, row.platform)
        assertNull(rows.firstOrNull { it.kind == VodRow.Kind.PLATFORM && it.platform == null })
    }
}
