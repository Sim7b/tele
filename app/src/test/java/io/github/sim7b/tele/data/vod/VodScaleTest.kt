package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.parser.M3uEntry
import io.github.sim7b.tele.data.storage.SecretBox
import io.github.sim7b.tele.data.storage.VodCatalogCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.text.Collator
import java.util.Locale
import javax.crypto.KeyGenerator

/**
 * A library of 60,000 titles (the size of the large provider accounts): names read, titles grouped,
 * rows and the A to Z list built, catalog saved and read back, in bounded time. The times are printed
 * for comparison; the bounds leave room for slow CI machines.
 */
class VodScaleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val genres = listOf("Action", "Drame", "Comédie", "Thriller", "Science-fiction", "Animation", "Documentaire", "Horreur")
    private val qualities = listOf("4K", "FHD", "HD", "", "MULTI", "VF", "VOSTFR")

    private fun catalog(): List<VodEntry> {
        val movies = (0 until 45_000).map { i ->
            val name = "FR | Film numéro $i (${1950 + i % 75}) ${qualities[i % qualities.size]}"
            val parsed = VodNames.parse(name)
            VodEntry(
                sourceId = if (i % 3 == 0) "b" else "a",
                kind = VodKind.MOVIE,
                providerId = i.toString(),
                name = name,
                title = parsed.title,
                year = parsed.year,
                posterUrl = "http://img.example/poster/$i.jpg",
                genres = listOf(genres[i % genres.size], genres[(i / 7) % genres.size]).distinct(),
                rating = ((i % 90) / 10.0).takeIf { it > 0 },
                tmdbId = if (i % 2 == 0) (100_000 + i / 3).toString() else null,
                addedAt = 1_700_000_000_000L + i,
                qualityTags = parsed.qualityTags,
                languageTags = parsed.languageTags
            )
        }
        // M3U series: 15,000 episodes of 500 series
        val episodes = M3uVod.toEntries("m", (0 until 15_000).map { i ->
            M3uEntry(
                name = "FR | Série ${i / 30} S${(i % 30) / 10 + 1}E${i % 10 + 1} FHD",
                url = "http://p.example/series/u/p/$i.mkv",
                tvgId = null,
                tvgName = null,
                logo = "http://img.example/series/${i / 30}.jpg",
                group = "Séries",
                number = null
            )
        })
        return movies + episodes
    }

    @Test
    fun `60,000 titles are grouped and listed quickly`() {
        val entries = catalog()

        var start = System.nanoTime()
        val titles = VodLibrary.build(entries)
        val buildMs = (System.nanoTime() - start) / 1_000_000
        // Movies of the same TMDB id across sources are grouped; the 500 series gather their episodes
        assertEquals(500, titles.count { it.kind == VodKind.SERIES })
        assertTrue(titles.size < entries.size)

        start = System.nanoTime()
        val rows = VodRows.build(titles, emptyMap(), emptyList())
        val rowsMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(rows.isNotEmpty())

        start = System.nanoTime()
        val collator = Collator.getInstance(Locale.FRANCE)
        val sorted = titles.map { collator.getCollationKey(it.title) to it }.sortedBy { it.first }.map { it.second }
        val sortMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(titles.size, sorted.size)

        println("VodScaleTest: ${entries.size} entries -> ${titles.size} titles; build $buildMs ms, rows $rowsMs ms, A-Z $sortMs ms")
        assertTrue("build took $buildMs ms", buildMs < 5_000)
        assertTrue("rows took $rowsMs ms", rowsMs < 3_000)
        assertTrue("sort took $sortMs ms", sortMs < 5_000)
    }

    @Test
    fun `a catalog of 60,000 titles is saved and read back`() {
        val entries = catalog()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cache = VodCatalogCache(folder.newFile("catalog.jsonl"), SecretBox({ key }, cbcTransformation = "AES/CBC/PKCS5Padding"))

        var start = System.nanoTime()
        cache.save("src", 42L, entries)
        val saveMs = (System.nanoTime() - start) / 1_000_000
        start = System.nanoTime()
        val loaded = cache.load("src")
        val loadMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(entries, loaded?.entries)
        assertEquals(42L, loaded?.savedAt)
        println("VodScaleTest: cache of ${entries.size} entries, ${folder.root.listFiles()!!.sumOf { it.length() } / 1024} KB; save $saveMs ms, load $loadMs ms")
        assertTrue("load took $loadMs ms", loadMs < 10_000)
    }
}
