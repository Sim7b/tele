package com.iptv.player.data.model

import com.iptv.player.data.optStringOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.UUID

/**
 * Type de source de chaînes.
 */
enum class SourceType(val label: String) {
    DISPATCHARR("Dispatcharr"),
    XTREAM("Xtream Codes"),
    M3U("Playlist M3U")
}

/**
 * Source de chaînes : serveur Dispatcharr (clé API), compte Xtream Codes (identifiant + mot de passe)
 * ou playlist M3U (lien, avec guide XMLTV facultatif).
 */
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    // Dispatcharr / Xtream : adresse du serveur ; M3U : lien complet de la playlist
    val serverUrl: String,
    val apiKey: String = "",
    val isActive: Boolean = false,
    val customUserAgent: String? = null,
    val type: SourceType = SourceType.DISPATCHARR,
    val username: String = "",
    val password: String = "",
    // M3U : guide XMLTV facultatif (sinon celui annoncé par la playlist, url-tvg)
    val epgUrl: String? = null
) {
    /**
     * URL absolue et normalisée. Sans schéma, https:// est ajouté (http:// pour Xtream, rarement en HTTPS).
     * La playlist M3U est conservée telle quelle (paramètres de requête compris).
     */
    val normalizedUrl: String
        get() {
            var url = serverUrl.trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                url = (if (type == SourceType.XTREAM) "http://" else "https://") + url
            }
            return if (type == SourceType.M3U) url else url.trimEnd('/')
        }

    /** Adresse affichable, sans identifiants ni paramètres (les liens M3U contiennent souvent le mot de passe). */
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
                name = json.optString("name", "Serveur Dispatcharr"),
                serverUrl = json.optString("serverUrl", ""),
                apiKey = json.optString("apiKey", ""),
                isActive = json.optBoolean("isActive", false),
                customUserAgent = json.optString("customUserAgent").takeIf { it.isNotBlank() },
                // Configurations enregistrées avant l'ajout des types : Dispatcharr
                type = runCatching { SourceType.valueOf(json.optString("type")) }.getOrDefault(SourceType.DISPATCHARR),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                epgUrl = json.optStringOrNull("epgUrl")
            )
        }
    }
}
