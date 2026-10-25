package io.github.sim7b.tele.data

import io.github.sim7b.tele.R
import org.json.JSONArray
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UiTextTest {

    @Test
    fun `parsing errors never expose the response body`() {
        val error = runCatching { JSONArray("""[{"user_info":{"password":"secret"}""") }.exceptionOrNull()!!

        assertTrue(error is JSONException)
        val text = error.toUiText() as UiText.Res
        assertEquals(R.string.error_invalid_response, text.id)
    }

    @Test
    fun `raw messages are shortened`() {
        val text = IOException("x".repeat(1_000)).toUiText() as UiText.Raw
        assertEquals(200, text.value.length)
    }
}
