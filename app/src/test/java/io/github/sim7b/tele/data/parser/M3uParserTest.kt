package io.github.sim7b.tele.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.byteInputStream())

    @Test
    fun `reads entries with their attributes`() {
        val playlist = parse(
            """
            #EXTM3U url-tvg="http://a.example/guide.xml,http://b.example/guide.xml.gz"
            #EXTINF:-1 tvg-id="tf1.fr" tvg-name="TF1 HD" tvg-logo="http://logo.example/tf1.png" group-title="General;News" tvg-chno="1",TF1
            http://stream.example/live/1.ts
            """.trimIndent()
        )

        assertEquals(listOf("http://a.example/guide.xml", "http://b.example/guide.xml.gz"), playlist.epgUrls)
        val entry = playlist.entries.single()
        assertEquals("TF1", entry.name)
        assertEquals("http://stream.example/live/1.ts", entry.url)
        assertEquals("tf1.fr", entry.tvgId)
        assertEquals("TF1 HD", entry.tvgName)
        assertEquals("http://logo.example/tf1.png", entry.logo)
        assertEquals("General", entry.group)
        assertEquals(1, entry.number)
    }

    @Test
    fun `strips a byte order mark and reads EXTGRP`() {
        val playlist = parse("﻿#EXTM3U\n#EXTINF:-1,France 2\n#EXTGRP:News\nhttp://stream.example/2.ts\n")

        val entry = playlist.entries.single()
        assertEquals("France 2", entry.name)
        assertEquals("News", entry.group)
    }

    @Test
    fun `keeps commas inside quoted attributes`() {
        val playlist = parse("#EXTM3U\n#EXTINF:-1 group-title=\"Sport, Live\",beIN Sports 1\nhttp://stream.example/3.ts\n")

        val entry = playlist.entries.single()
        assertEquals("beIN Sports 1", entry.name)
        assertEquals("Sport, Live", entry.group)
    }

    @Test
    fun `skips movies and series`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,Movie
            http://provider.example/movie/user/pass/42.mkv
            #EXTINF:-1,Series
            http://provider.example/series/user/pass/43.mp4
            #EXTINF:-1,Live
            http://provider.example/live/user/pass/44.ts
            """.trimIndent()
        )

        assertEquals(listOf("Live"), playlist.entries.map { it.name })
    }

    @Test
    fun `names untitled entries from tvg-name or position`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="Arte",
            http://stream.example/arte.ts
            http://stream.example/raw.ts
            """.trimIndent()
        )

        assertEquals(listOf("Arte", "#2"), playlist.entries.map { it.name })
        assertNull(playlist.entries[1].tvgId)
    }

    @Test
    fun `stops at the maximum number of entries`() {
        val text = "#EXTM3U\n" + (1..10).joinToString("\n") { "#EXTINF:-1,C$it\nhttp://stream.example/$it.ts" }

        assertEquals(3, M3uParser.parse(text.byteInputStream(), maxEntries = 3).entries.size)
    }

    @Test(expected = io.github.sim7b.tele.data.UserFacingException::class)
    fun `fails on playlists over the size limit`() {
        M3uParser.parse(("#EXTINF:-1,C\nhttp://s/1.ts\n").repeat(1_000).byteInputStream(), maxBytes = 1_000)
    }
}
