package com.iptv.player.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceConfigTest {

    @Test
    fun `adds a scheme and trims the trailing slash`() {
        assertEquals(
            "https://dispatcharr.example",
            SourceConfig(name = "D", serverUrl = " dispatcharr.example/ ").normalizedUrl
        )
        assertEquals(
            "http://xtream.example:8080",
            SourceConfig(name = "X", serverUrl = "xtream.example:8080/", type = SourceType.XTREAM).normalizedUrl
        )
        assertEquals(
            "https://m3u.example/list.m3u?token=1",
            SourceConfig(name = "M", serverUrl = "m3u.example/list.m3u?token=1", type = SourceType.M3U).normalizedUrl
        )
    }

    @Test
    fun `hides credentials from the displayed address`() {
        assertEquals(
            "https://m3u.example/get.php",
            SourceConfig(
                name = "M",
                serverUrl = "https://m3u.example/get.php?username=user&password=secret",
                type = SourceType.M3U
            ).displayUrl
        )
        assertEquals(
            "http://xtream.example:8080  ·  user",
            SourceConfig(
                name = "X",
                serverUrl = "xtream.example:8080",
                type = SourceType.XTREAM,
                username = "user",
                password = "secret"
            ).displayUrl
        )
    }

    @Test
    fun `survives a JSON round trip`() {
        val config = SourceConfig(
            name = "X",
            serverUrl = "xtream.example",
            type = SourceType.XTREAM,
            username = "user",
            password = "secret",
            epgUrl = "http://guide.example/xmltv.xml"
        )

        assertEquals(config, SourceConfig.fromJson(config.toJson()))
    }
}
