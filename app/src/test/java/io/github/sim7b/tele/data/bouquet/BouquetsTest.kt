package io.github.sim7b.tele.data.bouquet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BouquetsTest {

    private val france = Bouquet.france(Locale.FRANCE)

    @Test
    fun `free-to-air numbers follow the Arcom numbering of June 2025`() {
        val expected = mapOf(
            "France 4" to 4, "LCP" to 8, "Gulli" to 12, "BFMTV" to 13, "CNews" to 14, "LCI" to 15,
            "franceinfo" to 16, "T18" to 18, "Novo19" to 19, "RMC Life" to 25, "Paris Première" to 26
        )
        expected.forEach { (name, number) -> assertEquals(name, number, france.match(name)?.number) }
        assertEquals("RMC Life", france.match("Chérie 25 HD")?.name)
        assertNull(france.match("Canal+")?.number)
        assertEquals(BouquetCategory.CINEMA, france.match("Canal+")?.category)
    }

    @Test
    fun `numbers are unique in the French bouquet`() {
        val numbers = FranceBouquet.channels.mapNotNull { it.number }
        assertEquals(numbers.size, numbers.toSet().size)
    }

    @Test
    fun `neighbor bouquets know their channels and the French ones, without numbers`() {
        val belgium = requireNotNull(Bouquet.of(BouquetPreference.BELGIUM, Locale.FRANCE))
        assertEquals("La Une", belgium.match("BE | La Une HD")?.name)
        assertEquals(BouquetCategory.BELGIUM, belgium.match("RTL-TVI")?.category)
        assertEquals("TF1", belgium.match("FR: TF1 FHD")?.name)
        assertEquals(BouquetCategory.FRANCE, belgium.match("TF1")?.category)
        assertNull(belgium.match("TF1")?.number)

        val switzerland = requireNotNull(Bouquet.of(BouquetPreference.SWITZERLAND, Locale.FRANCE))
        assertEquals("RTS 1", switzerland.match("CH | RTS Un")?.name)
        assertEquals("SRF zwei", switzerland.match("SRF 2 HD")?.name)

        val canada = requireNotNull(Bouquet.of(BouquetPreference.CANADA, Locale.FRANCE))
        assertEquals("Télé-Québec", canada.match("CA | Tele Quebec HD")?.name)
        assertEquals(BouquetCategory.SPORT, canada.match("TVA Sports")?.category)
        assertEquals("ICI RDI", canada.match("RDI")?.name)

        assertNull(Bouquet.of(BouquetPreference.NONE, Locale.FRANCE))
    }

    @Test
    fun `country categories never catch provider categories by an empty key`() {
        val belgium = requireNotNull(Bouquet.of(BouquetPreference.BELGIUM, Locale.FRANCE))
        assertNull(belgium.categoryOf("Belgique"))
        assertTrue(BouquetPreference.entries.all { it == BouquetPreference.NONE || Bouquet.of(it, Locale.FRANCE) != null })
    }
}
