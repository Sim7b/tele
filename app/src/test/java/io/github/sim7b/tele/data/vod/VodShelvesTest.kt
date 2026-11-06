package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.ui.vod.ratingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VodShelvesTest {

    private fun title(key: String, year: Int?, rating: Double? = null, genres: List<String> = emptyList(), addedAt: Long? = null) =
        VodTitle(key, VodKind.MOVIE, key, year, null, null, null, genres, rating, null, null, addedAt, emptyList())

    @Test
    fun `the rating is the TMDB vote when known, else the provider's, out of 10`() {
        assertEquals(8.1, VodBrowse.rating(title("a", 2020, rating = 6.0), TmdbFacts("1", vote = 8.1))!!, 0.0)
        assertEquals(6.0, VodBrowse.rating(title("a", 2020, rating = 6.0), TmdbFacts("1", vote = 0.0))!!, 0.0)
        assertEquals(6.0, VodBrowse.rating(title("a", 2020, rating = 6.0), null)!!, 0.0)
        // Unknown, zero, broken or out of scale
        assertNull(VodBrowse.rating(title("a", 2020), null))
        assertNull(VodBrowse.rating(title("a", 2020, rating = 0.0), null))
        assertNull(VodBrowse.rating(title("a", 2020, rating = Double.NaN), null))
        assertEquals(10.0, VodBrowse.rating(title("a", 2020, rating = 85.0), null)!!, 0.0)
    }

    @Test
    fun `the rating is written with one decimal in the language of the TV`() {
        assertEquals("7,4", ratingLabel(7.43, Locale.FRANCE))
        assertEquals("7.0", ratingLabel(7.0, Locale.US))
    }

    @Test
    fun `the rows are the latest releases, the best rated, then the main genres`() {
        val titles = listOf(
            title("old", 1999, rating = 9.0, genres = listOf("Drama")),
            title("new", 2024, rating = 6.0, genres = listOf("Drama"), addedAt = 1),
            title("newer-added", 2024, rating = 7.0, genres = listOf("Drama"), addedAt = 2),
            title("mid", 2010, genres = listOf("Drama")),
            title("undated", null, rating = 8.0, genres = listOf("Comedy"))
        )
        val shelves = VodBrowse.shelves(titles, facts = mapOf("mid" to TmdbFacts("9", vote = 7.5)))

        assertEquals(listOf(VodShelf.Kind.LATEST, VodShelf.Kind.TOP_RATED, VodShelf.Kind.GENRE), shelves.map { it.kind })
        // Latest year first, the latest added first within a year; the undated ones left out
        assertEquals(listOf("newer-added", "new", "mid", "old"), shelves[0].titles.map { it.key })
        // TMDB vote counted
        assertEquals(listOf("old", "undated", "mid", "newer-added", "new"), shelves[1].titles.map { it.key })
        // A genre needs a few titles: Drama (4), not Comedy (1)
        assertEquals("Drama", shelves[2].genre)
        assertEquals(listOf("old", "mid", "newer-added", "new"), shelves[2].titles.map { it.key })
    }

    @Test
    fun `nothing to show gives no rows`() {
        assertTrue(VodBrowse.shelves(emptyList(), emptyMap()).isEmpty())
        // Neither dated nor rated: no row at all
        assertTrue(VodBrowse.shelves(listOf(title("x", null)), emptyMap()).isEmpty())
    }
}
