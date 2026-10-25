package io.github.sim7b.tele.data.parser

import io.github.sim7b.tele.data.UserFacingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class ParserLimitsTest {

    private fun lines(text: String, maxLength: Int = 100): List<String> {
        val reader = BoundedLineReader(StringReader(text), maxLength)
        return generateSequence { reader.readLine() }.toList()
    }

    @Test
    fun `reads lines ending with LF, CRLF or CR`() {
        assertEquals(listOf("a", "b", "c", "", "d"), lines("a\nb\r\nc\r\rd"))
        assertEquals(listOf("a"), lines("a\n"))
        assertEquals(emptyList<String>(), lines(""))
    }

    @Test
    fun `cuts long lines and keeps reading`() {
        assertEquals(listOf("abc", "de"), lines("abcdefgh\nde", maxLength = 3))
    }

    @Test
    fun `reads lines across buffer boundaries`() {
        val long = "x".repeat(100_000)
        assertEquals(listOf("x".repeat(70_000), "end"), lines("$long\r\nend", maxLength = 70_000))
        val reader = BoundedLineReader(StringReader("a\r"), 10)
        assertEquals("a", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test(expected = UserFacingException::class)
    fun `capped stream fails beyond its limit`() {
        CappedInputStream(ByteArray(2_000).inputStream(), 1_000).readBytes()
    }

    @Test(expected = UserFacingException::class)
    fun `XML guard rejects entity declarations`() {
        XmlGuardInputStream("<!DOCTYPE tv [<!ENTITY a \"aaaa\">]><tv/>".byteInputStream(), 1_000_000, 1_000).readBytes()
    }

    @Test(expected = UserFacingException::class)
    fun `XML guard rejects huge text nodes`() {
        XmlGuardInputStream("<desc>${"x".repeat(2_000)}</desc>".byteInputStream(), 1_000_000, 1_000).readBytes()
    }

    @Test
    fun `XML guard lets regular guides through`() {
        val guide = "<tv><programme><title>News</title></programme></tv>"
        assertEquals(guide, XmlGuardInputStream(guide.byteInputStream(), 1_000_000, 1_000).readBytes().decodeToString())
    }
}
