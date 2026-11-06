package io.github.sim7b.tele.data.vod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerKeyTest {

    private fun videos(vararg items: String) = JSONObject("""{"results": [${items.joinToString(",")}]}""")

    private fun video(key: String, type: String = "Trailer", site: String = "YouTube", language: String = "en", official: Boolean = false) =
        """{"key": "$key", "type": "$type", "site": "$site", "iso_639_1": "$language", "official": $official}"""

    @Test
    fun `a trailer in the language of the TV comes first, official ones first`() {
        val json = videos(
            video("teaserFR01", type = "Teaser", language = "fr"),
            video("trailerEN1", language = "en", official = true),
            video("trailerFR1", language = "fr"),
            video("trailerFR2", language = "fr", official = true)
        )
        assertEquals("trailerFR2", VodMetadata.trailerKey(json, "fr"))
        assertEquals("trailerEN1", VodMetadata.trailerKey(json, "en"))
    }

    @Test
    fun `a teaser when there is no trailer, nothing from other sites or with an odd id`() {
        assertEquals("teaser0001", VodMetadata.trailerKey(videos(video("teaser0001", type = "Teaser")), "fr"))
        assertNull(VodMetadata.trailerKey(videos(video("vimeo00001", site = "Vimeo")), "fr"))
        assertNull(VodMetadata.trailerKey(videos(video("../../x?y=1")), "fr"))
        assertNull(VodMetadata.trailerKey(videos(video("featurette", type = "Featurette")), "fr"))
        assertNull(VodMetadata.trailerKey(null, "fr"))
        assertNull(VodMetadata.trailerKey(JSONObject("{}"), "fr"))
    }
}
