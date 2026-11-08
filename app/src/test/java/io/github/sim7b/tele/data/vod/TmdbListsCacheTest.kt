package io.github.sim7b.tele.data.vod

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TmdbListsCacheTest {

    private val dir: File = Files.createTempDirectory("tmdb").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private val dune = RankedItem(VodKind.MOVIE, "438631", "Dune", "Dune: Part One", 2021)
    private val shogun = RankedItem(VodKind.SERIES, "126308", "Shōgun", null, null)

    @Test
    fun `a list is read back as it was kept, with when it was fetched`() {
        val cache = TmdbListsCache(dir)
        cache.write("rankings_fr_fr", mapOf("TRENDING" to listOf(dune, shogun), "UPCOMING" to emptyList()), fetchedAt = 1234)
        val entry = cache.read("rankings_fr_fr")!!
        assertEquals(1234L, entry.fetchedAt)
        assertEquals(listOf(dune, shogun), entry.lists["TRENDING"])
        assertEquals(emptyList<RankedItem>(), entry.lists["UPCOMING"])
        // Written beside, then renamed: no leftover
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `nothing is read for a list never kept, a broken file or a name that is not one`() {
        val cache = TmdbListsCache(dir)
        assertNull(cache.read("rankings_fr_fr"))
        File(dir, "broken.json").writeText("{not json")
        assertNull(cache.read("broken"))
        // Never a path
        cache.write("../escape", mapOf("A" to listOf(dune)))
        assertNull(cache.read("../escape"))
        assertFalse(File(dir.parentFile, "escape.json").exists())
    }

    @Test
    fun `entries of another version or without their fields are left out`() {
        assertNull(TmdbListsCache.decode("""{"version": 99, "fetchedAt": 1, "lists": {}}"""))
        assertNull(TmdbListsCache.decode("""{"version": 1, "lists": {}}"""))
        val entry = TmdbListsCache.decode(
            """{"version": 1, "fetchedAt": 5, "lists": {"A": [{"kind": "MOVIE", "id": "1", "title": "Ok"}, {"kind": "BOOK", "id": "2", "title": "No"}, {"kind": "MOVIE", "title": "No id"}]}}"""
        )!!
        assertEquals(listOf("1"), entry.lists["A"]!!.map { it.tmdbId })
    }

    @Test
    fun `TMDB is asked again when busy, after the pause it asks for`() {
        assertTrue(VodMetadata.shouldRetryTmdb(429))
        assertTrue(VodMetadata.shouldRetryTmdb(503))
        assertFalse(VodMetadata.shouldRetryTmdb(401))
        assertFalse(VodMetadata.shouldRetryTmdb(404))
        assertFalse(VodMetadata.shouldRetryTmdb(200))
        assertEquals(3_000L, VodMetadata.tmdbRetryDelayMs(0, retryAfterS = 3))
        assertEquals(10_000L, VodMetadata.tmdbRetryDelayMs(0, retryAfterS = 3600))
        assertEquals(1_000L, VodMetadata.tmdbRetryDelayMs(0, null))
        assertEquals(2_000L, VodMetadata.tmdbRetryDelayMs(1, null))
    }

    @Test
    fun `a genre of the provider opens on its popular titles, latest releases and best rated`() {
        fun title(key: String, year: Int, rating: Double) =
            VodTitle(key, VodKind.MOVIE, key, year, null, null, null, listOf("Comédie", "Drame"), rating, null, null, null, emptyList())
        val titles = listOf(title("a", 2020, 6.0), title("b", 2024, 7.5), title("c", 1999, 8.8), title("d", 2010, 5.0))
        val shelves = VodBrowse.libraryGenreShelves(titles, emptyMap())
        assertEquals(listOf(VodShelf.Kind.POPULAR, VodShelf.Kind.LATEST, VodShelf.Kind.TOP_RATED), shelves.map { it.kind })
        assertEquals("b", shelves[1].titles.first().key)
        assertEquals("c", shelves[2].titles.first().key)
        assertTrue(VodBrowse.libraryGenreShelves(emptyList(), emptyMap()).isEmpty())
    }
}
