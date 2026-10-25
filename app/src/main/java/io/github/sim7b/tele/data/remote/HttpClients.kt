package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.UserFacingException
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client shared by the API, playlists, guides and the player: a single connection pool,
 * so TLS handshakes and keep-alive connections are reused (notably on each channel switch).
 */
object HttpClients {
    const val DEFAULT_USER_AGENT = "TV-AndroidTV/1.0 (Android TV)"

    // Limits against huge or malicious responses (OkHttp transparently decompresses gzip bodies)
    private const val MAX_JSON_BYTES = 32L * 1024 * 1024
    private const val MAX_JSON_DEPTH = 64

    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * JSON body, bounded in size and nesting depth: org.json parses recursively, so a deeply nested
     * body would overflow the stack and crash the app at each launch. Bodies using org.json's lenient
     * syntax (single quotes, comments), which the depth check cannot follow, are rejected.
     */
    fun jsonString(body: ResponseBody): String {
        val source = body.source()
        if (source.request(MAX_JSON_BYTES + 1)) throw tooLarge()
        val text = source.readString(body.contentType()?.charset() ?: Charsets.UTF_8)
        checkJsonDepth(text)
        return text
    }

    internal fun checkJsonDepth(text: String, maxDepth: Int = MAX_JSON_DEPTH) {
        fun invalid() = UserFacingException(UiText.Res(R.string.error_invalid_response))
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> if (++depth > maxDepth) throw invalid()
                ']', '}' -> if (--depth < 0) throw invalid()
                '\'', '/', '#' -> throw invalid()
            }
        }
    }

    internal fun tooLarge() = UserFacingException(UiText.Res(R.string.error_response_too_large))
}
