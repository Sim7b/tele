package io.github.sim7b.tele.ui.vod

import io.github.sim7b.tele.data.vod.VodDetails
import io.github.sim7b.tele.data.vod.VodEpisode
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodSeason
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.data.vod.VodVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodFileChoiceTest {

    private fun file(url: String, source: String = "a", resolution: String? = null, languages: List<String> = emptyList()) =
        VodVersion(sourceId = source, sourceName = source, url = url, name = url, resolution = resolution, languageTags = languages)

    private val series = VodTitle("s", VodKind.SERIES, "Show", 2024, null, null, null, emptyList(), null, null, null, null, emptyList())

    @Test
    fun `the file chosen for an episode picks the same kind for the others`() {
        val chosen = file("e1-4k-vf", resolution = "2160p", languages = listOf("FR"))
        val files = listOf(file("e2-hd"), file("e2-4k-vo", resolution = "2160p", languages = listOf("EN")), file("e2-4k-vf", resolution = "2160p", languages = listOf("FR")))
        assertEquals("e2-4k-vf", matchingVersion(files, chosen)?.url)
        // Same quality, other languages: still that quality
        assertEquals("e2-4k-vo", matchingVersion(files.take(2), chosen)?.url)
        // Another source only, or nothing chosen: the first file
        assertNull(matchingVersion(listOf(file("x", source = "b", resolution = "2160p")), chosen))
        assertNull(matchingVersion(files, null))
    }

    @Test
    fun `a request keeps every file, and the next episode follows the file played`() {
        fun episode(number: Int) = VodEpisode(
            key = "1-$number", season = 1, number = number, title = "",
            versions = listOf(file("e$number-hd", resolution = "1080p"), file("e$number-4k", resolution = "2160p"))
        )
        val details = VodDetails(title = series, seasons = listOf(VodSeason(1, listOf(episode(1), episode(2)))))
        val first = details.seasons[0].episodes[0]
        val request = playRequest(series, "s", first, first.versions, first.versions[1], 0)!!
        assertEquals("e1-4k", request.url)
        assertEquals(listOf("e1-hd"), request.fallbacks)
        assertEquals(2, request.files.size)
        assertEquals("e2-4k", nextPlayRequest(request, details, emptyMap())?.url)
    }
}
