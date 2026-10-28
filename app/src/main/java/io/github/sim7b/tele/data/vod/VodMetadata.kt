package io.github.sim7b.tele.data.vod

import android.util.Log
import io.github.sim7b.tele.data.optStringOrNull
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.executeCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What TMDB or TVmaze tell about a title, to complete what the providers give. */
data class VodEnrichment(
    val title: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val durationSecs: Int? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val episodes: Map<String, VodEpisode> = emptyMap()
)

/**
 * Metadata of the titles: TMDB when the user entered an API key (movies and series, in the language of
 * the TV), else TVmaze for the series (no key; English texts). Failures only mean less complete sheets.
 * The TMDB key only goes to api.themoviedb.org, over HTTPS, and is never logged.
 */
object VodMetadata {

    private const val TAG = "VodMetadata"
    private const val TMDB_API = "https://api.themoviedb.org/3/"
    internal const val TMDB_IMAGES = "https://image.tmdb.org/t/p/"
    private const val MAX_SEASONS = 30

    private val client = HttpClients.base.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun enrich(title: VodTitle, seasons: Set<Int>, tmdbKey: String?, locale: Locale = Locale.getDefault()): VodEnrichment? =
        withContext(Dispatchers.IO) {
            try {
                when {
                    !tmdbKey.isNullOrBlank() -> tmdb(title, seasons, tmdbKey, locale)
                    title.kind == VodKind.SERIES -> tvMaze(title)
                    else -> null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Metadata unavailable: ${e.javaClass.simpleName}")
                null
            }
        }

    /**
     * Poster of a title its providers give none for: TMDB search (with a key), else TVmaze for a series.
     * Null when not found or unavailable.
     */
    suspend fun poster(title: VodTitle, tmdbKey: String?, locale: Locale = Locale.getDefault()): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!tmdbKey.isNullOrBlank()) {
                    val type = if (title.kind == VodKind.MOVIE) "movie" else "tv"
                    val result = if (title.tmdbId != null) {
                        tmdbGet("$type/${title.tmdbId}", tmdbKey, locale.toLanguageTag()) {}
                    } else {
                        tmdbGet("search/$type", tmdbKey, locale.toLanguageTag()) {
                            addQueryParameter("query", title.title)
                            title.year?.let { addQueryParameter(if (type == "movie") "primary_release_year" else "first_air_date_year", it.toString()) }
                        }?.optJSONArray("results")?.optJSONObject(0)
                    }
                    result?.optStringOrNull("poster_path")?.let { "${TMDB_IMAGES}w342$it" }
                } else if (title.kind == VodKind.SERIES) {
                    val url = "https://api.tvmaze.com/singlesearch/shows".toHttpUrl().newBuilder().addQueryParameter("q", title.title).build()
                    getJson(url)?.optJSONObject("image")?.let { it.optStringOrNull("medium") ?: it.optStringOrNull("original") }
                } else {
                    null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }

    // ---------------------------------------------------------------- TMDB

