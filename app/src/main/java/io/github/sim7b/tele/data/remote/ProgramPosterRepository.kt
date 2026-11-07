package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.epg.EpgResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * External EPG enrichment (TVMaze API: widescreen backgrounds and banners).
 * Prefers LANDSCAPE artwork (16:9 / banner), suited to TV tiles.
 */
object ProgramPosterRepository {

    private const val MAX_CACHED_TITLES = 2_000

    // Derived from the shared client (connection pool), with short timeouts: artwork is optional
    private val client = HttpClients.base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Bounded in-memory cache (including missing results, as an empty value) to avoid repeated requests
    private val posterCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_CACHED_TITLES
    }

    // Lookups in progress: the tiles showing the same program share a single request
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /**
     * URL of a landscape artwork (16:9 / backdrop) for a program title.
     */
    suspend fun getPosterUrl(rawTitle: String): String? = withContext(Dispatchers.IO) {
        if (rawTitle.isBlank()) return@withContext null

        val cleanTitle = cleanProgramTitle(rawTitle)
        if (cleanTitle.isBlank()) return@withContext null

        // Cache lookup
        synchronized(posterCache) { posterCache[cleanTitle] }?.let { return@withContext it.ifBlank { null } }

        val pending = CompletableDeferred<String?>()
        inFlight.putIfAbsent(cleanTitle, pending)?.let { return@withContext it.await() }
        try {
            fetchPosterUrl(cleanTitle).also { pending.complete(it) }
        } finally {
            // Cancelled or failed: the waiting tiles get no artwork this time
            pending.complete(null)
            inFlight.remove(cleanTitle, pending)
        }
    }

    private fun fetchPosterUrl(cleanTitle: String): String? {
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
            synchronized(posterCache) { posterCache[cleanTitle] = result ?: "" }
        }
        return result
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
        // The original (the medium one is a 210 px thumbnail, blurred once enlarged)
        return imageObj?.optString("original", "")?.takeIf { it.isNotBlank() }
            ?: imageObj?.optString("medium", "")?.takeIf { it.isNotBlank() }
    }

    /** TMDB API key of the settings (null: TVMaze only); only sent to api.themoviedb.org. */
    @Volatile
    var tmdbKey: String? = null

    private val backdropCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_CACHED_TITLES
    }

    /**
     * A large landscape artwork of a program, for the whole screen: the TMDB backdrop of the movie or the series
     * of that name (1280 px, with a TMDB key), else null. Missing ones are remembered too.
     */
    suspend fun getBackdropUrl(rawTitle: String): String? = withContext(Dispatchers.IO) {
        val key = tmdbKey?.takeIf { it.isNotBlank() } ?: return@withContext null
        val cleanTitle = cleanProgramTitle(rawTitle)
        if (cleanTitle.isBlank()) return@withContext null
        synchronized(backdropCache) { backdropCache[cleanTitle] }?.let { return@withContext it.ifBlank { null } }
        val url = "https://api.themoviedb.org/3/search/multi".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .addQueryParameter("query", cleanTitle)
            .addQueryParameter("language", java.util.Locale.getDefault().language)
            .addQueryParameter("include_adult", "false")
            .build()
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", HttpClients.DEFAULT_USER_AGENT).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val found = tmdbBackdrop(JSONObject(HttpClients.jsonString(response.body)), cleanTitle)
                synchronized(backdropCache) { backdropCache[cleanTitle] = found ?: "" }
                found
            }
        } catch (e: Exception) {
            // Network error: tried again next time
            null
        }
    }

    /** Backdrop of the first movie or series of [json] (TMDB search) named [cleanTitle], in 1280 px. */
    internal fun tmdbBackdrop(json: JSONObject, cleanTitle: String): String? {
        val wanted = EpgResolver.normalizeName(cleanTitle)
        val results = json.optJSONArray("results") ?: return null
        for (i in 0 until minOf(results.length(), 10)) {
            val item = results.optJSONObject(i) ?: continue
            val type = item.optString("media_type")
            if (type != "movie" && type != "tv") continue
            val names = listOf("title", "name", "original_title", "original_name").map { item.optString(it) }.filter { it.isNotBlank() }
            if (names.none { EpgResolver.normalizeName(it) == wanted }) continue
            val path = item.optString("backdrop_path").takeIf { it.startsWith("/") && it.length < 100 } ?: continue
            return "https://image.tmdb.org/t/p/w1280$path"
        }
        return null
    }

    private fun cleanProgramTitle(title: String): String {
        return title.substringBefore(",")
            .substringBefore(":")
            .substringBefore(" -")
            .substringBefore(" (")
            .trim()
    }
}
