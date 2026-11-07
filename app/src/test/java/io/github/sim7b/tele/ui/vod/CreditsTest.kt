package io.github.sim7b.tele.ui.vod

import io.github.sim7b.tele.data.storage.VodStore
import io.github.sim7b.tele.data.storage.VodTrackPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CreditsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val episode = 45 * 60_000L

    @Test
    fun `the next episode started by hand in the credits teaches their length`() {
        // 2 min before the end: the credits
        assertEquals(120_000L, learnedCredits(positionMs = episode - 120_000, durationMs = episode))
        // In the last 30 s: the usual lead, nothing to learn; halfway: not the credits
        assertNull(learnedCredits(positionMs = episode - 20_000, durationMs = episode))
        assertNull(learnedCredits(positionMs = episode / 2, durationMs = episode))
        // A short clip
        assertNull(learnedCredits(positionMs = 60_000, durationMs = 5 * 60_000))
    }

    @Test
    fun `once learned, the next episode is offered from the start of the credits`() {
        assertFalse(inCredits(positionMs = episode - 100_000, durationMs = episode))
        assertTrue(inCredits(positionMs = episode - 100_000, durationMs = episode, creditsMs = 120_000))
        // Never more than a few minutes
        assertFalse(inCredits(positionMs = episode - 10 * 60_000, durationMs = episode, creditsMs = 20 * 60_000))
    }

    @Test
    fun `the credits are kept with the languages, and in a backup`() {
        val store = VodStore(File(folder.root, "vod.json"))
        store.saveTrackPrefs("series", VodTrackPrefs(audio = "fr", introEndMs = 90_000, creditsMs = 120_000))
        store.toggleMyList("movie")
        val reread = VodStore(File(folder.root, "vod.json")).apply { load() }
        assertEquals(120_000L, reread.trackPrefs.value["series"]?.creditsMs)

        val other = VodStore(File(folder.root, "other.json"))
        other.importJson(store.exportJson())
        assertEquals(listOf("movie"), other.myList.value)
        assertEquals(90_000L, other.trackPrefs.value["series"]?.introEndMs)
        // Written to its file too
        assertEquals(listOf("movie"), VodStore(File(folder.root, "other.json")).apply { load() }.myList.value)
    }
}
