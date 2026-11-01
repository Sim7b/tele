package io.github.sim7b.tele.ui.vod

import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.storage.VodStore
import io.github.sim7b.tele.data.storage.VodTrackPrefs
import io.github.sim7b.tele.data.vod.VodDetails
import io.github.sim7b.tele.data.vod.VodEpisode
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodRows
import io.github.sim7b.tele.data.vod.VodSeason
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.data.vod.VodVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VodPlaybackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val series = VodTitle("s", VodKind.SERIES, "Shōgun", 2024, "poster", "backdrop", "Japon, 1600.", emptyList(), null, null, null, null, emptyList())

    private fun episode(season: Int, number: Int, title: String = "") =
        VodEpisode("S${season}E$number", season, number, title, versions = listOf(VodVersion("src", "Source", "http://x/$season-$number.mkv", "file")))

    private val details = VodDetails(
        title = series,
        seasons = listOf(
            VodSeason(0, listOf(episode(0, 1, "Making of"))),
            VodSeason(1, listOf(episode(1, 2, "Servants of Two Masters"), episode(1, 1, "Anjin"))),
            VodSeason(2, listOf(episode(2, 1)))
        )
    )

    @Test
    fun `the next episode follows the watching order, specials last`() {
        val episodes = details.seasons.flatMap { it.episodes }
        assertEquals("S1E2", episodeAfter(episodes, "S1E1")?.key)
        assertEquals("S2E1", episodeAfter(episodes, "S1E2")?.key)
        assertEquals("S0E1", episodeAfter(episodes, "S2E1")?.key)
        assertNull(episodeAfter(episodes, "S0E1"))
        assertNull(episodeAfter(episodes, "missing"))
    }

    @Test
    fun `the next episode is offered with its labels, resumed unless finished`() {
        val current = playRequest(series, "s", episode(1, 1, "Anjin"), episode(1, 1).versions, null, 0)!!
        assertEquals("Shōgun · S1E1 · Anjin", current.label)
        assertEquals("Anjin", current.episodeTitle)

        val next = nextPlayRequest(current, details, emptyMap())!!
        assertEquals("S1E2", next.episodeKey)
        assertEquals(2, next.episode)
        assertEquals("http://x/1-2.mkv", next.url)
        assertEquals("backdrop", next.imageUrl)
        assertEquals("Japon, 1600.", next.plot)
        assertEquals(0L, next.startMs)

        val key = VodRows.progressKey("s", "S1E2")
        val started = mapOf(key to VodProgress(key, "s", 120_000, 3_000_000, 1))
        assertEquals(120_000L, nextPlayRequest(current, details, started)?.startMs)
        val finished = mapOf(key to VodProgress(key, "s", 2_990_000, 3_000_000, 1))
        assertEquals(0L, nextPlayRequest(current, details, finished)?.startMs)

        val movie = current.copy(episodeKey = null)
        assertNull(nextPlayRequest(movie, details, emptyMap()))
        assertNull(nextPlayRequest(current, null, emptyMap()))
    }

    @Test
    fun `the remote seeks 10 s back, 30 s forward, faster when held`() {
        assertEquals(-10_000L, seekStep(forward = false, repeatCount = 0))
        assertEquals(30_000L, seekStep(forward = true, repeatCount = 0))
        assertEquals(10_000L, seekStep(forward = true, repeatCount = 5))
        assertEquals(-30_000L, seekStep(forward = false, repeatCount = 30))
        assertEquals(60_000L, seekStep(forward = true, repeatCount = 100))
    }

    @Test
    fun `times and credits`() {
        assertEquals("0:00", positionLabel(-5))
        assertEquals("4:05", positionLabel(245_000))
        assertEquals("1:02:03", positionLabel(3_723_000))

        assertTrue(inCredits(positionMs = 2_980_000, durationMs = 3_000_000))
        assertFalse(inCredits(positionMs = 2_900_000, durationMs = 3_000_000))
        // A short clip has no credits
        assertFalse(inCredits(positionMs = 50_000, durationMs = 60_000))
    }

    @Test
    fun `picture sizes get their usual name`() {
        assertEquals("720p", videoFormatLabel(1280, 718))
        assertEquals("1080p", videoFormatLabel(1920, 800))
        assertEquals("4K", videoFormatLabel(3840, 1600))
        assertEquals("576p", videoFormatLabel(720, 576))
        assertNull(videoFormatLabel(null, 1080))
    }

    @Test
    fun `languages are kept per title`() {
        val file = folder.newFile("state.json")
        file.delete()
        val store = VodStore(file)
        store.saveTrackPrefs("s", VodTrackPrefs(audio = "ja", subtitles = "fr", subtitlesOn = true))
        store.saveTrackPrefs("m", VodTrackPrefs(audio = "en"))

        val reloaded = VodStore(file)
        reloaded.load()
        assertEquals(VodTrackPrefs("ja", "fr", true), reloaded.trackPrefs.value["s"])
        assertEquals(VodTrackPrefs("en", null, false), reloaded.trackPrefs.value["m"])
    }
}
