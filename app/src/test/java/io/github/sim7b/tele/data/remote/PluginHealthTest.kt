package io.github.sim7b.tele.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHealthTest {

    private var now = 1_000_000L
    private val health = PluginHealth { now }

    @Test
    fun `features are probed again after a while`() {
        assertNull(health.current())
        health.probed(setOf("channels"))
        assertEquals(setOf("channels"), health.current())
        now += PluginHealth.RECHECK_MS
        assertNull(health.current())
        assertEquals(emptySet<String>(), health.known())
    }

    @Test
    fun `a failure sets the plugin aside, then it is probed again`() {
        health.probed(setOf("channels"))
        health.failed()
        assertEquals(emptySet<String>(), health.current())
        now += PluginHealth.SET_ASIDE_MS - 1
        assertEquals(emptySet<String>(), health.current())
        now += 1
        assertNull(health.current())
    }

    @Test
    fun `server errors and HTML pages are failures, JSON refusals are answers`() {
        assertTrue(PluginHealth.isFailure(500, "application/json"))
        assertTrue(PluginHealth.isFailure(502, null))
        assertTrue(PluginHealth.isFailure(403, "text/html; charset=utf-8"))
        assertTrue(PluginHealth.isFailure(200, "text/html"))
        assertFalse(PluginHealth.isFailure(403, "application/json"))
        assertFalse(PluginHealth.isFailure(429, "application/json"))
        assertFalse(PluginHealth.isFailure(404, "application/json"))
        assertFalse(PluginHealth.isFailure(304, null))
    }
}
