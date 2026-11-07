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

    /** A movie or a series found on TMDB: its kind ("movie", "tv"), its id, its backdrop (1280 px) if any. */
    internal data class TmdbMatch(val type: String, val id: Long, val backdrop: String?)

    // Search results by clean title (a missing one as null), and the title logos by "type:id"
    private val matchCache = object : LinkedHashMap<String, TmdbMatch?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TmdbMatch?>?) = size > MAX_CACHED_TITLES
    }
    private val logoCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_CACHED_TITLES
    }

    /**
     * A large landscape artwork of a program, for the whole screen: the TMDB backdrop of the movie or the series
     * of that name (1280 px, with a TMDB key), else null. Missing ones are remembered too.
     */
    suspend fun getBackdropUrl(rawTitle: String): String? = tmdbMatch(rawTitle)?.backdrop

    /**
     * The logo of a title (its name drawn as artwork, in the language of the device, else English, else without
     * text), from TMDB: by its id when known ([tmdbId], [movie]), else by its name. Null without a TMDB key or logo.
     */
    suspend fun getTitleLogoUrl(rawTitle: String, tmdbId: String? = null, movie: Boolean? = null): String? = withContext(Dispatchers.IO) {
        val key = tmdbKey?.takeIf { it.isNotBlank() } ?: return@withContext null
        val known = tmdbId?.toLongOrNull()?.takeIf { movie != null }?.let { TmdbMatch(if (movie == true) "movie" else "tv", it, null) }
        val match = known ?: tmdbMatch(rawTitle) ?: return@withContext null
        val cacheKey = "${match.type}:${match.id}"
        synchronized(logoCache) { logoCache[cacheKey] }?.let { return@withContext it.ifBlank { null } }
        val language = java.util.Locale.getDefault().language
        val url = "https://api.themoviedb.org/3/${match.type}/${match.id}/images".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .addQueryParameter("include_image_language", "$language,en,null")
            .build()
        val found = getJson(url)?.let { tmdbLogo(it, language) } ?: return@withContext null
        synchronized(logoCache) { logoCache[cacheKey] = found }
        found
    }

    /** The movie or series of that name on TMDB (with a TMDB key), remembered, missing ones too. */
    private suspend fun tmdbMatch(rawTitle: String): TmdbMatch? = withContext(Dispatchers.IO) {
        val key = tmdbKey?.takeIf { it.isNotBlank() } ?: return@withContext null
        val cleanTitle = cleanProgramTitle(rawTitle)
        if (cleanTitle.isBlank()) return@withContext null
        synchronized(matchCache) { if (matchCache.containsKey(cleanTitle)) return@withContext matchCache[cleanTitle] }
        val url = "https://api.themoviedb.org/3/search/multi".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .addQueryParameter("query", cleanTitle)
            .addQueryParameter("language", java.util.Locale.getDefault().language)
            .addQueryParameter("include_adult", "false")
            .build()
        // Network error: tried again next time
        val json = getJson(url) ?: return@withContext null
        val found = tmdbFind(json, cleanTitle)
        synchronized(matchCache) { matchCache[cleanTitle] = found }
        found
    }

    private fun getJson(url: okhttp3.HttpUrl): JSONObject? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", HttpClients.DEFAULT_USER_AGENT).get().build()).execute().use { response ->
            if (response.isSuccessful) JSONObject(HttpClients.jsonString(response.body)) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Backdrop of the first movie or series of [json] (TMDB search) named [cleanTitle], in 1280 px. */
    internal fun tmdbBackdrop(json: JSONObject, cleanTitle: String): String? = tmdbFind(json, cleanTitle)?.backdrop

    /** The first movie or series of [json] (TMDB search) named [cleanTitle] (the one with a backdrop first). */
    internal fun tmdbFind(json: JSONObject, cleanTitle: String): TmdbMatch? {
        val wanted = EpgResolver.normalizeName(cleanTitle)
        val results = json.optJSONArray("results") ?: return null
        val matches = (0 until minOf(results.length(), 10)).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val type = item.optString("media_type")
            if (type != "movie" && type != "tv") return@mapNotNull null
            val names = listOf("title", "name", "original_title", "original_name").map { item.optString(it) }.filter { it.isNotBlank() }
            if (names.none { EpgResolver.normalizeName(it) == wanted }) return@mapNotNull null
            val id = item.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val path = item.optString("backdrop_path").takeIf { it.startsWith("/") && it.length < 100 }
            TmdbMatch(type, id, path?.let { "https://image.tmdb.org/t/p/w1280$it" })
        }
        return matches.firstOrNull { it.backdrop != null } ?: matches.firstOrNull()
    }

    /** The logo of [json] (TMDB images) in [language], else English, else without text; PNG first (transparent). */
    internal fun tmdbLogo(json: JSONObject, language: String): String? {
        val logos = json.optJSONArray("logos") ?: return null
        val all = (0 until minOf(logos.length(), 40)).mapNotNull { logos.optJSONObject(it) }
            .filter { it.optString("file_path").let { path -> path.startsWith("/") && path.length < 100 } }
        fun rank(logo: JSONObject): Int {
            val lang = logo.optString("iso_639_1").takeIf { it.isNotBlank() && it != "null" }
            val byLanguage = when (lang) {
                language -> 0
                "en" -> 1
                null -> 2
                else -> 3
            }
            return byLanguage * 2 + if (logo.optString("file_path").endsWith(".png")) 0 else 1
        }
        val best = all.filter { rank(it) < 6 }.minByOrNull { rank(it) } ?: return null
        return "https://image.tmdb.org/t/p/w500" + best.optString("file_path")
    }


    private fun cleanProgramTitle(title: String): String {
        return title.substringBefore(",")
            .substringBefore(":")
            .substringBefore(" -")
            .substringBefore(" (")
            .trim()
    }
}
