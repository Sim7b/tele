package io.github.sim7b.tele.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import javax.crypto.AEADBadTagException

class BackupFileTest {

    private val config = """{"sources":[{"apiKey":"s3cret-key"}],"vod":{"myList":["a"]}}"""

    @Test
    fun `a backup is read back with its code only`() {
        val file = BackupFile.seal(config, "12345678")
        assertFalse(file.contains("s3cret"))
        assertEquals("a", BackupFile.open(file, "1234 5678").getJSONObject("vod").getJSONArray("myList").getString(0))
        try {
            BackupFile.open(file, "87654321")
            fail("A wrong code is expected to fail")
        } catch (_: AEADBadTagException) {
        }
    }

    @Test
    fun `a file that is not a backup, or too large, is refused`() {
        for (content in listOf("not json", "{}", TransferCrypto.seal(config, "12345678"))) {
            try {
                BackupFile.open(content, "12345678")
                fail("Not a backup: $content")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            BackupFile.read(ByteArray(BackupFile.MAX_BYTES + 10).inputStream())
            fail("Too large")
        } catch (_: IOException) {
        }
        assertEquals("abc", BackupFile.read("abc".byteInputStream()))
    }
}
