package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.remote.TeleApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcharrGroupsTest {

    private fun entry(kind: VodKind, category: String?) = VodEntry("a", kind, "1", "Dune", "Dune", 2021, category = category)

    @Test
    fun `groups of the plugin are parsed defensively`() {
        assertEquals(TeleApi.VodGroups(all = true), TeleApi.vodGroups(JSONObject("""{"mode": "all"}""")))
        val selected = TeleApi.vodGroups(JSONObject(
            """{"mode": "selected", "groups": [{"name": "FR | FILMS", "type": "movie"}, {"name": "X", "type": "live"}, {"type": "series"}]}"""
        ))
        assertEquals(TeleApi.VodGroups(all = false, groups = setOf("FR | FILMS|movie")), selected)
        assertNull(TeleApi.vodGroups(JSONObject("""{"mode": "other"}""")))
        assertNull(TeleApi.vodGroups(JSONObject("""{"mode": "selected"}""")))
        assertNull(TeleApi.vodGroups(null))
    }

    @Test
    fun `only the groups served are kept`() {
        val allowed = setOf("FR | FILMS|movie", "FR | SERIES|series")
        assertTrue(DispatcharrVod.allows(allowed, entry(VodKind.MOVIE, "FR | FILMS")))
        assertFalse(DispatcharrVod.allows(allowed, entry(VodKind.SERIES, "FR | FILMS")))
        assertFalse(DispatcharrVod.allows(allowed, entry(VodKind.MOVIE, "[AR] AFLAM")))
        assertFalse(DispatcharrVod.allows(allowed, entry(VodKind.MOVIE, null)))
        assertTrue(DispatcharrVod.allows(null, entry(VodKind.MOVIE, null)))
    }

    @Test
    fun `every category becomes the groups served`() {
        val allowed = setOf("FR | FILMS|movie", "FR | SERIES|series")
        val restricted = DispatcharrVod.restrict(VodSelection.ALL, allowed)
        assertFalse(restricted.all)
        assertEquals(setOf("MOVIE:FR | FILMS|movie", "SERIES:FR | SERIES|series"), restricted.keys)
        val chosen = VodSelection(all = false, keys = setOf("MOVIE:FR | FILMS|movie"))
        assertEquals(chosen, DispatcharrVod.restrict(chosen, allowed))
        assertEquals(VodSelection.ALL, DispatcharrVod.restrict(VodSelection.ALL, null))
        assertEquals("all", DispatcharrVod.groupsStamp(null))
        assertEquals(DispatcharrVod.groupsStamp(allowed), DispatcharrVod.groupsStamp(allowed.reversed().toSet()))
        assertNotEquals(DispatcharrVod.groupsStamp(allowed), DispatcharrVod.groupsStamp(setOf("FR | FILMS|movie")))
    }

    @Test
    fun `titles most watched on the server are parsed and shown in their order`() {
        val popular = TeleApi.popularTitles(JSONObject(
            """{"titles": [{"kind": "series", "id": 2}, {"kind": "movie", "id": "1"}, {"kind": "tv", "id": 3}, {"kind": "movie", "id": "x"}, {"kind": "movie", "id": 1}]}"""
        ))
        assertEquals(listOf("series" to "2", "movie" to "1"), popular)
        assertEquals(emptyList<Pair<String, String>>(), TeleApi.popularTitles(null))

        val titles = VodLibrary.build(listOf(entry(VodKind.MOVIE, "FR | FILMS")), emptyMap(), emptyMap())
        val key = titles.single().key
        val rows = VodRows.build(titles, emptyMap(), emptyList(), serverPopular = listOf("unknown", key))
        assertEquals(listOf(key), rows.single { it.kind == VodRow.Kind.SERVER_POPULAR }.titles.map { it.key })
        assertTrue(VodRows.build(titles, emptyMap(), emptyList()).none { it.kind == VodRow.Kind.SERVER_POPULAR })
        // Last of the kinds: the rows kept on the device store their position
        assertEquals(VodRow.Kind.SERVER_POPULAR, VodRow.Kind.entries.last())
    }
}
