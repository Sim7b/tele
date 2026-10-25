package io.github.sim7b.tele.data.parser

import io.github.sim7b.tele.data.UserFacingException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedInputStream

class XmltvEncodingTest {

    private fun encoding(bytes: ByteArray): String {
        val stream = BufferedInputStream(bytes.inputStream())
        val encoding = XmltvParser.asciiCompatibleEncoding(stream)
        assertEquals(bytes.size, stream.readBytes().size)
        return encoding
    }

    @Test
    fun `defaults to UTF-8 and keeps ASCII-compatible declarations`() {
        assertEquals("UTF-8", encoding("<tv></tv>".toByteArray()))
        assertEquals("UTF-8", encoding("﻿<?xml version=\"1.0\"?><tv/>".toByteArray()))
        assertEquals("ISO-8859-1", encoding("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><tv/>".toByteArray()))
        assertEquals("windows-1252", encoding("<?xml version='1.0' encoding='windows-1252'?><tv/>".toByteArray()))
    }

    @Test(expected = UserFacingException::class)
    fun `rejects UTF-16 with a byte order mark`() {
        encoding("﻿<tv/>".toByteArray(Charsets.UTF_16))
    }

    @Test(expected = UserFacingException::class)
    fun `rejects UTF-16 without a byte order mark`() {
        encoding("<tv/>".toByteArray(Charsets.UTF_16LE))
    }

    @Test(expected = UserFacingException::class)
    fun `rejects a declared encoding that is not ASCII-compatible`() {
        encoding("<?xml version=\"1.0\" encoding=\"UTF-16\"?><tv/>".toByteArray())
    }
}
