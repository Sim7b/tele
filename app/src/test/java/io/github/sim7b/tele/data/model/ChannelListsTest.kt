package io.github.sim7b.tele.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelListsTest {

    private fun channel(id: String, members: List<String> = emptyList()) =
        Channel(id = id, name = id, group = "", streamUrl = "http://example.test/$id", memberIds = members)

    @Test
    fun `lists are made, filled, renamed and deleted`() {
        var lists = ChannelLists.create(emptyList(), "  Sport ", channelId = "a", id = "1")
        assertEquals(listOf(ChannelList("1", "Sport", listOf("a"))), lists)
        lists = ChannelLists.toggle(lists, "1", "b")
        assertEquals(listOf("a", "b"), lists.single().channelIds)
        lists = ChannelLists.toggle(lists, "1", "a")
        assertEquals(listOf("b"), lists.single().channelIds)
        assertEquals("Kids", ChannelLists.rename(lists, "1", "Kids").single().name)
        // No blank names
        assertSame(lists, ChannelLists.create(lists, "   "))
        assertEquals(lists, ChannelLists.rename(lists, "1", " "))
        assertTrue(ChannelLists.delete(lists, "1").isEmpty())
    }

    @Test
    fun `channels are found by any of their versions, in the order added`() {
        val lists = listOf(ChannelList("1", "Sport", listOf("fhd", "c", "gone")))
        val views = ChannelLists.resolve(lists, listOf(channel("tf1", members = listOf("hd", "fhd")), channel("c")))
        assertEquals(listOf("tf1", "c"), views.single().channels.map { it.id })
    }

    @Test
    fun `lists survive a round trip and broken ones are skipped`() {
        val lists = listOf(ChannelList("1", "Sport", listOf("a", "b")), ChannelList("2", "Kids", emptyList()))
        assertEquals(lists, ChannelLists.fromJson(ChannelLists.toJson(lists).toString()))
        assertEquals(listOf(ChannelList("1", "A", emptyList())), ChannelLists.fromJson("""[{"id":"1","name":"A"},{"name":"no id"},3]"""))
        assertTrue(ChannelLists.fromJson("{broken").isEmpty())
        assertEquals(listOf("b"), ChannelLists.without(lists) { it == "a" }.first().channelIds)
    }
}
