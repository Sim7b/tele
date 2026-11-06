package io.github.sim7b.tele.ui.components

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.ChannelList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GuideActionsInsetTest {

    @Test
    fun `the corner channel action keeps every other action of the channel menu`() {
        val toggled = mutableListOf<String>()
        val lists = listOf(ChannelList("l", "Sport", emptyList()))
        val base = GuideActions(
            toggleFavorite = { toggled += "favorite:" + it.id },
            hideChannel = { toggled += "hide:" + it.id },
            savedLists = lists,
            automaticStreamDescription = { "auto" }
        )
        var inset: Channel? = null
        val withInset = base.withInset { inset = it }
        val channel = Channel(id = "1", name = "One", group = "News", streamUrl = "http://example.test/1")

        withInset.toggleFavorite(channel)
        withInset.hideChannel(channel)
        withInset.watchInInset!!(channel)

        assertEquals(listOf("favorite:1", "hide:1"), toggled)
        assertSame(lists, withInset.savedLists)
        assertEquals("auto", withInset.automaticStreamDescription(channel))
        assertEquals(channel, inset)
        assertNull(base.watchInInset)
    }
}
