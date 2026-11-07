package io.github.sim7b.tele.data.trakt

import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.executeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** The Trakt application of the user (created on trakt.tv): its client id and secret. */
data class TraktApp(val clientId: String, val clientSecret: String) {
    val isComplete: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

/** Access to the account of the user, given on trakt.tv/activate; [expiresAt] in epoch ms. */
data class TraktSession(val accessToken: String, val refreshToken: String, val expiresAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("access", accessToken).put("refresh", refreshToken).put("expires", expiresAt)

    companion object {
        fun fromJson(json: JSONObject): TraktSession? {
            val access = json.optString("access").takeIf { it.isNotBlank() } ?: return null
            val refresh = json.optString("refresh").takeIf { it.isNotBlank() } ?: return null
            return TraktSession(access, refresh, json.optLong("expires"))
        }
    }
}

/** Code to type on [verificationUrl] to link the account; polled every [intervalSeconds] until [expiresAt]. */
data class TraktDeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val intervalSeconds: Int, val expiresAt: Long)

/** A movie or a series of the watchlist, by its ids. */
data class TraktItem(val movie: Boolean, val title: String, val tmdbId: String?, val imdbId: String?)

/** What was watched to the end: a movie, or an episode of a series (the ids of the series). */
data class TraktWatched(val tmdbId: String?, val imdbId: String?, val season: Int? = null, val episode: Int? = null, val watchedAt: Long) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/**
 * Trakt API (https://trakt.docs.apiary.io): linking by a code typed on another device (device flow), the
 * watchlist, and the movies and episodes watched to the end added to the history. Only the ids of the titles
 * are sent, nothing about the sources.
 */
object Trakt {

    private const val API = "https://api.trakt.tv"
    private val JSON = "application/json".toMediaType()
    private const val REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
    private const val MAX_ITEMS = 500
    // A token is refreshed this long before it expires
    private const val REFRESH_MARGIN_MS = 24 * 60 * 60_000L

    /** Not linked yet: [Pending] while the code is not typed, then the session or the end of the code. */
    sealed interface Poll {
        data object Pending : Poll
        data class Linked(val session: TraktSession) : Poll
        data object Expired : Poll
        data object Denied : Poll
    }

    suspend fun deviceCode(app: TraktApp, now: Long = System.currentTimeMillis()): TraktDeviceCode = withContext(Dispatchers.IO) {
        val json = post("/oauth/device/code", JSONObject().put("client_id", app.clientId), app, null)
        parseDeviceCode(json, now)
    }

    internal fun parseDeviceCode(json: JSONObject, now: Long): TraktDeviceCode = TraktDeviceCode(
        deviceCode = json.getString("device_code"),
        userCode = json.getString("user_code"),
        verificationUrl = json.optString("verification_url").takeIf { it.startsWith("https://") } ?: "https://trakt.tv/activate",
        intervalSeconds = json.optInt("interval", 5).coerceIn(1, 60),
        expiresAt = now + json.optLong("expires_in", 600).coerceIn(60, 3_600) * 1000
    )

