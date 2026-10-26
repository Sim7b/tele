package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.UserFacingException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonStreamsTest {

    private fun read(json: String, maxBytes: Long = 1024 * 1024, maxItems: Int = 1000): List<JSONObject> {
        val objects = mutableListOf<JSONObject>()
        JsonStreams.forEachObject(json.byteInputStream(), maxBytes, maxItems) { objects.add(it) }
        return objects
    }

    @Test
    fun `reads the objects of a raw array, skipping other values`() {
        val objects = read("""[{"name":"A"}, 3, "x", null, {"name":"B"}]""")

        assertEquals(listOf("A", "B"), objects.map { it.getString("name") })
    }

    @Test
    fun `reads the data or results array of a paginated object`() {
        assertEquals(1, read("""{"count":1,"results":[{"id":1}],"next":null}""").size)
        assertEquals(2, read("""{"data":[{"id":1},{"id":2}]}""").size)
        assertEquals(0, read("""{"user_info":{"auth":0}}""").size)
    }

    @Test
    fun `keeps integers as integers like org json`() {
        val item = read("""[{"stream_id":12345,"big":12345678901,"ratio":1.5,"ok":true,"none":null,"tags":["a"],"o":{"k":1}}]""").single()

        assertEquals("12345", item.optString("stream_id"))
        assertEquals(12345678901L, item.getLong("big"))
        assertEquals(1.5, item.getDouble("ratio"), 0.0)
        assertTrue(item.getBoolean("ok"))
        assertTrue(item.isNull("none"))
        assertEquals("a", item.getJSONArray("tags").getString(0))
        assertEquals(1, item.getJSONObject("o").getInt("k"))
    }

    @Test
    fun `stops at the maximum number of objects`() {
        assertEquals(2, read("""[{},{},{},{}]""", maxItems = 2).size)
    }

    @Test(expected = UserFacingException::class)
    fun `rejects malformed json`() {
        read("""[{"name":'single quotes'}]""")
    }

    @Test(expected = UserFacingException::class)
    fun `rejects an empty body`() {
        read("")
    }

    @Test(expected = UserFacingException::class)
    fun `rejects a body over the size limit`() {
        read("[" + List(1000) { """{"name":"channel"}""" }.joinToString(",") + "]", maxBytes = 1024)
    }

    @Test(expected = UserFacingException::class)
    fun `rejects deeply nested json without overflowing the stack`() {
        read("[" + "[".repeat(100_000) + "]".repeat(100_000) + "]")
    }
}
