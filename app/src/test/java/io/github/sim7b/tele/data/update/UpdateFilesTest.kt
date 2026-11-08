package io.github.sim7b.tele.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFilesTest {

    @Test
    fun `a version gives a file name and back, never a path`() {
        assertEquals("tele-0.3.15.apk", UpdateFiles.fileName("0.3.15"))
        assertEquals("0.3.15", UpdateFiles.versionOf("tele-0.3.15.apk"))
        assertNull(UpdateFiles.fileName("../../evil"))
        assertNull(UpdateFiles.fileName("0.3/15"))
        assertNull(UpdateFiles.fileName(""))
        assertNull(UpdateFiles.versionOf("update.apk"))
        assertNull(UpdateFiles.versionOf("tele-0.3.15.apk.part"))
    }

    @Test
    fun `only the APK of a newer version is kept`() {
        val names = listOf("tele-0.3.14.apk", "tele-0.3.15.apk", "tele-0.3.16.apk", "update.apk", "tele-0.3.16.apk.part")
        assertEquals(listOf("tele-0.3.14.apk", "update.apk", "tele-0.3.16.apk.part"), UpdateFiles.stale(names, installed = "0.3.14"))
        // A release found: only its APK stays
        assertEquals(
            listOf("tele-0.3.14.apk", "tele-0.3.15.apk", "update.apk", "tele-0.3.16.apk.part"),
            UpdateFiles.stale(names, installed = "0.3.14", keep = "0.3.16")
        )
    }

    @Test
    fun `the offer shows a newer version unless it was ignored`() {
        assertTrue(UpdateFiles.shouldPrompt("0.3.16", ignoredVersion = null))
        assertTrue(UpdateFiles.shouldPrompt("0.3.16", ignoredVersion = "0.3.15"))
        assertFalse(UpdateFiles.shouldPrompt("0.3.16", ignoredVersion = "0.3.16"))
        assertFalse(UpdateFiles.shouldPrompt(null, ignoredVersion = null))
    }
}
