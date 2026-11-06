package io.github.sim7b.tele.data.storage

import android.content.Context
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.HomeLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParentalControlTest {

    private var now = 1_000_000L
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("parental-test", Context.MODE_PRIVATE)
    private fun control() = ParentalControl(prefs) { now }

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `without a code nothing is locked`() {
        val parental = control()
        parental.setGroupsLocked(listOf("Adults"), locked = true)
        assertFalse(parental.active())
        assertEquals(emptySet<String>(), parental.hiddenGroupsNow())
        assertTrue(parental.unlock("0000"))
    }

    @Test
    fun `the code locks until entered, and again once the app is left`() {
        val parental = control()
        assertFalse(parental.setPin("12a4"))
        assertTrue(parental.setPin("1234"))
        parental.setGroupLocked("Adults", locked = true)
        // Whoever sets it keeps the control open
        assertFalse(parental.active())
        parental.lock()
        assertEquals(setOf("Adults"), parental.hiddenGroupsNow())
        assertFalse(parental.unlock("0000"))
        assertTrue(parental.unlock("1234"))
        assertEquals(emptySet<String>(), parental.hiddenGroupsNow())
    }

    @Test
    fun `the code is kept as a salted hash only, and survives a restart`() {
        control().setPin("1234")
        assertFalse(prefs.all.values.any { it.toString().contains("1234") })
        val restarted = control()
        assertTrue(restarted.active())
        assertTrue(restarted.unlock("1234"))
    }

    @Test
    fun `too many wrong codes ask to wait`() {
        val parental = control()
        parental.setPin("1234")
        parental.lock()
        repeat(ParentalControl.MAX_ATTEMPTS) { assertFalse(parental.unlock("0000")) }
        assertTrue(parental.waitMs() > 0)
        // Even the right one waits
        assertFalse(parental.unlock("1234"))
        now += ParentalControl.COOLDOWN_MS
        assertTrue(parental.unlock("1234"))
    }

    @Test
    fun `removing the code removes the locks`() {
        val parental = control()
        parental.setPin("1234")
        parental.setGroupLocked("Adults", locked = true)
        parental.setVodLocked(true)
        parental.clear()
        assertFalse(parental.hasPin.value)
        assertEquals(emptySet<String>(), parental.lockedGroups.value)
        assertFalse(parental.vodLocked.value)
    }

    @Test
    fun `locked categories leave every list, favorites included`() {
        fun channel(id: String, group: String) = Channel(id = id, name = id, group = group, streamUrl = "http://example.test/$id")
        val display = listOf(channel("news", "News"), channel("adult", "Adults"))
        val lists = HomeLists.build(display, emptySet(), emptySet(), favoriteIds = listOf("adult", "news"), categoryOrder = emptyList(), lockedGroups = setOf("Adults"))
        assertEquals(listOf("news"), lists.unhidden.map { it.id })
        assertEquals(listOf("news"), lists.favorites.map { it.id })
        assertEquals(listOf("News"), lists.categoryCounts.map { it.first })
    }
}