    /** One poll of the code: Trakt answers 400 while it is not typed, 410 / 418 once expired or refused. */
    suspend fun poll(app: TraktApp, code: TraktDeviceCode, now: Long = System.currentTimeMillis()): Poll = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code.deviceCode).put("client_id", app.clientId).put("client_secret", app.clientSecret)
        val request = Request.Builder().url("$API/oauth/device/token").post(body.toString().toRequestBody(JSON)).headers(app, null).build()
        HttpClients.base.newCall(request).executeCancellable { response ->
            when (response.code) {
                200 -> Poll.Linked(parseSession(JSONObject(response.body.string().take(MAX_BODY)), now))
                400, 429 -> Poll.Pending
                404, 409, 410 -> Poll.Expired
                418 -> Poll.Denied
                else -> throw IOException("Trakt ${response.code}")
            }
        }
    }

    internal fun parseSession(json: JSONObject, now: Long): TraktSession = TraktSession(
        accessToken = json.getString("access_token"),
        refreshToken = json.getString("refresh_token"),
        expiresAt = now + json.optLong("expires_in", 7 * 24 * 3_600L) * 1000
    )

    /** [session] still valid, else a new one (null: no longer linked). */
    suspend fun fresh(app: TraktApp, session: TraktSession, now: Long = System.currentTimeMillis()): TraktSession? {
        if (session.expiresAt - REFRESH_MARGIN_MS > now) return session
        return withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("refresh_token", session.refreshToken)
                .put("client_id", app.clientId)
                .put("client_secret", app.clientSecret)
                .put("redirect_uri", REDIRECT)
                .put("grant_type", "refresh_token")
            val request = Request.Builder().url("$API/oauth/token").post(body.toString().toRequestBody(JSON)).headers(app, null).build()
            HttpClients.base.newCall(request).executeCancellable { response ->
                when {
                    response.isSuccessful -> parseSession(JSONObject(response.body.string().take(MAX_BODY)), now)
                    response.code == 400 || response.code == 401 -> null
                    else -> throw IOException("Trakt ${response.code}")
                }
            }
        }
    }

    /** Movies then series of the watchlist, the latest added first. */
    suspend fun watchlist(app: TraktApp, session: TraktSession): List<TraktItem> = withContext(Dispatchers.IO) {
        listOf("movies", "shows").flatMap { type ->
            val request = Request.Builder().url("$API/sync/watchlist/$type/added?limit=$MAX_ITEMS").get().headers(app, session).build()
            HttpClients.base.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) throw IOException("Trakt ${response.code}")
                parseWatchlist(JSONArray(response.body.string().take(MAX_BODY)))
            }
        }
    }

    internal fun parseWatchlist(array: JSONArray): List<TraktItem> = (0 until minOf(array.length(), MAX_ITEMS)).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        val movie = entry.optJSONObject("movie")
        val item = movie ?: entry.optJSONObject("show") ?: return@mapNotNull null
        val ids = item.optJSONObject("ids")
        TraktItem(
            movie = movie != null,
            title = item.optString("title"),
            tmdbId = ids?.optLong("tmdb", 0L)?.takeIf { it > 0 }?.toString(),
            imdbId = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }
        )
    }

    /** Adds [watched] to the history of the account. */
    suspend fun addToHistory(app: TraktApp, session: TraktSession, watched: TraktWatched) = withContext(Dispatchers.IO) {
        post("/sync/history", historyBody(watched), app, session)
    }

    internal fun historyBody(watched: TraktWatched): JSONObject {
        val ids = JSONObject().apply {
            watched.tmdbId?.toLongOrNull()?.let { put("tmdb", it) }
            watched.imdbId?.let { put("imdb", it) }
        }
        val at = Instant.ofEpochMilli(watched.watchedAt).toString()
        return if (watched.isEpisode) {
            val episode = JSONObject().put("number", watched.episode).put("watched_at", at)
            val season = JSONObject().put("number", watched.season).put("episodes", JSONArray().put(episode))
            JSONObject().put("shows", JSONArray().put(JSONObject().put("ids", ids).put("seasons", JSONArray().put(season))))
        } else {
            JSONObject().put("movies", JSONArray().put(JSONObject().put("ids", ids).put("watched_at", at)))
        }
    }

    private fun post(path: String, body: JSONObject, app: TraktApp, session: TraktSession?): JSONObject {
        val request = Request.Builder().url(API + path).post(body.toString().toRequestBody(JSON)).headers(app, session).build()
        return HttpClients.base.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Trakt ${response.code}")
            val text = response.body.string().take(MAX_BODY)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun Request.Builder.headers(app: TraktApp, session: TraktSession?): Request.Builder = apply {
        header("Content-Type", "application/json")
        header("trakt-api-version", "2")
        header("trakt-api-key", app.clientId)
        session?.let { header("Authorization", "Bearer ${it.accessToken}") }
    }

    // Answers are small; anything bigger is cut (and fails to parse rather than filling the memory)
    private const val MAX_BODY = 2 * 1024 * 1024
}

/** The titles of the library matching the Trakt items, in the order of the items (by TMDB id, else IMDb id). */
object TraktMatch {
    fun titles(items: List<TraktItem>, titles: List<io.github.sim7b.tele.data.vod.VodTitle>): List<io.github.sim7b.tele.data.vod.VodTitle> {
        if (items.isEmpty() || titles.isEmpty()) return emptyList()
        val byTmdb = HashMap<String, io.github.sim7b.tele.data.vod.VodTitle>()
        val byImdb = HashMap<String, io.github.sim7b.tele.data.vod.VodTitle>()
        titles.forEach { title ->
            val kind = if (title.kind == io.github.sim7b.tele.data.vod.VodKind.MOVIE) "m" else "s"
            title.tmdbId?.let { byTmdb.putIfAbsent("$kind:$it", title) }
            title.imdbId?.let { byImdb.putIfAbsent(it, title) }
        }
        return items.mapNotNull { item ->
            val kind = if (item.movie) "m" else "s"
            item.tmdbId?.let { byTmdb["$kind:$it"] } ?: item.imdbId?.let { byImdb[it] }
        }.distinctBy { it.key }
    }
}
