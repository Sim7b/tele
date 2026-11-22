package io.github.sim7b.tele.data.sport

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportBoardTest {

    private val hour = 3_600_000L
    private val start = 1_795_000_000_000L

    private fun event(channel: String, title: String, at: Long) = SportEvent(
        Channel(id = channel, name = channel.uppercase(), group = "Sport", streamUrl = "http://example.test/$channel"),
        GuideProgram(id = "$channel$at", channelUuid = channel, title = title, startTimeIso = "", endTimeIso = "", startMillis = at, endMillis = at + 2 * hour)
    )

    @Test
    fun `the competitions of the followed teams, competitions and sports`() {
        assertEquals(
            listOf("football/france/ligue1", "motorsport/f1"),
            SportBoard.competitionsFor(listOf("@football/france/ligue1/paris-saint-germain", "@motorsport/f1", "@football/france/ligue1", "PSG"))
        )
        // A country followed whole: its competitions, at most MAX_COMPETITIONS in all
        val france = SportBoard.competitionsFor(listOf("@football/france"))
        assertEquals(listOf("football/france/ligue1", "football/france/ligue2", "football/france/cdf", "football/france/d1"), france)
        assertEquals(SportBoard.MAX_COMPETITIONS, SportBoard.competitionsFor(listOf("@football", "@rugby")).size)
        assertEquals(emptyList<String>(), SportBoard.competitionsFor(listOf("@unknown/topic", "Stade Toulousain")))
    }

    @Test
    fun `a followed team is found under the names of the sources`() {
        val teams = SportBoard.followedTeams(listOf("@football/france/ligue1/olympique-de-marseille", "@motorsport/f1"))
        assertTrue(SportBoard.isFollowed(teams, "Marseille"))
        assertTrue(SportBoard.isFollowed(teams, "Olympique de Marseille"))
        assertTrue(SportBoard.isFollowed(teams, "Lyon", "OM"))
        assertFalse(SportBoard.isFollowed(teams, "Olympique Lyonnais"))
        assertFalse(SportBoard.isFollowed(emptyList(), "Marseille"))
    }

    @Test
    fun `the answer of the plugin is read as untrusted`() {
        val json = JSONObject(
            """
            {"competitions": [
              {"topic": "football/france/ligue1", "name": "Ligue 1", "logo": "https://a.espncdn.com/l1.png",
               "events": [
                 {"id": "2", "start": ${start + 24 * hour}, "state": "pre", "home": {"name": "Lyon", "logo": "https://x/ol.png"}, "away": {"name": "AS Monaco", "logo": "javascript:alert(1)"}},
                 {"id": "1", "start": $start, "state": "in", "home": {"name": "PSG", "score": "1"}, "away": {"name": "OM", "score": "0"}},
                 {"id": "3", "start": 0},
                 {"id": "4", "start": ${start + hour}, "state": "post", "name": "Session", "session": "Race"}
               ],
               "standings": [{"name": "Table", "rows": [{"rank": 1, "name": "PSG", "points": "26", "record": "8-2-1"}, {"rank": 2}]}, {"rows": []}]},
              {"name": "No topic"},
              "broken"
            ]}
            """.trimIndent()
        )
        val competitions = SportBoard.fromJson(json)
        assertEquals(1, competitions.size)
        val ligue1 = competitions.single()
        assertEquals(listOf("1", "4", "2"), ligue1.fixtures.map { it.id })
        assertEquals(SportFixture.State.LIVE, ligue1.fixtures[0].state)
        assertEquals(SportFixture.State.FINISHED, ligue1.fixtures[1].state)
        assertEquals("Race", ligue1.fixtures[1].session)
        assertEquals("https://x/ol.png", ligue1.fixtures[2].home?.logo)
        assertNull(ligue1.fixtures[2].away?.logo)
        assertEquals(1, ligue1.tables.size)
        assertEquals(listOf("PSG"), ligue1.tables[0].rows.map { it.name })
        assertEquals(emptyList<SportCompetition>(), SportBoard.fromJson(JSONObject("{}")))
        assertEquals(emptyList<SportCompetition>(), SportBoard.fromJson(null))
    }

    @Test
    fun `a match is shown by the guide program naming one of its teams at its time`() {
        val match = SportFixture("1", start, SportFixture.State.UPCOMING, "Monaco at Lyon",
            SportSide("Lyon", "OL", "https://x/ol.png"), SportSide("AS Monaco", "ASM", "https://x/asm.png"))
        val lyon = event("canal", "Ligue 1 : Lyon - Monaco", start - 15 * 60_000L)
        val other = event("bein", "Ligue 1 : Lens - Nice", start)
        val later = event("dazn", "Lyon - Monaco (rediffusion)", start + 5 * hour)
        assertEquals(lyon, SportBoard.programFor(match, listOf(other, lyon, later)))
        assertNull(SportBoard.programFor(match, listOf(other, later)))
        // "AS" alone does not tell a team
        assertNull(SportBoard.programFor(match.copy(home = SportSide("AS Roma", null, null), away = SportSide("AS Saint-Étienne", null, null)), listOf(event("x", "AS Monaco - Lens", start))))
        // A race: the program at its time
        val race = SportFixture("r", start, SportFixture.State.UPCOMING, "Abu Dhabi Grand Prix", session = "Race")
        assertEquals(other, SportBoard.programFor(race, listOf(later, other)))
        // The logos of the match on the card of its program
        val ligue1 = SportCompetition("football/france/ligue1", "Ligue 1", null, listOf(match), emptyList())
        assertEquals(listOf("https://x/ol.png", "https://x/asm.png"), SportBoard.logosFor(lyon, listOf(ligue1)))
        assertEquals(emptyList<String>(), SportBoard.logosFor(later, listOf(ligue1)))
    }
}
