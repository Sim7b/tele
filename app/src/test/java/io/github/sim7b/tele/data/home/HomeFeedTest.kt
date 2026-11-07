package io.github.sim7b.tele.data.home

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.EpgProgram
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.sport.SportEvent
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFeedTest {

    private val hour = 60 * 60_000L
    private val now = 1_000 * hour

    private fun channel(id: String, program: String? = null) = Channel(
        id = id, name = id.uppercase(), group = "TV", streamUrl = "http://example.test/$id",
        currentProgram = program?.let { EpgProgram(title = it, startTime = "20:00", endTime = "21:00") }
    )

    private fun title(key: String) = VodTitle(key, VodKind.MOVIE, key, 2024, null, null, null, emptyList(), null, null, null, null, emptyList())

    private fun event(id: String, start: Long, end: Long) = SportEvent(
        channel(id),
        GuideProgram(id = id, channelUuid = id, title = "Match $id", startTimeIso = "", endTimeIso = "", startMillis = start, endMillis = end)
    )

    @Test
    fun `up next puts the matches on air first, then titles and coming matches in turn, never past ones`() {
        val resume = listOf(title("dune") to VodProgress("dune", "dune", 1, 2, 3), title("bear") to VodProgress("bear", "bear", 1, 2, 2))
        val sport = listOf(
            event("later", now + 3 * hour, now + 5 * hour),
            event("onair", now - hour, now + hour),
            event("soon", now + hour, now + 2 * hour),
            event("past", now - 3 * hour, now - 2 * hour)
        )
        val keys = HomeFeed.upNext(resume, sport, now).map { it.key }
        assertEquals(
            listOf("ev:onair:${now - hour}", "vod:dune", "ev:soon:${now + hour}", "vod:bear", "ev:later:${now + 3 * hour}"),
            keys
        )
    }

    @Test
    fun `featured takes a program on air, a title, a match in turn, and never stays empty`() {
        val favorites = listOf(channel("tf1", "Koh-Lanta"), channel("m6"), channel("arte", "Le documentaire"))
        val upNext = HomeFeed.upNext(listOf(title("dune") to VodProgress("dune", "dune", 1, 2, 3)), listOf(event("c", now - hour, now + hour)), now)
        assertEquals(
            listOf("ch:tf1", "vod:dune", "ev:c:${now - hour}", "ch:arte"),
            HomeFeed.featured(favorites, upNext, favorites).map { it.key }
        )
        // Nothing on air nor started: the first favorite, else the first channel
        assertEquals(listOf("ch:m6"), HomeFeed.featured(listOf(channel("m6")), emptyList(), emptyList()).map { it.key })
        assertEquals(listOf("ch:x"), HomeFeed.featured(emptyList(), emptyList(), listOf(channel("x"))).map { it.key })
        assertEquals(emptyList<HomeItem>(), HomeFeed.featured(emptyList(), emptyList(), emptyList()))
    }
}
