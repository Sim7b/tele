package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator

class SecretBoxTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SecretBox({ key }, cbcTransformation = "AES/CBC/PKCS5Padding")

    @Test
    fun `encrypts values with a fresh IV and reads them back`() {
        val secret = """[{"apiKey":"s3cret","password":"p@ss"}]"""
        val first = box.encrypt(secret)
        val second = box.encrypt(secret)

        assertTrue(box.isEncrypted(first))
        assertFalse(first.contains("s3cret"))
        assertTrue(first != second)
        assertEquals(secret, box.decrypt(first))
        assertFalse(box.isEncrypted(secret))
    }

    @Test
    fun `rejects a tampered value`() {
        val encrypted = box.encrypt("secret")
        val tampered = encrypted.dropLast(2) + if (encrypted.endsWith("A=")) "B=" else "A="
        try {
            box.decrypt(tampered)
            fail("A tampered value must not decrypt")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `stream round trip`() {
        val text = "line 1\nhttp://provider/live/user/pass/1.ts\n".repeat(1_000)
        val output = ByteArrayOutputStream()
        box.encryptingStream(output).bufferedWriter().use { it.write(text) }
        val bytes = output.toByteArray()

        assertTrue(bytes.copyOfRange(0, SecretBox.STREAM_MAGIC.size).contentEquals(SecretBox.STREAM_MAGIC))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("user/pass"))
        val input = ByteArrayInputStream(bytes).apply { skip(SecretBox.STREAM_MAGIC.size.toLong()) }
        assertEquals(text, box.decryptingStream(input).bufferedReader().readText())
    }

    @Test
    fun `channel cache is encrypted and still reads a plain legacy cache`() {
        val file = folder.root.resolve("channels.jsonl")
        val channels = listOf(Channel(id = "a", name = "A", group = "", streamUrl = "http://provider/live/user/pass/1.ts"))

        ChannelCache(file).save("source", channels)
        assertEquals(channels, ChannelCache(file, box).load("source"))

        ChannelCache(file, box).save("source", channels)
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("user/pass"))
        assertEquals(channels, ChannelCache(file, box).load("source"))
    }
}
