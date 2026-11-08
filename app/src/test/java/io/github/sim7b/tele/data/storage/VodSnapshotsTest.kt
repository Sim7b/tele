package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.vod.StreamingPlatform
import io.github.sim7b.tele.data.vod.VodAudio
import io.github.sim7b.tele.data.vod.VodDetails
import io.github.sim7b.tele.data.vod.VodEntry
import io.github.sim7b.tele.data.vod.VodEpisode
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodLanguages
import io.github.sim7b.tele.data.vod.VodRepository
import io.github.sim7b.tele.data.vod.VodRow
import io.github.sim7b.tele.data.vod.VodSeason
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.data.vod.VodVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class VodSnapshotsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SecretBox({ key }, cbcTransformation = "AES/CBC/PKCS5Padding")

    private val entry = VodEntry(
        sourceId = "src", kind = VodKind.SERIES, providerId = "42", name = "FR| Shōgun", title = "Shōgun", year = 2024,
        posterUrl = "https://img/p.jpg", genres = listOf("Drame"), rating = 8.6, tmdbId = "126308", addedAt = 1_700_000_000_000,
        category = "Séries", streamUrl = "http://provider/series/user/secret-password/42.mkv", qualityTags = listOf("4K"),
        languageTags = listOf("FR"), season = 1, episode = 2
    )
    private val title = VodTitle(
        key = "series:shogun:2024", kind = VodKind.SERIES, title = "Shōgun", year = 2024, posterUrl = "https://img/p.jpg",
        backdropUrl = null, plot = "Japon, 1600.", genres = listOf("Drame", "Guerre"), rating = 8.6, tmdbId = "126308",
        imdbId = null, addedAt = null, entries = listOf(entry), languages = VodLanguages(setOf("fr"), setOf("en"))
    )
    private val version = VodVersion(
        sourceId = "src", sourceName = "Ma source", url = "http://provider/series/user/secret-password/42.mkv", name = "S01E02",
        resolution = "3840x2160", videoCodec = "hevc", hdr = true, bitrateKbps = 12_000, audio = listOf(VodAudio("fr", "eac3", 6)),
        subtitles = listOf("fr"), durationSecs = 3_300
    )
    private val details = VodDetails(
        title = title, durationSecs = 3_300, director = "Rachel Kondo", cast = listOf("Hiroyuki Sanada"),
        seasons = listOf(VodSeason(1, listOf(VodEpisode("1:2", 1, 2, "Servants of Two Masters", versions = listOf(version))))),
        trailerKey = "yt123"
    )

    @Test
    fun `a sheet is read back as it was kept, encrypted on the device`() {
        val snapshots = VodSnapshots(folder.newFolder("vod"), box)
        snapshots.saveDetails(title.key, details, savedAt = 99)
        val kept = snapshots.details(title.key)!!
        assertEquals(99L, kept.savedAt)
        assertEquals(details, kept.details)
        assertNull(snapshots.details("movie:other:2020"))
        // The account in the file addresses never appears in clear
        val files = folder.root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { assertFalse(String(it.readBytes(), Charsets.ISO_8859_1).contains("secret-password")) }
    }

    @Test
    fun `past the sheets kept, the oldest go`() {
        val dir = folder.newFolder("vod")
        val snapshots = VodSnapshots(dir, box)
        repeat(VodSnapshots.MAX_SHEETS + 3) { index ->
            snapshots.saveDetails("movie:$index", details.copy(title = title.copy(key = "movie:$index")))
        }
        assertEquals(VodSnapshots.MAX_SHEETS, File(dir, "sheets").listFiles()!!.size)
    }

    @Test
    fun `the rows and the library are read back, the library only for the same catalogs`() {
        val snapshots = VodSnapshots(folder.newFolder("vod"), box)
        val rows = listOf(
            VodRow(VodRow.Kind.PLATFORM, listOf(title), name = "Netflix", platform = StreamingPlatform.NETFLIX),
            VodRow(VodRow.Kind.TRENDING, listOf(title), ranked = true)
        )
        snapshots.saveRows(rows)
        assertEquals(rows, snapshots.rows())

        snapshots.saveLibrary("v1|false|src:1:1", listOf(title))
        assertEquals(listOf(title), snapshots.library("v1|false|src:1:1"))
        assertNull(snapshots.library("v1|false|src:2:1"))
    }

    @Test
    fun `the posters found are read back, and nothing from a file of another key`() {
        val dir = folder.newFolder("vod")
        VodSnapshots(dir, box).savePosters(mapOf("movie:a" to "https://img/a.jpg", "movie:b" to ""))
        assertEquals(mapOf("movie:a" to "https://img/a.jpg", "movie:b" to ""), VodSnapshots(dir, box).posters())
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(VodSnapshots(dir, SecretBox({ otherKey }, cbcTransformation = "AES/CBC/PKCS5Padding")).posters().isEmpty())
    }

    @Test
    fun `the library is the same only for the same catalogs, display and app version`() {
        val stamps = mapOf("a" to (10L to 5L), "b" to (20L to 20L))
        val signature = VodRepository.librarySignature(listOf("a", "b"), false, stamps)
        assertEquals(signature, VodRepository.librarySignature(listOf("a", "b"), false, stamps))
        assertNotEquals(signature, VodRepository.librarySignature(listOf("a", "b"), true, stamps))
        assertNotEquals(signature, VodRepository.librarySignature(listOf("a", "b"), false, stamps + ("b" to (21L to 20L))))
        // A catalog not saved as it is in memory: none
        assertNull(VodRepository.librarySignature(listOf("a", "c"), false, stamps))
        assertNull(VodRepository.librarySignature(emptyList(), false, stamps))
    }
}
