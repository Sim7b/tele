package io.github.sim7b.tele.data.subtitles

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenSubtitlesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `search URLs have sorted lowercase parameters, ids first`() {
        val movie = OpenSubtitles.searchUrl(SubtitleQuery("Dune: Part Two", 2024, tmdbId = "693134"), listOf("fr", "en"))
        assertEquals("languages=en%2Cfr&tmdb_id=693134", movie.encodedQuery)

        val episode = OpenSubtitles.searchUrl(SubtitleQuery("Shōgun", tmdbId = "126308", season = 1, episode = 5), listOf("fr"))
        assertEquals("episode_number=5&languages=fr&parent_tmdb_id=126308&season_number=1", episode.encodedQuery)

        val byImdb = OpenSubtitles.searchUrl(SubtitleQuery("Old Boy", imdbId = "tt0364569"), emptyList())
        assertEquals("imdb_id=364569", byImdb.encodedQuery)

        val byName = OpenSubtitles.searchUrl(SubtitleQuery("Le Comte de Monte-Cristo", 2024), listOf("fr"))
        assertEquals(listOf("languages", "query", "year"), byName.queryParameterNames.toList())
        assertEquals("le comte de monte-cristo", byName.queryParameter("query"))
    }

    @Test
    fun `results keep the order of the languages, then the most downloaded`() {
        fun result(id: Long, language: String, downloads: Int, machine: Boolean = false) = JSONObject().put(
            "attributes",
            JSONObject()
                .put("language", language)
                .put("release", "Release $id")
                .put("download_count", downloads)
                .put("machine_translated", machine)
                .put("files", JSONArray().put(JSONObject().put("file_id", id)))
        )
        val json = JSONObject().put(
            "data",
            JSONArray()
                .put(result(1, "en", 900))
                .put(result(2, "fr", 10))
                .put(result(3, "fr", 500, machine = true))
                .put(result(4, "fr", 200))
                .put(JSONObject().put("attributes", JSONObject().put("language", "fr")))
        )
        val results = OpenSubtitles.parseSearch(json, listOf("fr", "en"))
        assertEquals(listOf(4L, 2L, 3L, 1L), results.map { it.fileId })
        assertTrue(results.first { it.fileId == 3L }.machineTranslated)
        assertTrue(OpenSubtitles.parseSearch(JSONObject(), listOf("fr")).isEmpty())
    }

    @Test
    fun `downloaded subtitles are kept by movie or episode, the oldest dropped`() {
        val cache = SubtitleCache(folder.newFolder("subtitles"), maxFiles = 2)
        val first = cache.save("m:tmdb:1", 11, "fr", "srt", "1\n00:00:01,000 --> 00:00:02,000\nBonjour\n".toByteArray())
        first.file.setLastModified(1_000)
        cache.save("s:tmdb:2#S1E1", 22, "EN", "vtt", "WEBVTT\n".toByteArray()).file.setLastModified(2_000)
        assertEquals(listOf(11L), cache.list("m:tmdb:1").map { it.fileId })
        assertEquals("en", cache.list("s:tmdb:2#S1E1").single().language)
        assertEquals("text/vtt", cache.list("s:tmdb:2#S1E1").single().mimeType)
        // No title in the file names
        assertTrue(!first.file.name.contains("tmdb"))

        cache.save("m:tmdb:3", 33, "../x", "exe", ByteArray(3))
        assertTrue(cache.list("m:tmdb:1").isEmpty())
        assertEquals("x", cache.list("m:tmdb:3").single().language)
        assertEquals("srt", cache.list("m:tmdb:3").single().file.extension)
    }
}
