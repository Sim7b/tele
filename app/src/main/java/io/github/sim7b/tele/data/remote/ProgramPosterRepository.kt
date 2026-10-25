package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.epg.EpgResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * External EPG enrichment (TVMaze API: widescreen backgrounds and banners).
 * Prefers LANDSCAPE artwork (16:9 / banner), suited to TV tiles.
 */
object ProgramPosterRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // In-memory cache (including missing results, as an empty value) to avoid repeated requests
    private val posterCache = ConcurrentHashMap<String, String>()

    /**
     * URL of a landscape artwork (16:9 / backdrop) for a program title.
     */
    suspend fun getPosterUrl(rawTitle: String): String? = withContext(Dispatchers.IO) {
        if (rawTitle.isBlank() || rawTitle == "Program") return@withContext null

        val cleanTitle = cleanProgramTitle(rawTitle)
        if (cleanTitle.isBlank()) return@withContext null

        // Cache lookup
        posterCache[cleanTitle]?.let { return@withContext it.ifBlank { null } }

        var result: String? = null
        var definitive = true
        try {
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val url = "https://api.tvmaze.com/singlesearch/shows?q=$encodedTitle&embed=images"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.DEFAULT_USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        result = extractLandscapeImage(JSONObject(HttpClients.jsonString(response.body)), cleanTitle)
                    }
                    // 404 = no matching show; other codes (429, 5xx) are transient
                    response.code != 404 -> definitive = false
                }
            }
        } catch (e: Exception) {
            // Network error: do not cache the missing result
            definitive = false
        }

        if (result != null || definitive) {
            posterCache[cleanTitle] = result ?: ""
        }
        result
    }

    private fun extractLandscapeImage(json: JSONObject, cleanTitle: String): String? {
        // TVMaze always returns the "closest" show: reject a show whose name does not match,
        // otherwise unrelated artwork shows up on the tiles.
        val showName = json.optString("name", "")
        if (EpgResolver.normalizeName(showName) != EpgResolver.normalizeName(cleanTitle)) return null

        // 1. Look for a LANDSCAPE image first (background / 16:9 banner)
        val imagesArray = json.optJSONObject("_embedded")?.optJSONArray("images")
        if (imagesArray != null) {
            for (i in 0 until imagesArray.length()) {
                val imgObj = imagesArray.optJSONObject(i) ?: continue
                val type = imgObj.optString("type", "")
                if (type.equals("background", ignoreCase = true) || type.equals("banner", ignoreCase = true)) {
                    val resObj = imgObj.optJSONObject("resolutions")
                    val origObj = resObj?.optJSONObject("original") ?: resObj?.optJSONObject("medium")
                    val landscapeUrl = origObj?.optString("url", "")?.takeIf { it.isNotBlank() }
                    if (landscapeUrl != null) return landscapeUrl
                }
            }
        }

        // 2. Fall back to the standard image when no 16:9 background is available
        val imageObj = json.optJSONObject("image")
        return imageObj?.optString("medium", "")?.takeIf { it.isNotBlank() }
            ?: imageObj?.optString("original", "")?.takeIf { it.isNotBlank() }
    }

    private fun cleanProgramTitle(title: String): String {
        return title.substringBefore(",")
            .substringBefore(":")
            .substringBefore(" -")
            .substringBefore(" (")
            .trim()
    }
}
