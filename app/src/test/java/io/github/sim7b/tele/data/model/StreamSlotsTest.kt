package io.github.sim7b.tele.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSlotsTest {

    private fun account(active: Boolean, vararg profiles: String) =
        JSONObject("""{"is_active": $active, "profiles": [${profiles.joinToString(",")}]}""")

    private fun profile(max: Int, viewers: Int, active: Boolean = true) =
        """{"max_streams": $max, "current_viewers": $viewers, "is_active": $active}"""

    @Test
    fun `sums the limited active profiles of active accounts`() {
        val slots = StreamSlots.fromAccounts(
            listOf(
                account(true, profile(max = 1, viewers = 1)),
                account(true, profile(max = 2, viewers = 0)),
                account(false, profile(max = 5, viewers = 0)),
                account(true, profile(max = 3, viewers = 0, active = false))
            )
        )
        assertEquals(StreamSlots(used = 1, total = 3), slots)
        assertEquals(2, slots?.free)
    }

    @Test
    fun `ignores unlimited profiles and returns null without any limit`() {
        assertNull(StreamSlots.fromAccounts(listOf(account(true, profile(max = 0, viewers = 4)))))
        assertNull(StreamSlots.fromAccounts(emptyList()))
    }

    @Test
    fun `never reports more used slots than allowed`() {
        val slots = StreamSlots.fromAccounts(listOf(account(true, profile(max = 1, viewers = 3))))
        assertEquals(StreamSlots(used = 1, total = 1), slots)
        assertEquals(0, slots?.free)
    }

    @Test
    fun `a preview always leaves a free connection for the channel opened`() {
        // 2 of 4 used: a preview may start, it leaves one free
        assertTrue(StreamSlots(used = 2, total = 4).allowsPreview(previewing = false))
        // Last free connection: kept for the channel the user opens
        assertFalse(StreamSlots(used = 3, total = 4).allowsPreview(previewing = false))
        // The preview playing holds one of them: moving to another card gives it back
        assertTrue(StreamSlots(used = 3, total = 4).allowsPreview(previewing = true))
        assertFalse(StreamSlots(used = 1, total = 1).allowsPreview(previewing = true))
    }

    @Test
    fun `connections in use come from the live stats of the server`() {
        // Two profiles of 2 and 4 connections; the profiles say nobody watches, the server says otherwise
        val accounts = listOf(
            JSONObject("""{"is_active": true, "profiles": [{"id": 1, "max_streams": 2, "current_viewers": 0}, {"id": 2, "max_streams": 4, "current_viewers": 0}]}""")
        )
        val stats = JSONObject(
            """{"live": {"channels": [{"channel_id": "a", "m3u_profile_id": 1}, {"channel_id": "b", "m3u_profile_id": "1"}, {"channel_id": "c"}]},
                "vod": {"vod_connections": [{"connections": [{"m3u_profile_id": 2}]}]}}"""
        )
        assertEquals(mapOf("1" to 2, "2" to 1), StreamSlots.connectionsByProfile(stats))
        val slots = StreamSlots.fromAccounts(accounts, stats)
        assertEquals(StreamSlots(used = 3, total = 6), slots)
        // Without the stats (older server, no admin rights): the profiles
        assertEquals(StreamSlots(used = 0, total = 6), StreamSlots.fromAccounts(accounts, null))
    }
}
