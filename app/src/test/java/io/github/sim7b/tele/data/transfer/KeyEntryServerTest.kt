package io.github.sim7b.tele.data.transfer

import io.github.sim7b.tele.ui.components.qrModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyEntryServerTest {

    private val page = KeyEntryPage(
        title = "Clé <OpenSubtitles>",
        label = "Clé API",
        send = "Envoyer",
        done = "Clé envoyée",
        invalid = "Clé invalide",
        getKey = "Obtenir une clé",
        providerUrl = "https://www.opensubtitles.com/consumers"
    )

    @Test
    fun `the page answers only at its random path, and takes a valid key once posted`() {
        val server = KeyEntryServer(page) {}
        try {
            assertEquals(404, server.respond("GET", "/", "").status)
            assertEquals(404, server.respond("POST", "/guess", "key=abcdefghijklmnop1234").status)

            val form = server.respond("GET", "/${server.token}", "")
            assertEquals(200, form.status)
            assertTrue(form.html.contains("action=\"/${server.token}\""))
            // Texts are escaped
            assertTrue(form.html.contains("Clé &lt;OpenSubtitles&gt;"))
            assertNull(form.key)

            val invalid = server.respond("POST", "/${server.token}", "key=%3Cscript%3E")
            assertNull(invalid.key)
            assertTrue(invalid.html.contains("Clé invalide"))

            val valid = server.respond("POST", "/${server.token}", "key=+0123456789abcdefFAKEkeyFORtests00%0A")
            assertEquals("0123456789abcdefFAKEkeyFORtests00", valid.key)
            assertTrue(server.url("192.168.0.10").startsWith("http://192.168.0.10:${server.port}/"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `keys and forms are read strictly`() {
        assertTrue(KeyEntryServer.isValidKey("0123456789abcdef0123456789abcdef"))
        assertFalse(KeyEntryServer.isValidKey("short"))
        assertFalse(KeyEntryServer.isValidKey("with space inside the key 123"))
        assertEquals(mapOf("key" to "a b", "x" to ""), KeyEntryServer.parseForm("key=a+b&x=&=lost"))
        assertEquals(emptyMap<String, String>(), KeyEntryServer.parseForm("key=%zz"))
    }

    @Test
    fun `QR codes are square and keep their finder patterns`() {
        val modules = qrModules("http://192.168.0.182:41234/abcdefghijklmnopqrstuv")
        assertTrue(modules.size >= 21)
        assertTrue(modules.all { it.size == modules.size })
        // Top left finder pattern: dark border, light ring, dark centre
        assertTrue(modules[0][0] && modules[0][6] && modules[6][0])
        assertFalse(modules[1][1])
        assertTrue(modules[3][3])
    }
}
