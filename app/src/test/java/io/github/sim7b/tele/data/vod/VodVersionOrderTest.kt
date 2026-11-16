package io.github.sim7b.tele.data.vod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VodVersionOrderTest {

    private fun file(
        url: String,
        resolution: String? = null,
        tags: List<String> = emptyList(),
        audio: List<String?> = emptyList(),
        subtitles: List<String> = emptyList(),
        hdr: Boolean = false
    ) = VodVersion(
        sourceId = "a", sourceName = "a", url = url, name = url, resolution = resolution, hdr = hdr, languageTags = tags,
        audio = audio.map { VodAudio(it, null, null) }, subtitles = subtitles
    )

    private val vo4k = file("vo-4k", "2160p", audio = listOf("eng"))
    private val vf1080 = file("vf-1080", "1080p", tags = listOf("VF"))
    private val vostfr4k = file("vostfr-4k", "2160p", tags = listOf("VOSTFR"))
    private val multi720 = file("multi-720", "720p", tags = listOf("MULTI"))
    private val vfHdr = file("vf-4k-hdr", "2160p", audio = listOf("fre"), hdr = true)

    @Test
    fun `the language of the TV first, then the quality`() {
        val sorted = VodVersionOrder.sort(listOf(vo4k, vostfr4k, multi720, vf1080, vfHdr), VersionOrder.LANGUAGE_FIRST, "fr")
        assertEquals(listOf("vf-4k-hdr", "vf-1080", "multi-720", "vostfr-4k", "vo-4k"), sorted.map { it.url })
        // An English TV: the English audio first
        assertEquals("vo-4k", VodVersionOrder.sort(listOf(vf1080, vo4k), VersionOrder.LANGUAGE_FIRST, "en").first().url)
    }

    @Test
    fun `the best quality first, or the order of the providers`() {
        val files = listOf(vf1080, vo4k, vfHdr)
        assertEquals(listOf("vf-4k-hdr", "vo-4k", "vf-1080"), VodVersionOrder.sort(files, VersionOrder.QUALITY_FIRST, "fr").map { it.url })
        assertEquals(files, VodVersionOrder.sort(files, VersionOrder.AS_LISTED, "fr"))
        // A theater copy is last whatever its resolution
        val cam = file("cam", "2160p", tags = listOf("VF")).copy(name = "Dune CAM")
        assertEquals("cam", VodVersionOrder.sort(listOf(cam, vf1080), VersionOrder.QUALITY_FIRST, "fr").last().url)
    }

    @Test
    fun `languages written in any form`() {
        assertEquals("fra", VodVersionOrder.iso3("fr"))
        assertEquals("fra", VodVersionOrder.iso3("fre"))
        assertEquals("fra", VodVersionOrder.iso3("FRA"))
        assertEquals("deu", VodVersionOrder.iso3("ger"))
        assertEquals("eng", VodVersionOrder.iso3("English"))
        assertEquals(null, VodVersionOrder.iso3(""))
        assertEquals(1, VodVersionOrder.languageScore(file("x", subtitles = listOf("fr")), "fr"))
    }

    @Test
    fun `the order is kept in the settings`() {
        val display = VodDisplay(versionOrder = VersionOrder.QUALITY_FIRST)
        assertEquals(VersionOrder.QUALITY_FIRST, VodDisplay.fromJson(display.toJson()).versionOrder)
        assertEquals(VersionOrder.LANGUAGE_FIRST, VodDisplay.fromJson(JSONObject("""{"versionOrder": "x"}""")).versionOrder)
    }

    @Test
    fun `a title from another country in its original version with subtitles`() {
        val files = listOf(vf1080, vostfr4k, vo4k)
        // A US series on a French TV, original version wanted: VOSTFR first, then the VO, the VF last
        val original = VodVersionOrder.sort(files, VersionOrder.LANGUAGE_FIRST, "fr", AudioPreference.ORIGINAL, "en")
        assertEquals(listOf("vostfr-4k", "vo-4k", "vf-1080"), original.map { it.url })
        // Dubbed wanted, or a French title: the French audio first
        assertEquals("vf-1080", VodVersionOrder.sort(files, VersionOrder.LANGUAGE_FIRST, "fr", AudioPreference.DUBBED, "en").first().url)
        assertEquals("vf-1080", VodVersionOrder.sort(files, VersionOrder.LANGUAGE_FIRST, "fr", AudioPreference.ORIGINAL, "fr").first().url)
        // Starting tracks
        assertEquals(DefaultTracks("en", "fr"), VodVersionOrder.defaultTracks(AudioPreference.ORIGINAL, "fr", "en"))
        assertEquals(DefaultTracks("fr", null), VodVersionOrder.defaultTracks(AudioPreference.ORIGINAL, "fr", null))
        assertEquals(DefaultTracks("fr", null), VodVersionOrder.defaultTracks(AudioPreference.DUBBED, "fr", "ko"))
        val display = VodDisplay(audioPreference = AudioPreference.ORIGINAL)
        assertEquals(AudioPreference.ORIGINAL, VodDisplay.fromJson(display.toJson()).audioPreference)
    }
}
