package io.github.sim7b.tele.data.bouquet

import io.github.sim7b.tele.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChannelGroupingTest {

    private val names = ChannelNames(ChannelNames.homeTokens(Locale.FRANCE))
    private val bouquet = Bouquet.france(Locale.FRANCE)

    @Test
    fun `channel keys ignore quality, country prefixes and decorations, never digits`() {
        listOf("TF1 FHD", "FR | TF1 HEVC", "TF1 ᴴᴰ", "|FR| TF1 1080p", "TF1 (720p) [Not 24/7]", "FR: TF1 50 FPS", "TF1 4K")
            .forEach { assertEquals(it, "tf1", names.channelKey(it)) }
        assertEquals("canalplus", names.channelKey("Canal Plus"))
        assertEquals("canalplus", names.channelKey("CANAL+ HD"))
        assertTrue(names.channelKey("France 2") != names.channelKey("France 3"))
        assertEquals("TF1", names.channelDisplay("FR | TF1 FHD"))
        assertEquals("TF1 FHD", names.versionDisplay("FR | TF1 FHD"))
    }

    @Test
    fun `category keys ignore country words and plurals`() {
        listOf("Sport", "FR | Sport", "France Sport", "Sports", "FR| SPORTS HD").forEach { assertEquals(it, "sport", names.categoryKey(it)) }
        assertEquals("Sport", names.categoryDisplay("FR | Sport"))
    }

    @Test
    fun `recognizes bouquet channels, exactly or approximately`() {
        assertEquals("TF1", bouquet.match("FR | TF1 FHD")?.name)
        assertEquals(1, bouquet.match("TF1 ᴴᴰ")?.number)
        assertEquals("Canal+", bouquet.match("CANAL PLUS HD")?.name)
        assertEquals("France 2", bouquet.match("France 2 UHD")?.name)
        assertEquals("Ciné+ Frisson", bouquet.match("CINE+ FRISSON FHD")?.name)
        assertEquals("BFMTV", bouquet.match("BFM TV")?.name)
        // Close spelling (one letter off), same digits
        assertEquals("Eurosport 2", bouquet.match("Eurosprt 2")?.name)
        // Never across numbers nor for unrelated or regional channels
        assertNull(bouquet.match("France 22"))
        assertNull(bouquet.match("France 3 Alsace"))
        assertNull(bouquet.match("Alpe d'Huez TV"))
        assertEquals(BouquetCategory.SPORT, bouquet.categoryOf("FR| SPORTS"))
        assertEquals(BouquetCategory.DOCUMENTARY, bouquet.categoryOf("Documentaires"))
        assertEquals(BouquetCategory.NEWS, bouquet.categoryOf("News"))
        assertEquals(BouquetCategory.NEWS, bouquet.categoryOf("FR | Infos"))
        assertEquals(BouquetCategory.KIDS, bouquet.categoryOf("Animation"))
        assertNull(bouquet.categoryOf("General"))
    }

    private fun channel(id: String, name: String, group: String, source: String = "a", number: Int? = null) =
        Channel(id = id, name = name, group = group, streamUrl = "http://x/$id", sourceId = source, number = number)

    private fun label(category: BouquetCategory) = category.name

    @Test
    fun `merges the versions of a channel and uses the bouquet`() {
        val channels = listOf(
            channel("1", "TF1 HD", "FR| GENERALISTE", number = 101),
            channel("2", "Alpe d'Huez TV", "FR | Sport"),
            channel("3", "TF1 FHD", "FR| HD"),
            channel("4", "TF1 HEVC", "Other", source = "b"),
            channel("5", "Ma Chaîne Locale", "France Sports")
        )
        val result = ChannelGrouping(bouquet, names).build(channels, mergeAcrossSources = true, favoriteIds = setOf("3"), sourceNames = mapOf("a" to "Neo", "b" to "Cheaply"), categoryLabel = ::label)

        assertEquals(listOf("TF1", "Alpe d'Huez TV", "Ma Chaîne Locale"), result.map { it.name })
        val tf1 = result.first()
        // Favorite version keeps its id; every version becomes a stream; bouquet number and category
        assertEquals("3", tf1.id)
        assertEquals(listOf("1", "3", "4"), tf1.memberIds)
        assertEquals(listOf("TF1 HD", "TF1 FHD", "TF1 HEVC"), tf1.availableStreams.map { it.name })
        assertEquals(listOf(null, null, "Cheaply").map { it ?: "Neo" }.drop(2), tf1.availableStreams.drop(2).map { it.provider })
        assertEquals(1, tf1.number)
        assertEquals("TNT", tf1.group)
        // "FR | Sport" and "France Sports" are the bouquet Sport category
        assertEquals("SPORT", result[1].group)
        assertEquals("SPORT", result[2].group)
    }

    @Test
    fun `separate mode only merges within a source`() {
        val channels = listOf(channel("1", "TF1 HD", "G"), channel("2", "TF1 FHD", "G", source = "b"))
        val result = ChannelGrouping(bouquet, names).build(channels, mergeAcrossSources = false, favoriteIds = emptySet(), sourceNames = emptyMap(), categoryLabel = ::label)
        assertEquals(2, result.size)
        assertEquals(listOf("TF1", "TF1"), result.map { it.name })
    }

    @Test
    fun `without bouquet, close provider categories still merge`() {
        val channels = listOf(channel("1", "A", "FR | Cinéma"), channel("2", "B", "Cinema"), channel("3", "C", "FR| CINEMAS"))
        val result = ChannelGrouping(null, names).build(channels, mergeAcrossSources = true, favoriteIds = emptySet(), sourceNames = emptyMap(), categoryLabel = ::label)
        assertEquals(setOf("Cinéma"), result.map { it.group }.toSet())
    }
}
