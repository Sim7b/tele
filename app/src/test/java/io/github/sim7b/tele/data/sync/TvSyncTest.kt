package io.github.sim7b.tele.data.sync

import io.github.sim7b.tele.data.storage.Reminder
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.storage.VodProgress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSyncTest {

    private val now = 1_794_254_400_000L

    @Test
    fun `changes are what differs from the last sync, deletions included`() {
        val synced = mapOf(TvSync.FAVORITES to TvSync.favorites(listOf("a", "b")).mapValues { it.value.json })
        val local = mapOf(TvSync.FAVORITES to TvSync.favorites(listOf("b", "c")))

        val changes = TvSync.changes(local, synced, now).getJSONObject(TvSync.FAVORITES)

        assertEquals(setOf("a", "b", "c"), changes.keys().asSequence().toSet())
        assertTrue(changes.getJSONObject("a").getBoolean("d"))
        assertEquals(0, changes.getJSONObject("b").getJSONArray("v").getInt(0))
        assertEquals(now, changes.getJSONObject("c").getLong("t"))
        // Nothing changed: nothing to send
        assertEquals(0, TvSync.changes(local, local.mapValues { (_, items) -> items.mapValues { it.value.json } }, now).length())
    }

    @Test
    fun `positions keep the time they changed`() {
        val progress = mapOf("m:1" to VodProgress("m:1", "m:dune", 60_000, 7_200_000, now - 5_000))
        val changes = TvSync.changes(mapOf(TvSync.VOD_PROGRESS to TvSync.progress(progress)), emptyMap(), now)
        assertEquals(now - 5_000, changes.getJSONObject(TvSync.VOD_PROGRESS).getJSONObject("m:1").getLong("t"))
        assertEquals(progress, TvSync.progressOf(TvSync.progress(progress).mapValues { it.value.json }))
    }

    @Test
    fun `answers of the plugin are read defensively`() {
        val answer = JSONObject(
            """{"items": {"favorites": {"a": {"v": [1], "t": 5, "d": false}, "b": {"t": 6, "d": true}, "c": {"v": "x", "t": 7}},
                "unknown": {"z": {"v": [1], "t": 1}}}}"""
        )
        val items = TvSync.items(answer)
        assertEquals(mapOf("a" to "[1]"), items[TvSync.FAVORITES])
        assertFalse("unknown" in items)
        assertEquals(emptyMap<String, Map<String, String>>(), TvSync.items(null))
    }

    @Test
    fun `a change made while the sync ran is kept and sent next time`() {
        val sent = mapOf(TvSync.MY_LIST to TvSync.myList(listOf("x")))
        val during = mapOf(TvSync.MY_LIST to TvSync.myList(listOf("y", "x")))
        val server = mapOf(TvSync.MY_LIST to mapOf("x" to "[0]", "z" to "[1]"))

        val (kept, remembered) = TvSync.resolve(server, sent, during)

        assertEquals(listOf("y", "x", "z").sorted(), kept.getValue(TvSync.MY_LIST).keys.sorted())
        assertEquals(listOf("x", "z").sorted(), remembered.getValue(TvSync.MY_LIST).keys.sorted())
    }

    @Test
    fun `values of each collection round trip`() {
        val order = listOf("b", "a", "c")
        assertEquals(order, TvSync.favoriteOrder(TvSync.favorites(order).mapValues { it.value.json }))
        val collections = listOf(VodCollection("2", "Family night", listOf("m:1", "m:2")), VodCollection("1", "Horror", emptyList()))
        assertEquals(collections, TvSync.collectionsOf(TvSync.collections(collections).mapValues { it.value.json }))
        val reminders = listOf(Reminder("c1", "TF1", "Journal", now, now + 1_800_000))
        assertEquals(reminders, TvSync.remindersOf(TvSync.reminders(reminders).mapValues { it.value.json }))
        // Malformed values are skipped
        assertTrue(TvSync.remindersOf(mapOf("k" to "[\"c1\", \"TF1\", \"x\", 10, 5]", "j" to "nope")).isEmpty())
        val kept = mapOf(TvSync.FAVORITES to mapOf("a" to "[0]"))
        assertEquals(kept, TvSync.fromJson(TvSync.toJson(kept)).filterValues { it.isNotEmpty() })
        assertTrue(TvSync.fromJson("{broken").isEmpty())
    }

    @Test
    fun `profiles named the same on several TVs share a key`() {
        assertEquals("lea", TvSync.profileKey("Léa"))
        assertEquals("lea", TvSync.profileKey("  LÉA "))
        assertEquals("jeanpierre2", TvSync.profileKey("Jean-Pierre 2"))
        assertEquals(24, TvSync.profileKey("a".repeat(40))?.length)
        assertEquals(null, TvSync.profileKey("日本"))
        assertEquals(null, TvSync.profileKey(""))
    }
}
