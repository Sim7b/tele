package io.github.sim7b.tele.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgramBackdropTest {

    private val search = JSONObject(
        """{"results":[
            {"media_type":"person","id":1,"name":"Koh-Lanta","backdrop_path":"/person.jpg"},
            {"media_type":"tv","id":2,"name":"Koh Lanta - Autre","backdrop_path":"/other.jpg"},
            {"media_type":"tv","id":67997,"name":"Koh-Lanta","original_name":"Koh-Lanta","backdrop_path":"/koh.jpg"},
            {"media_type":"movie","id":693134,"title":"Dune","backdrop_path":null}
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

    @Test
    fun `the title found keeps its id, for its logo`() {
        assertEquals(ProgramPosterRepository.TmdbMatch("movie", 693134, null), ProgramPosterRepository.tmdbFind(JSONObject("""{"results":[{"media_type":"movie","id":693134,"title":"Dune"}]}"""), "Dune"))
        assertNull(ProgramPosterRepository.tmdbFind(JSONObject("""{"results":[{"media_type":"movie","title":"Dune"}]}"""), "Dune"))
    }

    @Test
    fun `the logo in the language of the device, else English, else without text, PNG first`() {
        val images = JSONObject(
            """{"logos":[
                {"iso_639_1":"de","file_path":"/de.png"},
                {"iso_639_1":"en","file_path":"/en.svg"},
                {"iso_639_1":"en","file_path":"/en.png"},
                {"iso_639_1":null,"file_path":"/none.png"},
                {"iso_639_1":"fr","file_path":"/fr.png"}
            ]}"""
        )
        assertEquals("https://image.tmdb.org/t/p/w500/fr.png", ProgramPosterRepository.tmdbLogo(images, "fr"))
        assertEquals("https://image.tmdb.org/t/p/w500/en.png", ProgramPosterRepository.tmdbLogo(images, "it"))
        assertEquals("https://image.tmdb.org/t/p/w500/none.png", ProgramPosterRepository.tmdbLogo(JSONObject("""{"logos":[{"iso_639_1":"de","file_path":"/de.png"},{"file_path":"/none.png"}]}"""), "fr"))
        // Only another language: none rather than a foreign title
        assertNull(ProgramPosterRepository.tmdbLogo(JSONObject("""{"logos":[{"iso_639_1":"de","file_path":"/de.png"}]}"""), "fr"))
    }
}
