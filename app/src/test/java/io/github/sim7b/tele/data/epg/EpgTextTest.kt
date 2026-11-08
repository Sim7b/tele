package io.github.sim7b.tele.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgTextTest {

    @Test
    fun `texts escaped twice by the provider are made readable`() {
        assertEquals(
            "Familles, évasion... \" Un jour, un doc week-end \" propose l'été",
            EpgText.clean("Familles, évasion... \\\" Un jour, un doc week-end \\\" propose l\'été")
        )
        assertEquals("Ligne 1 Ligne 2", EpgText.clean("Ligne 1\nLigne 2"))
        assertEquals("Déjà propre", EpgText.clean("Déjà propre"))
        assertNull(EpgText.clean("   "))
        assertNull(EpgText.clean(null))
    }
}
