package io.github.sim7b.tele.data.launcher

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeChannelItemTest {

    @Test
    fun `favorites shown outside the app with what they show now, not the locked ones`() {
        val news = Channel("1", "Info 24", "News", logoUrl = "https://logo/1.png", streamUrl = "s1",
            currentProgram = EpgProgram("Le Journal", startTime = "20:00", endTime = "20:45", posterUrl = "https://art/1.jpg"))
        val adult = Channel("2", "Adult", "Adults", logoUrl = "https://logo/2.png", streamUrl = "s2")
        val plain = Channel("3", "Musique Plus", "Music", logoUrl = "https://logo/3.png", streamUrl = "s3")

        val items = HomeChannelItem.of(listOf(news, adult, plain), lockedGroups = setOf("Adults"))

        assertEquals(listOf("1", "3"), items.map { it.channelId })
        assertEquals(HomeChannelItem("1", "Info 24", "Le Journal · 20:00 – 20:45", "https://art/1.jpg", logo = false), items[0])
        assertEquals(HomeChannelItem("3", "Musique Plus", null, "https://logo/3.png", logo = true), items[1])
    }
}
