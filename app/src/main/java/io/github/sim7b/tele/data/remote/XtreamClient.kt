package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.UserFacingException
import io.github.sim7b.tele.data.failure
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authenticated Xtream Codes account.
 */
data class XtreamAccount(
    val username: String,
    val expiresAtMillis: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val outputFormats: List<String>
)

/**
 * Xtream Codes API client (player_api.php): authentication, live channels and xmltv.php guide.
 */
object XtreamClient {

    suspend fun authenticate(config: SourceConfig): Result<XtreamAccount> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(get(apiUrl(config, action = null), config))
            val user = root.optJSONObject("user_info")
                ?: return@withContext failure(R.string.error_not_xtream_api)
            if (user.optInt("auth", 0) != 1) {
                return@withContext failure(R.string.error_xtream_credentials_refused)
            }
            val status = user.optStringOrNull("status")
            if (status != null && !status.equals("Active", ignoreCase = true)) {
                return@withContext failure(R.string.error_xtream_account_status, status)
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
            Result.failure(e)
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
                        .associate { it.optString("category_id") to (it.optStringOrNull("category_name") ?: "Other") }
                }

                // TS first (progressive playback, fast start), else HLS
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
                            name = item.optStringOrNull("name") ?: "Channel $streamId",
                            group = categories[categoryId] ?: "Other",
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
            Result.failure(e)
        }
    }

    private fun base(config: SourceConfig): HttpUrl =
        config.normalizedUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid server address")

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
                throw UserFacingException(
                    when (response.code) {
                        401, 403 -> UiText.Res(R.string.error_xtream_credentials_refused_http, response.code)
                        404 -> UiText.Res(R.string.error_xtream_not_found)
                        else -> UiText.Res(R.string.error_server_http, response.code)
                    }
                )
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw UserFacingException(UiText.Res(R.string.error_empty_response))
            return body
        }
    }

    /**
     * Users often paste a full link (get.php?username=…&password=…):
     * returns the server address, username and password it contains.
     */
    fun splitAccountUrl(text: String): Triple<String, String, String>? {
        val url = text.trim().let { if (it.contains("://")) it else "http://$it" }.toHttpUrlOrNull() ?: return null
        val username = url.queryParameter("username") ?: return null
        val password = url.queryParameter("password") ?: return null
        val port = if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
        return Triple("${url.scheme}://${url.host}$port", username, password)
    }
}
