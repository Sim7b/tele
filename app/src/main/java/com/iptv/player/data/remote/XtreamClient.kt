package com.iptv.player.data.remote

import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.StreamSource
import com.iptv.player.data.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Compte Xtream Codes authentifié.
 */
data class XtreamAccount(
    val username: String,
    val expiresAtMillis: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val outputFormats: List<String>
)

/**
 * Client de l'API Xtream Codes (player_api.php) : authentification, chaînes en direct et guide xmltv.php.
 */
object XtreamClient {

    suspend fun authenticate(config: SourceConfig): Result<XtreamAccount> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(get(apiUrl(config, action = null), config))
            val user = root.optJSONObject("user_info")
                ?: return@withContext Result.failure(IOException("Réponse inattendue : ce serveur n'est pas une API Xtream Codes"))
            if (user.optInt("auth", 0) != 1) {
                return@withContext Result.failure(IOException("Identifiant ou mot de passe refusé par le serveur"))
            }
            val status = user.optStringOrNull("status")
            if (status != null && !status.equals("Active", ignoreCase = true)) {
                return@withContext Result.failure(IOException("Compte $status"))
            }
            val formats = user.optJSONArray("allowed_output_formats")
                ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf { f -> f.isNotBlank() } } }
                .orEmpty()
            Result.success(
                XtreamAccount(
                    username = user.optStringOrNull("username") ?: config.username,
                    expiresAtMillis = user.optStringOrNull("exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
                    maxConnections = user.optStringOrNull("max_connections")?.toIntOrNull(),
                    activeConnections = user.optStringOrNull("active_cons")?.toIntOrNull(),
                    outputFormats = formats
                )
            )
        } catch (e: Exception) {
            Result.failure(IOException(HttpClients.friendlyError(e), e))
        }
    }

    suspend fun loadChannels(config: SourceConfig): Result<SourceChannels> = withContext(Dispatchers.IO) {
        val account = authenticate(config).getOrElse { return@withContext Result.failure(it) }
        try {
            coroutineScope {
                val categoriesDeferred = async { JSONArray(get(apiUrl(config, "get_live_categories"), config)) }
                val streams = JSONArray(get(apiUrl(config, "get_live_streams"), config))
                val categories = categoriesDeferred.await().let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                        .associate { it.optString("category_id") to (it.optStringOrNull("category_name") ?: "Autres") }
                }

                // TS en priorité (lecture progressive, démarrage rapide), sinon HLS
                val formats = account.outputFormats.ifEmpty { listOf("ts", "m3u8") }
                val preferred = if ("ts" in formats) "ts" else formats.firstOrNull { it == "m3u8" } ?: "ts"
                val idPrefix = "xt-${config.id.take(8)}-"

                val channels = ArrayList<Channel>(streams.length())
                for (i in 0 until streams.length()) {
                    val item = streams.optJSONObject(i) ?: continue
                    val streamId = item.optStringOrNull("stream_id") ?: continue
                    val categoryId = item.optStringOrNull("category_id")
                        ?: item.optJSONArray("category_ids")?.optString(0)
                    val variants = formats.filter { it == "ts" || it == "m3u8" }.map { format ->
                        StreamSource(
                            name = if (format == "ts") "MPEG-TS (direct)" else "HLS",
                            url = streamUrl(config, streamId, format),
                            id = "xtream-$format",
                            provider = account.username,
                            qualityTags = listOf(if (format == "ts") "TS" else "HLS")
                        )
                    }
                    channels.add(
                        Channel(
                            id = idPrefix + streamId,
                            name = item.optStringOrNull("name") ?: "Chaîne $streamId",
                            group = categories[categoryId] ?: "Autres",
                            logoUrl = item.optStringOrNull("stream_icon"),
                            streamUrl = streamUrl(config, streamId, preferred),
                            tvgId = item.optStringOrNull("epg_channel_id"),
                            availableStreams = if (variants.size > 1) variants else emptyList(),
                            number = item.optStringOrNull("num")?.toIntOrNull()
                        )
                    )
                }
                channels.sortBy { it.number ?: Int.MAX_VALUE }

                Result.success(
                    SourceChannels(
                        channels = channels,
                        guideUrls = listOf(xmltvUrl(config).toString()),
                        userName = account.username
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(IOException(HttpClients.friendlyError(e), e))
        }
    }

    private fun base(config: SourceConfig): HttpUrl =
        config.normalizedUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Adresse du serveur invalide")

    private fun apiUrl(config: SourceConfig, action: String?): HttpUrl =
        base(config).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", config.username)
            .addQueryParameter("password", config.password)
            .apply { action?.let { addQueryParameter("action", it) } }
            .build()

    private fun xmltvUrl(config: SourceConfig): HttpUrl =
        base(config).newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", config.username)
            .addQueryParameter("password", config.password)
            .build()

    private fun streamUrl(config: SourceConfig, streamId: String, format: String): String =
        base(config).newBuilder()
            .addPathSegment("live")
            .addPathSegment(config.username)
            .addPathSegment(config.password)
            .addPathSegment("$streamId.$format")
            .build()
            .toString()

    private fun get(url: HttpUrl, config: SourceConfig): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", config.customUserAgent?.takeIf { it.isNotBlank() } ?: HttpClients.DEFAULT_USER_AGENT)
            .get()
            .build()
        HttpClients.base.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        401, 403 -> "Identifiant ou mot de passe refusé (HTTP ${response.code})"
                        404 -> "API Xtream introuvable à cette adresse (HTTP 404)"
                        else -> "Erreur serveur : HTTP ${response.code}"
                    }
                )
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("Réponse vide du serveur")
            return body
        }
    }

    /**
     * Les utilisateurs collent souvent un lien complet (get.php?username=…&password=…) :
     * renvoie l'adresse du serveur, l'identifiant et le mot de passe qu'il contient.
     */
    fun splitAccountUrl(text: String): Triple<String, String, String>? {
        val url = text.trim().let { if (it.contains("://")) it else "http://$it" }.toHttpUrlOrNull() ?: return null
        val username = url.queryParameter("username") ?: return null
        val password = url.queryParameter("password") ?: return null
        val port = if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
        return Triple("${url.scheme}://${url.host}$port", username, password)
    }
}
