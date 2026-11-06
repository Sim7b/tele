package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.storage.VodProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class VodContinueTest {

    private fun title(key: String, kind: VodKind = VodKind.MOVIE) =
        VodTitle(key, kind, key, 2020, null, null, null, emptyList(), null, null, null, null, emptyList())

    @Test
    fun `started titles come latest first, a series once from its latest episode, finished ones left out`() {
        val byKey = listOf(title("movie"), title("series", VodKind.SERIES), title("done")).associateBy { it.key }
        val progress = listOf(
            VodProgress("movie", "movie", positionMs = 60_000, durationMs = 6_000_000, updatedAt = 1),
            VodProgress("series#1", "series", positionMs = 60_000, durationMs = 2_400_000, updatedAt = 2),
            VodProgress("series#2", "series", positionMs = 120_000, durationMs = 2_400_000, updatedAt = 3),
            VodProgress("done", "done", positionMs = 5_900_000, durationMs = 6_000_000, updatedAt = 4),
            // Not in the library any more
            VodProgress("gone", "gone", positionMs = 60_000, durationMs = 6_000_000, updatedAt = 5)
        ).associateBy { it.key }

        val continuing = VodRows.continuing(byKey, progress)

        assertEquals(listOf("series", "movie"), continuing.map { it.first.key })
        assertEquals("series#2", continuing.first().second.key)
        assertEquals(listOf("series"), VodRows.continuing(byKey, progress, limit = 1).map { it.first.key })
    }
}
