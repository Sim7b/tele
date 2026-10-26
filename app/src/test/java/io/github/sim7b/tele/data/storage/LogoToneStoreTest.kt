package io.github.sim7b.tele.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogoToneStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = folder.root.resolve("logo_tones")

    @Test
    fun `saves and reads back the tones`() {
        LogoToneStore(file).apply {
            put("http://logos.example/a.png", true)
            put("http://logos.example/b.png", false)
            save()
        }

        val reloaded = LogoToneStore(file).apply { load() }
        assertEquals(true, reloaded.get("http://logos.example/a.png"))
        assertEquals(false, reloaded.get("http://logos.example/b.png"))
    }

    @Test
    fun `keeps only the most recently used logos`() {
        val store = LogoToneStore(file, maxEntries = 2)
        store.put("a", true)
        store.put("b", true)
        store.get("a")
        store.put("c", true)

        assertNull(store.get("b"))
        assertEquals(true, store.get("a"))
        assertEquals(true, store.get("c"))
    }

    @Test
    fun `does not write when nothing changed`() {
        LogoToneStore(file).save()

        assertFalse(file.exists())
    }

    @Test
    fun `ignores malformed lines`() {
        file.writeText("1http://logos.example/a.png\nx\n\n0\n0http://logos.example/b.png\n")

        val store = LogoToneStore(file).apply { load() }
        assertEquals(true, store.get("http://logos.example/a.png"))
        assertEquals(false, store.get("http://logos.example/b.png"))
    }
}
