package io.github.sim7b.tele.data.search

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppIntentsTest {

    private fun channel(name: String, number: Int? = null) = Channel(id = name, name = name, group = "TV", streamUrl = "http://example.test/$name", number = number)
    private fun title(name: String, rating: Double? = null) =
        VodTitle(name, VodKind.MOVIE, name, 2020, null, null, null, emptyList(), rating, null, null, null, emptyList())

    @Test
    fun `a title link of the TV home opens its sheet, whatever its key`() {
        val key = "movie:Le Fabuleux Destin d'Amélie & co?"
        val link = AppIntents.titleLink(key)
        assertEquals(AppRequest.OpenTitle(key), AppIntents.parse(AppIntents.ACTION_VIEW, link, null))
        // Another app's link, or nothing to open
        assertNull(AppIntents.parse(AppIntents.ACTION_VIEW, "https://example.test/vod?key=x", null))
        assertNull(AppIntents.parse(AppIntents.ACTION_VIEW, "tele://vod", null))
        assertNull(AppIntents.parse(AppIntents.ACTION_VIEW, "not a uri %%", null))
        assertNull(AppIntents.parse("android.intent.action.MAIN", null, null))
    }

    @Test
    fun `a voice request plays what was said`() {
        assertEquals(AppRequest.Play("France 2"), AppIntents.parse(AppIntents.ACTION_PLAY_FROM_SEARCH, null, "  France 2 "))
        assertEquals(AppRequest.Play("Arte"), AppIntents.parse(AppIntents.ACTION_SEARCH, null, "Arte"))
        assertNull(AppIntents.parse(AppIntents.ACTION_PLAY_FROM_SEARCH, null, " "))
        assertEquals(200, (AppIntents.parse(AppIntents.ACTION_SEARCH, null, "x".repeat(500)) as AppRequest.Play).query.length)
    }

    @Test
    fun `the channel said is found by its name, its number, or the shortest name starting with it`() {
        val channels = listOf(channel("France 24"), channel("France 2 HD", 2), channel("TF1 FHD", 1), channel("Arte", 7))
        assertEquals("France 2 HD", AppIntents.channelFor("france 2", channels)?.name)
        assertEquals("TF1 FHD", AppIntents.channelFor("TF1", channels)?.name)
        assertEquals("Arte", AppIntents.channelFor("7", channels)?.name)
        assertEquals("France 2 HD", AppIntents.channelFor("France", channels)?.name)
        assertNull(AppIntents.channelFor("Canal", channels))
        assertNull(AppIntents.channelFor("", channels))
    }

    @Test
    fun `the title said is found exactly, else the best rated starting with it`() {
        val titles = listOf(title("Dune", 7.0), title("Dune : deuxième partie", 8.5), title("Alien", 8.0))
        assertEquals("Dune", AppIntents.titleFor("dune", titles)?.title)
        assertEquals("Alien", AppIntents.titleFor("ALIEN", titles)?.title)
        assertNull(AppIntents.titleFor("Matrix", titles))
    }
}
