package io.github.sim7b.tele.data.sport

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportCatalogTest {

    private val hour = 60 * 60_000L
    private val now = 1_000 * hour

    @Test
    fun `every topic has its own id, and is found back from its entry`() {
        val ids = ArrayList<String>()
        fun walk(topic: SportTopic) {
            ids += topic.id
            topic.children.forEach(::walk)
        }
        SportCatalog.topics.forEach(::walk)
        assertEquals(ids.size, ids.toSet().size)
        val rennes = SportCatalog.find("football/france/ligue1/stade-rennais")!!
        assertEquals(rennes, SportCatalog.topicOf(SportCatalog.entry(rennes)))
        // Kept whole when cleaned, though longer than a typed word
        val psg = SportCatalog.entry(SportCatalog.find("football/france/ligue1/paris-saint-germain")!!)
        assertEquals(listOf(psg, "Biathlon"), SportEvents.clean("$psg\nBiathlon"))
    }

    @Test
    fun `a topic is searched by all its names, and holds its teams`() {
        assertTrue("SRFC" in SportCatalog.keywords(listOf("@football/france/ligue1/stade-rennais")))
        assertTrue("Formula 1" in SportCatalog.keywords(listOf("@motorsport/f1")))
        // Ligue 1 holds its clubs; typed words stay as they are
        val ligue1 = SportCatalog.keywords(listOf("@football/france/ligue1", "Biathlon"))
        assertTrue("PSG" in ligue1 && "Rennes" in ligue1 && "Biathlon" in ligue1)
    }

    @Test
    fun `the guide programs of a followed topic are found by any of its names`() {
        val channel = Channel(id = "c", name = "Canal+", group = "Sport", streamUrl = "http://example.test/c")
        fun program(title: String, start: Long) =
            GuideProgram(id = title, channelUuid = "c", title = title, startTimeIso = "", endTimeIso = "", startMillis = start, endMillis = start + hour)
        val schedules = mapOf(
            "c" to listOf(
                program("Rennes - Nantes", now + hour),
                program("F1 : Grand Prix de Monaco", now + 2 * hour),
                program("Journal", now + 3 * hour)
            )
        )
        val found = SportEvents.find(listOf("@football/france/ligue1/stade-rennais", "@motorsport/f1"), listOf(channel), schedules, now)
        assertEquals(listOf("Rennes - Nantes", "F1 : Grand Prix de Monaco"), found.map { it.program.title })
    }
}
