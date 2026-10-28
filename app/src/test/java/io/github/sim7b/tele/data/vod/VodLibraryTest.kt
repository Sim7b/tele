package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.parser.M3uEntry
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.ui.vod.nextEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodLibraryTest {

    private fun m3u(name: String, url: String) = M3uEntry(name = name, url = url, tvgId = null, tvgName = null, logo = null, group = "VOD", number = null)

    @Test
    fun `M3U episodes become one series with its seasons`() {
        val entries = M3uVod.toEntries(
            "m",
            listOf(
                m3u("FR | The Bear S01E01", "http://p/series/u/p/1.mkv"),
                m3u("FR | The Bear S01E02 FHD", "http://p/series/u/p/2.mkv"),
                m3u("FR | The Bear S02E01", "http://p/series/u/p/3.mkv"),
                m3u("Dune (2021) 4K", "http://p/movie/u/p/9.mkv")
            )
        )
        val titles = VodLibrary.build(entries)
        assertEquals(2, titles.size)
        val bear = titles.first { it.kind == VodKind.SERIES }
        val episodes = M3uVod.episodes({ "Liste" }, bear.entries)
        assertEquals(listOf("S1E1", "S1E2", "S2E1"), episodes.sortedBy { it.season * 100 + it.number }.map { it.key })
        assertEquals(listOf("1080p"), episodes.first { it.key == "S1E2" }.versions.single().qualityTags)
    }

    @Test
    fun `a movie without a year joins the only one of the same title`() {
        val titles = VodLibrary.build(
            listOf(
                VodEntry("a", VodKind.MOVIE, "1", "Oppenheimer (2023)", "Oppenheimer", 2023),
                VodEntry("b", VodKind.MOVIE, "2", "Oppenheimer", "Oppenheimer", null)
            )
        )
        assertEquals(1, titles.size)
        assertEquals(2, titles.single().entries.size)
    }

    @Test
    fun `TMDB texts replace the provider ones, TVmaze only fills the gaps`() {
        val title = VodTitle("k", VodKind.SERIES, "Shōgun", 2024, null, null, "Résumé du fournisseur", emptyList(), null, null, null, null, emptyList())
        val details = VodDetails(title, seasons = listOf(VodSeason(1, listOf(VodEpisode("S1E1", 1, 1, "")))))
        val enrichment = VodEnrichment(
            plot = "Summary",
            posterUrl = "http://poster",
            genres = listOf("Drama"),
            episodes = mapOf("S1E1" to VodEpisode("S1E1", 1, 1, "Anjin", stillUrl = "http://still"))
        )

        val withTvMaze = VodRepository.enrich(details, enrichment, preferEnrichment = false)
        assertEquals("Résumé du fournisseur", withTvMaze.title.plot)
        assertEquals("http://poster", withTvMaze.title.posterUrl)
        assertEquals("Anjin", withTvMaze.seasons.single().episodes.single().title)

        val withTmdb = VodRepository.enrich(details, enrichment, preferEnrichment = true)
        assertEquals("Summary", withTmdb.title.plot)
    }

    @Test
    fun `plays the episode in progress, else the one after the last watched`() {
        val episodes = listOf(VodEpisode("S1E1", 1, 1, ""), VodEpisode("S1E2", 1, 2, ""), VodEpisode("S1E3", 1, 3, ""))
        assertEquals("S1E1", nextEpisode("t", episodes, emptyMap())?.key)

        val watched = mapOf("t#S1E1" to VodProgress("t#S1E1", "t", 59_000, 60_000, 1))
        assertEquals("S1E2", nextEpisode("t", episodes, watched)?.key)

        val started = watched + ("t#S1E3" to VodProgress("t#S1E3", "t", 10_000, 60_000, 2))
        assertEquals("S1E3", nextEpisode("t", episodes, started)?.key)
        assertNull(nextEpisode("t", emptyList(), started))
    }

    @Test
    fun `rows put the started titles first and skip the empty ones`() {
        val movie = VodTitle("m", VodKind.MOVIE, "A", 2020, null, "http://b", null, listOf("Action"), 7.0, null, null, 5, emptyList())
        val series = VodTitle("s", VodKind.SERIES, "B", 2021, null, null, null, listOf("Action"), null, null, null, 9, emptyList())
        val rows = VodRows.build(listOf(movie, series), mapOf("s#S1E1" to VodProgress("s#S1E1", "s", 1_000, 60_000, 3)), emptyList())
        assertEquals(VodRow.Kind.CONTINUE, rows.first().kind)
        assertEquals(listOf("s"), rows.first().titles.map { it.key })
        assertEquals(null, rows.firstOrNull { it.kind == VodRow.Kind.MY_LIST })
        assertEquals("m", VodRows.featured(listOf(movie, series))?.key)
    }
}
