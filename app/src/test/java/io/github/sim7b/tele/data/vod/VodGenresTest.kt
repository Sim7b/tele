package io.github.sim7b.tele.data.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VodGenresTest {

    private val today = LocalDate.of(2026, 11, 8)

    private fun title(key: String, year: Int?) =
        VodTitle(key, VodKind.MOVIE, key, year, null, null, null, emptyList(), null, null, null, null, emptyList())

    @Test
    fun `the genres of a kind are the ones TMDB has for it`() {
        val movies = VodGenre.of(VodKind.MOVIE)
        val series = VodGenre.of(VodKind.SERIES)
        assertTrue(VodGenre.COMEDY in movies && VodGenre.COMEDY in series)
        assertTrue(VodGenre.HORROR in movies)
        assertFalse(VodGenre.HORROR in series)
        assertFalse(VodGenre.KIDS in movies)
        assertEquals(10759, VodGenre.ACTION.tmdbId(VodKind.SERIES))
        assertEquals(28, VodGenre.ACTION.tmdbId(VodKind.MOVIE))
    }

    @Test
    fun `the popular titles of a genre are sorted by popularity`() {
        val query = VodGenres.query(35, VodKind.MOVIE, GenreList.POPULAR, 2, today).toMap()
        assertEquals("35", query["with_genres"])
        assertEquals("2", query["page"])
        assertEquals("popularity.desc", query["sort_by"])
        assertNull(query["vote_count.gte"])
    }

    @Test
    fun `the best of the last years are the best rated since three years, by enough people`() {
        val movies = VodGenres.query(35, VodKind.MOVIE, GenreList.RECENT_BEST, 1, today).toMap()
        assertEquals("vote_average.desc", movies["sort_by"])
        assertEquals("2023-11-08", movies["primary_release_date.gte"])
        assertEquals("150", movies["vote_count.gte"])
        val series = VodGenres.query(35, VodKind.SERIES, GenreList.RECENT_BEST, 1, today).toMap()
        assertEquals("2023-11-08", series["first_air_date.gte"])
        assertNull(series["primary_release_date.gte"])
    }

    @Test
    fun `the best of all time have no date and need many votes`() {
        val query = VodGenres.query(35, VodKind.MOVIE, GenreList.ALL_TIME_BEST, 1, today).toMap()
        assertEquals("vote_average.desc", query["sort_by"])
        assertEquals("1500", query["vote_count.gte"])
        assertNull(query["primary_release_date.gte"])
    }

    @Test
    fun `a genre shows its best of the last years, of all time, its popular titles, then the latest`() {
        val recent = listOf(title("recent", 2025))
        val classic = listOf(title("classic", 1972))
        val popular = listOf(title("hit", 2019), title("recent", 2025))
        val shelves = VodBrowse.genreShelves(popular, recent, classic)
        assertEquals(
            listOf(VodShelf.Kind.RECENT_BEST, VodShelf.Kind.ALL_TIME_BEST, VodShelf.Kind.POPULAR, VodShelf.Kind.LATEST),
            shelves.map { it.kind }
        )
        assertEquals(listOf("recent", "hit", "classic"), shelves.last().titles.map { it.key })
        // Lists the library has nothing of are left out
        assertEquals(listOf(VodShelf.Kind.POPULAR, VodShelf.Kind.LATEST), VodBrowse.genreShelves(popular, emptyList(), emptyList()).map { it.kind })
        assertTrue(VodBrowse.genreShelves(emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
