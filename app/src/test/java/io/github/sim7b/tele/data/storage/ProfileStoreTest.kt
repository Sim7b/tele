package io.github.sim7b.tele.data.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileStoreTest {

    private var now = 1_000_000L
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("profiles-test", Context.MODE_PRIVATE)
    private fun store() = ProfileStore(prefs) { now }

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `without profiles saved, the main one is in use`() {
        val store = store()
        assertEquals(listOf(Profile.MAIN), store.profiles.value.map { it.id })
        assertTrue(store.current.value.isMain)
    }

    @Test
    fun `profiles and the one in use are kept`() {
        val store = store()
        val kid = store.add("  Léa  ", kids = true, pin = null)!!
        store.select(kid.id)
        val again = store()
        assertEquals(listOf("", "Léa"), again.profiles.value.map { it.name })
        assertEquals(kid.id, again.current.value.id)
        assertTrue(again.current.value.kids)
    }

    @Test
    fun `an empty name, a wrong code or too many profiles are refused`() {
        val store = store()
        assertNull(store.add("   ", kids = false, pin = null))
        assertNull(store.add("Tom", kids = false, pin = "12a4"))
        repeat(ProfileStore.MAX_PROFILES - 1) { assertNotNull(store.add("P$it", kids = false, pin = null)) }
        assertNull(store.add("One too many", kids = false, pin = null))
    }

    @Test
    fun `the code of a profile is kept hashed and checked`() {
        val store = store()
        val profile = store.add("Tom", kids = false, pin = "1234")!!
        assertTrue(profile.hasPin)
        assertFalse(prefs.all.values.any { it.toString().contains("\"1234\"") })
        assertTrue(store.checkPin(profile, "1234"))
        assertFalse(store.checkPin(profile, "0000"))
        assertTrue(store.checkPin(store.profiles.value.first(), "anything"))
    }

    @Test
    fun `too many wrong codes ask to wait`() {
        val store = store()
        val profile = store.add("Tom", kids = false, pin = "1234")!!
        repeat(ParentalControl.MAX_ATTEMPTS) { assertFalse(store.checkPin(profile, "0000")) }
        assertTrue(store.waitMs() > 0)
        assertFalse(store.checkPin(profile, "1234"))
        now += ParentalControl.COOLDOWN_MS
        assertTrue(store.checkPin(profile, "1234"))
    }

    @Test
    fun `the code is changed or removed, the name kept when empty`() {
        val store = store()
        val profile = store.add("Tom", kids = false, pin = "1234")!!
        assertTrue(store.update(profile.id, name = " ", pin = "5678"))
        val changed = store.profiles.value.last()
        assertEquals("Tom", changed.name)
        assertTrue(store.checkPin(changed, "5678"))
        store.update(profile.id, pin = "")
        assertFalse(store.profiles.value.last().hasPin)
    }

    @Test
    fun `the main profile is never removed, another one goes back to it`() {
        val store = store()
        assertFalse(store.remove(Profile.MAIN))
        val kid = store.add("Léa", kids = true, pin = null)!!
        store.select(kid.id)
        assertTrue(store.remove(kid.id))
        assertTrue(store.current.value.isMain)
        assertEquals(1, store.profiles.value.size)
    }

    @Test
    fun `broken saved profiles are skipped, the main one always first`() {
        val profiles = ProfileStore.fromJson("""[{"id":"0123456789ab","name":"Tom"},{"id":"../x","name":"Bad"},3,{"id":"main","name":"Me"}]""")
        assertEquals(listOf("main", "0123456789ab"), profiles.map { it.id })
        assertEquals("Me", profiles.first().name)
        assertEquals(listOf("main"), ProfileStore.fromJson("not json").map { it.id })
    }

    @Test
    fun `a kids profile forces the parental locks, even once the code is entered`() {
        val parental = ParentalControl(prefs) { now }
        parental.setVodLocked(true)
        assertFalse(parental.vodHiddenNow())
        parental.setForced(true)
        assertTrue(parental.vodHiddenNow())
        parental.setPin("1234")
        assertTrue(parental.unlock("1234"))
        assertTrue(parental.vodHiddenNow())
        parental.setForced(false)
        assertFalse(parental.vodHiddenNow())
    }

    @Test
    fun `the movies and series state follows the profile`() {
        val dir = Files.createTempDirectory("vod").toFile()
        val vod = VodStore(File(dir, "main.json"))
        vod.toggleMyList("m:1")
        vod.switchTo(File(dir, "kid.json"))
        assertEquals(emptyList<String>(), vod.myList.value)
        vod.toggleMyList("m:2")
        vod.switchTo(File(dir, "main.json"))
        assertEquals(listOf("m:1"), vod.myList.value)
        dir.deleteRecursively()
    }
}
