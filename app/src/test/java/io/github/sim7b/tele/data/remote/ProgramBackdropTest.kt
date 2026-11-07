package io.github.sim7b.tele.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgramBackdropTest {

    private val search = JSONObject(
        """{"results":[
            {"media_type":"person","name":"Koh-Lanta","backdrop_path":"/person.jpg"},
            {"media_type":"tv","name":"Koh Lanta - Autre","backdrop_path":"/other.jpg"},
            {"media_type":"tv","name":"Koh-Lanta","original_name":"Koh-Lanta","backdrop_path":"/koh.jpg"},
            {"media_type":"movie","title":"Dune","backdrop_path":null}
        ]}"""
    )

    @Test
    fun `the large backdrop of the movie or series of that very name`() {
        assertEquals("https://image.tmdb.org/t/p/w1280/koh.jpg", ProgramPosterRepository.tmdbBackdrop(search, "Koh-Lanta"))
        // Found, but without a backdrop; or not found: none
        assertNull(ProgramPosterRepository.tmdbBackdrop(search, "Dune"))
        assertNull(ProgramPosterRepository.tmdbBackdrop(search, "Envoyé spécial"))
        assertNull(ProgramPosterRepository.tmdbBackdrop(JSONObject("{}"), "Koh-Lanta"))
    }
}
