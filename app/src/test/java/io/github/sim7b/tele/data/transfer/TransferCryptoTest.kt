package io.github.sim7b.tele.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransferCryptoTest {

    private val config = """{"sources":[{"apiKey":"s3cret-key","password":"p@ss"}]}"""

    @Test
    fun `round trip with the code, typed with spaces`() {
        val sealed = TransferCrypto.seal(config, "12345678")
        assertFalse(sealed.contains("s3cret"))
        assertEquals(config, TransferCrypto.open(sealed, "1234 5678"))
    }

    @Test
    fun `a wrong code does not decrypt`() {
        val sealed = TransferCrypto.seal(config, "12345678")
        try {
            TransferCrypto.open(sealed, "12345679")
            fail("A wrong code must not decrypt")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `codes have eight digits`() {
        val code = TransferCrypto.newCode()
        assertEquals(TransferCrypto.CODE_LENGTH, code.length)
        assertTrue(code.all { it.isDigit() })
    }
}