    private suspend fun tmdb(title: VodTitle, seasons: Set<Int>, key: String, locale: Locale): VodEnrichment? {
        val language = locale.toLanguageTag()
        val type = if (title.kind == VodKind.MOVIE) "movie" else "tv"
        val id = title.tmdbId ?: run {
            val search = tmdbGet("search/$type", key, language) {
                addQueryParameter("query", title.title)
                title.year?.let { addQueryParameter(if (type == "movie") "primary_release_year" else "first_air_date_year", it.toString()) }
            } ?: return null
            search.optJSONArray("results")?.optJSONObject(0)?.optStringOrNull("id")
        } ?: return null

        val details = tmdbGet("$type/$id", key, language) {
            addQueryParameter("append_to_response", if (type == "movie") "credits" else "credits,external_ids")
        } ?: return null
        val crew = details.optJSONObject("credits")?.optJSONArray("crew")
        val episodes = HashMap<String, VodEpisode>()
        if (type == "tv") {
            seasons.sorted().take(MAX_SEASONS).forEach { season ->
                val list = tmdbGet("tv/$id/season/$season", key, language) {}?.optJSONArray("episodes") ?: return@forEach
                objects(list).forEach { episode ->
                    val number = episode.optInt("episode_number", 0)
                    episodes[XtreamVod.episodeKey(season, number)] = VodEpisode(
                        key = XtreamVod.episodeKey(season, number),
                        season = season,
                        number = number,
                        title = episode.optStringOrNull("name").orEmpty(),
                        plot = episode.optStringOrNull("overview")?.takeIf { it.isNotBlank() },
                        durationSecs = episode.optInt("runtime", 0).takeIf { it > 0 }?.times(60),
                        stillUrl = episode.optStringOrNull("still_path")?.let { "${TMDB_IMAGES}w300$it" },
                        airDate = episode.optStringOrNull("air_date")
                    )
                }
            }
        }
        return VodEnrichment(
            title = details.optStringOrNull("title") ?: details.optStringOrNull("name"),
            year = VodNames.yearOf(details.optStringOrNull("release_date") ?: details.optStringOrNull("first_air_date")),
            plot = details.optStringOrNull("overview")?.takeIf { it.isNotBlank() },
            posterUrl = details.optStringOrNull("poster_path")?.let { "${TMDB_IMAGES}w500$it" },
            backdropUrl = details.optStringOrNull("backdrop_path")?.let { "${TMDB_IMAGES}w1280$it" },
            genres = objects(details.optJSONArray("genres")).mapNotNull { it.optStringOrNull("name") },
            rating = details.optDouble("vote_average", 0.0).takeIf { it > 0 },
            durationSecs = details.optInt("runtime", 0).takeIf { it > 0 }?.times(60),
            director = crew?.let { objects(it).firstOrNull { person -> person.optString("job") == "Director" }?.optStringOrNull("name") },
            cast = objects(details.optJSONObject("credits")?.optJSONArray("cast")).take(12).mapNotNull { it.optStringOrNull("name") },
            tmdbId = id,
            imdbId = details.optStringOrNull("imdb_id") ?: details.optJSONObject("external_ids")?.optStringOrNull("imdb_id"),
            episodes = episodes
        )
    }

    internal suspend fun tmdbGet(path: String, key: String, language: String, query: HttpUrl.Builder.() -> Unit): JSONObject? {
        val url = (TMDB_API + path).toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .addQueryParameter("language", language)
            .apply(query)
            .build()
        return getJson(url)
    }

    // ---------------------------------------------------------------- TVmaze

    private suspend fun tvMaze(title: VodTitle): VodEnrichment? {
        val url = "https://api.tvmaze.com/singlesearch/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", title.title)
            .addQueryParameter("embed", "episodes")
            .build()
        val show = getJson(url) ?: return null
        val year = VodNames.yearOf(show.optStringOrNull("premiered"))
        // Another show with the same name: kept only when the years agree
        if (title.year != null && year != null && kotlin.math.abs(title.year - year) > 1) return null
        val episodes = objects(show.optJSONObject("_embedded")?.optJSONArray("episodes")).associate { episode ->
            val season = episode.optInt("season", 0)
            val number = episode.optInt("number", 0)
            XtreamVod.episodeKey(season, number) to VodEpisode(
                key = XtreamVod.episodeKey(season, number),
                season = season,
                number = number,
                title = episode.optStringOrNull("name").orEmpty(),
                plot = stripHtml(episode.optStringOrNull("summary")),
                durationSecs = episode.optInt("runtime", 0).takeIf { it > 0 }?.times(60),
                stillUrl = episode.optJSONObject("image")?.let { it.optStringOrNull("original") ?: it.optStringOrNull("medium") },
                airDate = episode.optStringOrNull("airdate")
            )
        }
        return VodEnrichment(
            title = show.optStringOrNull("name"),
            year = year,
            plot = stripHtml(show.optStringOrNull("summary")),
            posterUrl = show.optJSONObject("image")?.let { it.optStringOrNull("original") ?: it.optStringOrNull("medium") },
            genres = show.optJSONArray("genres")?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf { g -> g.isNotBlank() } } }.orEmpty(),
            rating = show.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { it > 0 },
            imdbId = show.optJSONObject("externals")?.optStringOrNull("imdb"),
            episodes = episodes
        )
    }

    // ---------------------------------------------------------------- Helpers

    internal suspend fun getJson(url: HttpUrl): JSONObject? {
        val request = Request.Builder().url(url).header("User-Agent", HttpClients.DEFAULT_USER_AGENT).get().build()
        return client.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) return@executeCancellable null
            JSONObject(HttpClients.jsonString(response.body))
        }
    }

    internal fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    internal fun stripHtml(text: String?): String? =
        text?.replace(Regex("<[^>]*>"), "")?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")?.trim()?.takeIf { it.isNotEmpty() }
}
