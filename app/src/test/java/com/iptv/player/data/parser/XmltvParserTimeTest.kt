package com.iptv.player.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class XmltvParserTimeTest {

    private fun millis(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun `parses timestamps with offset`() {
        assertEquals(millis("2026-10-25T18:30:00+01:00"), XmltvParser.parseTime("20261025183000 +0100"))
        assertEquals(millis("2026-10-25T18:30:00-05:30"), XmltvParser.parseTime("20261025183000 -0530"))
    }

    @Test
    fun `defaults to UTC and optional seconds`() {
        assertEquals(millis("2026-10-25T18:30:00Z"), XmltvParser.parseTime("202610251830"))
    }

    @Test
    fun `returns zero for invalid values`() {
        assertEquals(0L, XmltvParser.parseTime(null))
        assertEquals(0L, XmltvParser.parseTime(""))
        assertEquals(0L, XmltvParser.parseTime("2026"))
        assertEquals(0L, XmltvParser.parseTime("20261399999999"))
    }
}
