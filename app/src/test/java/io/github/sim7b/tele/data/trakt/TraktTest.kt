package io.github.sim7b.tele.data.trakt

import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodTitle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktTest {

    private val now = 1_000_000L

    @Test
    fun `the device code and the session are read, with safe bounds`() {
        val code = Trakt.parseDeviceCode(
            JSONObject("""{"device_code":"d","user_code":"AB12CD","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}"""),
            now
        )
        assertEquals("AB12CD", code.userCode)
        assertEquals(now + 600_000, code.expiresAt)
        // A strange address is not shown: the usual one is
        val odd = Trakt.parseDeviceCode(JSONObject("""{"device_code":"d","user_code":"X","verification_url":"http://evil.test","interval":0}"""), now)
        assertEquals("https://trakt.tv/activate", odd.verificationUrl)
        assertEquals(1, odd.intervalSeconds)

        val session = Trakt.parseSession(JSONObject("""{"access_token":"a","refresh_token":"r","expires_in":7776000}"""), now)
        assertEquals(TraktSession("a", "r", now + 7_776_000_000L), session)
        assertEquals(session, TraktSession.fromJson(session.toJson()))
        assertNull(TraktSession.fromJson(JSONObject("{}")))
    }

    @Test
    fun `the watchlist is read and matched to the library`() {
        val items = Trakt.parseWatchlist(
            JSONArray(
                """[
                {"type":"movie","movie":{"title":"Dune","ids":{"tmdb":438631,"imdb":"tt1160419"}}},
                {"type":"show","show":{"title":"The Bear","ids":{"tmdb":136315,"imdb":"tt14452776"}}},
                {"type":"movie","movie":{"title":"Unknown","ids":{}}},
                "broken"
                ]"""
            )
        )
        assertEquals(listOf("Dune", "The Bear", "Unknown"), items.map { it.title })
        fun title(key: String, kind: VodKind, tmdb: String?, imdb: String?) =
            VodTitle(key, kind, key, 2024, null, null, null, emptyList(), null, tmdb, imdb, null, emptyList())
        val library = listOf(
            title("bear", VodKind.SERIES, null, "tt14452776"),
            title("dune", VodKind.MOVIE, "438631", null),
            // Same TMDB number, other kind: not the series
            title("other", VodKind.MOVIE, "136315", null)
        )
        assertEquals(listOf("dune", "bear"), TraktMatch.titles(items, library).map { it.key })
    }

    @Test
    fun `a movie and an episode watched go to the history by their ids`() {
        val movie = Trakt.historyBody(TraktWatched("438631", "tt1160419", watchedAt = 0))
        assertEquals(438631L, movie.getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getLong("tmdb"))
        assertEquals("1970-01-01T00:00:00Z", movie.getJSONArray("movies").getJSONObject(0).getString("watched_at"))

        val episode = Trakt.historyBody(TraktWatched("136315", null, season = 2, episode = 5, watchedAt = 0))
        val show = episode.getJSONArray("shows").getJSONObject(0)
        assertEquals(136315L, show.getJSONObject("ids").getLong("tmdb"))
        val season = show.getJSONArray("seasons").getJSONObject(0)
        assertEquals(2, season.getInt("number"))
        assertEquals(5, season.getJSONArray("episodes").getJSONObject(0).getInt("number"))
    }
}
