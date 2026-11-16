package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.remote.TeleApi
import io.github.sim7b.tele.ui.vod.countdownFraction
import io.github.sim7b.tele.ui.vod.introSegmentTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodSegmentsTest {

    @Test
    fun `segments of theintrodb`() {
        val found = VodSegments.parse(JSONObject(
            """{"tvdb_id": 81189, "intro": [{"start_ms": 228665, "end_ms": 246498}], "credits": [{"start_ms": 3432884, "end_ms": null}]}"""
        ))
        assertEquals(VodSegments(228665, 246498, 3432884), found)
        assertEquals(87_116L, found.creditsLengthMs(3_520_000))
        // Credits after the end of the file, or longer than a third of it: not trusted
        assertNull(found.creditsLengthMs(3_000_000))
        assertNull(VodSegments(creditsStartMs = 1_000_000).creditsLengthMs(2_000_000))
        // A null start is the start of the file; inverted or broken ones are left out
        assertEquals(0L, VodSegments.parse(JSONObject("""{"intro": [{"start_ms": null, "end_ms": 23000}]}""")).introStartMs)
        assertTrue(VodSegments.parse(JSONObject("""{"intro": [{"start_ms": 9000, "end_ms": 3000}]}""")).isEmpty)
        assertTrue(VodSegments.parse(null).isEmpty)
    }

    @Test
    fun `the intro is offered only while it plays`() {
        val episode = 45 * 60_000L
        // Cold open before the intro: nothing to skip yet
        assertNull(introSegmentTarget(60_000, episode, 228_665, 246_498))
        assertEquals(246_498L, introSegmentTarget(230_000, episode, 228_665, 246_498))
        assertNull(introSegmentTarget(245_000, episode, 228_665, 246_498))
        // Intro from the start: not in the first seconds
        assertNull(introSegmentTarget(1_000, episode, 0, 60_000))
        assertEquals(60_000L, introSegmentTarget(10_000, episode, 0, 60_000))
    }

    @Test
    fun `related titles of the library in the order of tmdb`() {
        fun title(key: String, kind: VodKind, tmdb: String?) =
            VodTitle(key, kind, key, 2020, null, null, null, emptyList(), null, tmdb, null, null, emptyList())
        val library = listOf(title("a", VodKind.MOVIE, "1"), title("b", VodKind.MOVIE, "2"), title("c", VodKind.SERIES, "2"), title("self", VodKind.MOVIE, "9"))
        val ids = TeleApi.relatedIds(JSONObject("""{"ids": ["2", "x", "1", "9", "2"]}"""))
        assertEquals(listOf("2", "1", "9"), ids)
        assertEquals(listOf("b", "a"), VodLibrary.byTmdbIds(library, VodKind.MOVIE, ids, exclude = "self").map { it.key })
        assertEquals(1f, countdownFraction(99), 0f)
        assertEquals(0f, countdownFraction(0), 0f)
    }
}
