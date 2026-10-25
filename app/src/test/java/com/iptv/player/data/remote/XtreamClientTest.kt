package com.iptv.player.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamClientTest {

    @Test
    fun `splits a pasted account link`() {
        assertEquals(
            Triple("http://provider.example:8080", "user", "secret"),
            XtreamClient.splitAccountUrl("http://provider.example:8080/get.php?username=user&password=secret&type=m3u_plus")
        )
        assertEquals(
            Triple("http://provider.example", "user", "secret"),
            XtreamClient.splitAccountUrl("  provider.example/get.php?username=user&password=secret ")
        )
    }

    @Test
    fun `ignores links without credentials`() {
        assertNull(XtreamClient.splitAccountUrl("http://provider.example/get.php?username=user"))
        assertNull(XtreamClient.splitAccountUrl("not a url"))
    }
}
