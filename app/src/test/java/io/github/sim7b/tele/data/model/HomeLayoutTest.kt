package io.github.sim7b.tele.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun `the classic home with every row by default, and a chosen layout read back`() {
        assertEquals(HomeStyle.CLASSIC, HomeLayout().style)
        assertEquals(HomeRow.entries.toSet(), HomeLayout().rows)
        val chosen = HomeLayout(HomeStyle.IMMERSIVE, setOf(HomeRow.SPORT, HomeRow.RESUME))
        assertEquals(chosen, HomeLayout.fromJson(chosen.toJson()))
        // Unknown values: ignored
        assertEquals(HomeLayout(HomeStyle.CLASSIC, setOf(HomeRow.TRAKT)), HomeLayout.fromJson(JSONObject("""{"style":"NEON","rows":["TRAKT","WEATHER"]}""")))
    }
}
