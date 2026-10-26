package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.parser.M3uEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uClientTest {

    private fun entry(name: String, tvgId: String? = null) =
        M3uEntry(name = name, url = "http://provider.example/$name", tvgId = tvgId, tvgName = null, logo = null, group = null, number = null)

    @Test
    fun `keeps the historical ids, numbered when duplicated`() {
        val channels = M3uClient.toChannels(listOf(entry("TF1 HD", "TF1.fr"), entry("TF1 FHD", "TF1.fr"), entry("M6")), "m3u-p-")

        val tf1 = "m3u-p-" + Integer.toHexString("tf1.fr".hashCode())
        assertEquals(listOf(tf1, "$tf1-2", "m3u-p-" + Integer.toHexString("m6".hashCode())), channels.map { it.id })
    }

    @Test
    fun `ids stay unique when two keys share the same hash`() {
        // "az" and "b[" have the same String.hashCode()
        assertEquals("az".hashCode(), "b[".hashCode())

        val ids = M3uClient.toChannels(listOf(entry("az"), entry("b["), entry("az")), "m3u-p-").map { it.id }

        assertEquals(3, ids.toSet().size)
    }
}
