package io.github.sim7b.tele.data.subtitles

import io.github.sim7b.tele.BuildConfig
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.UserFacingException
import io.github.sim7b.tele.data.optStringOrNull
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.executeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.Locale

/** What to look subtitles up for: a movie, or an episode of a series ([season] and [episode] set). */
data class SubtitleQuery(
    val title: String,
    val year: Int? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/** A subtitle file found on OpenSubtitles. */
data class SubtitleResult(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val machineTranslated: Boolean,
    val fromTrusted: Boolean
)

/** A downloaded subtitle file and the downloads left for the day (null when unknown). */
class DownloadedSubtitle(val bytes: ByteArray, val extension: String, val remaining: Int?)

/**
 * OpenSubtitles REST API (api.opensubtitles.com/api/v1), with the user's API key. Only called when the user
 * asks for subtitles. The API redirects queries whose parameters are not in alphabetical order or not in
 * lowercase: [searchUrl] builds them that way.
 */
object OpenSubtitles {

    private const val API = "https://api.opensubtitles.com/api/v1"
    // Identifies the app, as the API asks (generic user agents are refused)
    private val USER_AGENT = "Tele v${BuildConfig.VERSION_NAME}"
    private const val MAX_SUBTITLE_BYTES = 4L * 1024 * 1024
    private val JSON = "application/json".toMediaType()
    private val EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml")

    /** Search URL: the ids of the title when known, else its name; parameters sorted, values lowercase. */
    fun searchUrl(query: SubtitleQuery, languages: List<String>): HttpUrl {
        val params = sortedMapOf<String, String>()
        val tmdb = query.tmdbId?.takeIf { it.isNotBlank() && it != "0" }
        val imdb = query.imdbId?.removePrefix("tt")?.trimStart('0')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        if (query.isEpisode) {
            when {
                tmdb != null -> params["parent_tmdb_id"] = tmdb
                imdb != null -> params["parent_imdb_id"] = imdb
                else -> params["query"] = query.title.lowercase(Locale.ROOT)
            }
            params["season_number"] = query.season.toString()
            params["episode_number"] = query.episode.toString()
        } else {
            when {
                tmdb != null -> params["tmdb_id"] = tmdb
                imdb != null -> params["imdb_id"] = imdb
                else -> {
                    params["query"] = query.title.lowercase(Locale.ROOT)
                    query.year?.let { params["year"] = it.toString() }
                }
            }
        }
        if (languages.isNotEmpty()) params["languages"] = languages.map { it.lowercase(Locale.ROOT) }.distinct().sorted().joinToString(",")
        val builder = "$API/subtitles".toHttpUrl().newBuilder()
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    /** Results of a search response, the most downloaded first within the order of [languages]. */
    fun parseSearch(json: JSONObject, languages: List<String>): List<SubtitleResult> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val results = (0 until data.length()).mapNotNull { index ->
            val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: return@mapNotNull null
            val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            val fileId = file.optLong("file_id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            SubtitleResult(
                fileId = fileId,
                language = attributes.optStringOrNull("language")?.lowercase(Locale.ROOT) ?: return@mapNotNull null,
                release = attributes.optStringOrNull("release") ?: file.optStringOrNull("file_name").orEmpty(),
                downloads = attributes.optInt("download_count", 0),
                hearingImpaired = attributes.optBoolean("hearing_impaired", false),
                machineTranslated = attributes.optBoolean("machine_translated", false) || attributes.optBoolean("ai_translated", false),
                fromTrusted = attributes.optBoolean("from_trusted", false)
            )
        }
        val order = languages.map { it.lowercase(Locale.ROOT) }
        return results.sortedWith(
            compareBy<SubtitleResult> { order.indexOf(it.language).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .thenBy { it.machineTranslated }
                .thenByDescending { it.downloads }
        )
    }

    suspend fun search(key: String, query: SubtitleQuery, languages: List<String>): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val request = request(key).url(searchUrl(query, languages)).get().build()
        HttpClients.base.newCall(request).executeCancellable { response ->
            check(response)
            parseSearch(JSONObject(HttpClients.jsonString(response.body)), languages)
        }
    }

    /** Downloads [fileId]: a temporary link from the API, then the file (one download of the daily quota). */
    suspend fun download(key: String, fileId: Long): DownloadedSubtitle = withContext(Dispatchers.IO) {
        val body = JSONObject().put("file_id", fileId).toString().toRequestBody(JSON)
        val ticket = HttpClients.base.newCall(request(key).url("$API/download").post(body).build()).executeCancellable { response ->
            check(response)
            JSONObject(HttpClients.jsonString(response.body))
        }
        val link = ticket.optStringOrNull("link")?.toHttpUrl() ?: throw UserFacingException(UiText.Res(R.string.error_invalid_response))
        if (link.scheme != "https") throw UserFacingException(UiText.Res(R.string.error_invalid_response))
        val extension = ticket.optStringOrNull("file_name")?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it in EXTENSIONS } ?: "srt"
        val remaining = if (ticket.has("remaining")) ticket.optInt("remaining") else null
        // The file itself: on the CDN of OpenSubtitles, without the API key
        val file = Request.Builder().url(link).header("User-Agent", USER_AGENT).get().build()
        val bytes = HttpClients.base.newCall(file).executeCancellable { response ->
            if (!response.isSuccessful) throw UserFacingException(UiText.Res(R.string.subtitles_error_download))
            val source = response.body.source()
            if (source.request(MAX_SUBTITLE_BYTES + 1)) throw UserFacingException(UiText.Res(R.string.subtitles_error_download))
            source.readByteArray()
        }
        DownloadedSubtitle(bytes, extension, remaining)
    }

    private fun request(key: String): Request.Builder = Request.Builder()
        .header("Api-Key", key)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")

    /** Refused key, exhausted quota, rate limit: messages the user can act on. */
    private fun check(response: Response) {
        if (response.isSuccessful) return
        val text = when (response.code) {
            401, 403 -> UiText.Res(R.string.subtitles_error_key)
            406, 429 -> UiText.Res(R.string.subtitles_error_quota)
            else -> UiText.Res(R.string.subtitles_error_server, response.code)
        }
        throw UserFacingException(text)
    }
}
