package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.remote.TeleApi
import io.github.sim7b.tele.data.storage.VodProgress
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Test

class VodRowsTest {

    private fun series(key: String) = VodTitle(key, VodKind.SERIES, key, 2024, null, null, null, emptyList(), null, null, null, null, emptyList())

    private val day = 24 * 3_600_000L
    private val now = 100 * day

    @Test
    fun `followed series with an episode newer than the last time they were watched`() {
        val byKey = listOf("watched", "old", "seen", "listed", "stale").associateWith { series(it) }
        val progress = mapOf(
            "watched#S1E2" to VodProgress("watched#S1E2", "watched", 10, 100, now - 5 * day),
            "old#S1E1" to VodProgress("old#S1E1", "old", 10, 100, now - day),
            "seen#S2E3" to VodProgress("seen#S2E3", "seen", 10, 100, now - 9 * day)
        )
        val episodes = mapOf(
            // Found after the last watch: new
            "watched" to TeleApi.NewEpisode(now - 2 * day, 1, 3),
            // Found before the last watch: not new
            "old" to TeleApi.NewEpisode(now - 3 * day, 1, 2),
            // That very episode started already
            "seen" to TeleApi.NewEpisode(now - 2 * day, 2, 3),
            // In my list, never watched: new within a week
            "listed" to TeleApi.NewEpisode(now - day, 1, 1),
            "stale" to TeleApi.NewEpisode(now - 8 * day, 1, 1)
        )
        assertEquals(listOf("listed", "watched"), VodRows.withNewEpisode(byKey, progress, episodes, now).map { it.key })
    }

    @Test
    fun `what is new on the server is read defensively`() {
        val fresh = TeleApi.vodFresh(
            JSONObject(
                """{"added": [{"kind": "movie", "id": 5, "at": 9.5}, {"kind": "tv", "id": 6}, {"kind": "series", "id": "x"}],
                    "episodes": {"12": {"at": 1700000000.5, "season": 2, "episode": 3}, "13": {"at": "bad"}, "x": {"at": 1}}}"""
            )
        )
        assertEquals(listOf("movie" to "5"), fresh?.added)
        assertEquals(mapOf("12" to TeleApi.NewEpisode(1_700_000_000_500L, 2, 3)), fresh?.episodes)
        assertEquals(null, TeleApi.vodFresh(null))
    }
}
