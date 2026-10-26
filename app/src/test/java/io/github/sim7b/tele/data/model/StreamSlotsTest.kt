package io.github.sim7b.tele.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
