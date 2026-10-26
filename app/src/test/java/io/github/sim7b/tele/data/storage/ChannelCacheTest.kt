package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.EpgProgram
import io.github.sim7b.tele.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache() = ChannelCache(folder.root.resolve("channels.jsonl"))

    private val program = EpgProgram(title = "News", startTime = "20:00", endTime = "21:00", startMillis = 1_000, endMillis = 5_000)

    private val channels = listOf(
        Channel(
            id = "a",
            name = "Channel A",
            group = "News",
            streamUrl = "http://provider.example/a.ts",
            tvgId = "a.fr",
            currentProgram = program,
            availableStreams = listOf(StreamSource(name = "HLS", url = "http://provider.example/a.m3u8", id = "xtream-m3u8", qualityTags = listOf("HLS"))),
            number = 1
        ),
        Channel(id = "b", name = "Channel B", group = "Sports", streamUrl = "http://provider.example/b.ts")
    )

    @Test
    fun `reads back the saved channels of the same source`() {
        val cache = cache()
        cache.save("source", channels)

        assertEquals(channels.map { it.copy(currentProgram = it.currentProgram?.copy(progress = 0.5f)) }, cache.load("source", now = 3_000))
    }

    @Test
    fun `ignores the cache of another source`() {
        val cache = cache()
        cache.save("source", channels)

        assertTrue(cache.load("other").isEmpty())
    }

    @Test
    fun `drops programs that have ended`() {
        val cache = cache()
        cache.save("source", channels)

        assertNull(cache.load("source", now = 6_000).first().currentProgram)
    }

    @Test
    fun `is empty without a file`() {
        assertTrue(cache().load("source").isEmpty())
    }
}
