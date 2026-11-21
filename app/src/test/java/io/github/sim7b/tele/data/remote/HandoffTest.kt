package io.github.sim7b.tele.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffTest {

    @Test
    fun `an item goes to the server and back`() {
        val item = HandoffItem(HandoffItem.VOD, "series:12#S1E3", "Show S1E3", 61_000, 2_400_000)
        assertEquals(item, HandoffItem.fromJson(JSONObject(item.toJson().toString())))
        assertEquals("series:12", item.titleKey)
        assertEquals("42", HandoffItem(HandoffItem.LIVE, "42", "Info").titleKey)
    }

    @Test
    fun `broken items are left out`() {
        assertNull(HandoffItem.fromJson(null))
        assertNull(HandoffItem.fromJson(JSONObject("""{"kind":"other","key":"1"}""")))
        assertNull(HandoffItem.fromJson(JSONObject("""{"kind":"live","key":" "}""")))
        assertNull(HandoffItem.fromJson(JSONObject().put("kind", "vod").put("key", "x".repeat(301))))
        val negative = HandoffItem.fromJson(JSONObject("""{"kind":"vod","key":"m:1","title":"${"t".repeat(300)}","position_ms":-5}"""))
        assertEquals(0L, negative?.positionMs)
        assertEquals(200, negative?.title?.length)
    }

    @Test
    fun `the state keeps only well-formed TVs and items`() {
        val state = HandoffState.fromJson(
            JSONObject(
                """
                {"playing":[
                    {"device":"0123456789ab","name":"Salon","kind":"live","key":"7","title":"Info"},
                    {"device":"../etc","kind":"live","key":"7","title":"Info"},
                    {"device":"0123456789ac","kind":"bad","key":"7"}
                 ],
                 "tvs":[{"device":"0123456789ab","name":"Salon"},{"device":"0123456789ad","name":" "},{"device":"nope"}],
                 "incoming":{"device":"0123456789ad","kind":"vod","key":"movie:3","title":"Film","position_ms":5000}}
                """
            )
        )!!
        assertEquals(listOf(HandoffTv("0123456789ab", "Salon") to HandoffItem(HandoffItem.LIVE, "7", "Info")), state.playing)
        assertEquals(listOf(HandoffTv("0123456789ab", "Salon"), HandoffTv("0123456789ad", null)), state.tvs)
        assertEquals(HandoffItem(HandoffItem.VOD, "movie:3", "Film", 5000), state.incoming?.second)
        assertNull(HandoffState.fromJson(JSONObject("{}"))!!.incoming)
    }
}
