package io.github.sim7b.tele.data.model

import androidx.annotation.StringRes
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.optStringOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.UUID

/**
 * Channel source type.
 */
enum class SourceType(@param:StringRes val labelRes: Int) {
    DISPATCHARR(R.string.source_type_dispatcharr),
    XTREAM(R.string.source_type_xtream),
    M3U(R.string.source_type_m3u)
}

/**
 * Channel source: Dispatcharr server (API key), Xtream Codes account (username + password)
 * or M3U playlist (link, with an optional XMLTV guide).
 */
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    // Dispatcharr / Xtream: server address; M3U: full playlist link
    val serverUrl: String,
    val apiKey: String = "",
    val isActive: Boolean = false,
    val customUserAgent: String? = null,
    val type: SourceType = SourceType.DISPATCHARR,
    val username: String = "",
    val password: String = "",
    // M3U: optional XMLTV guide (else the one announced by the playlist, url-tvg)
    val epgUrl: String? = null
) {
    /**
     * Absolute, normalized URL. Without a scheme, https:// is added (http:// for Xtream, rarely served over HTTPS).
     * The M3U playlist link is kept as is (query parameters included).
     */
    val normalizedUrl: String
        get() {
            var url = serverUrl.trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                url = (if (type == SourceType.XTREAM) "http://" else "https://") + url
            }
            return if (type == SourceType.M3U) url else url.trimEnd('/')
        }

    /** Displayable address, without credentials or parameters (M3U links often contain the password). */
    val displayUrl: String
        get() {
            val parsed = normalizedUrl.toHttpUrlOrNull() ?: return normalizedUrl.substringBefore('?')
            val port = if (parsed.port != okhttp3.HttpUrl.defaultPort(parsed.scheme)) ":${parsed.port}" else ""
            val base = "${parsed.scheme}://${parsed.host}$port"
            return when (type) {
                SourceType.M3U -> base + parsed.encodedPath.takeIf { it != "/" }.orEmpty()
                SourceType.XTREAM -> if (username.isNotBlank()) "$base  ·  $username" else base
                SourceType.DISPATCHARR -> base
            }
        }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("serverUrl", serverUrl)
            put("apiKey", apiKey)
            put("isActive", isActive)
            put("customUserAgent", customUserAgent ?: "")
            put("type", type.name)
            put("username", username)
            put("password", password)
            put("epgUrl", epgUrl ?: "")
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SourceConfig {
            return SourceConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Dispatcharr"),
                serverUrl = json.optString("serverUrl", ""),
                apiKey = json.optString("apiKey", ""),
                isActive = json.optBoolean("isActive", false),
                customUserAgent = json.optString("customUserAgent").takeIf { it.isNotBlank() },
                // Configurations saved before source types existed: Dispatcharr
                type = runCatching { SourceType.valueOf(json.optString("type")) }.getOrDefault(SourceType.DISPATCHARR),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                epgUrl = json.optStringOrNull("epgUrl")
            )
        }
    }
}
