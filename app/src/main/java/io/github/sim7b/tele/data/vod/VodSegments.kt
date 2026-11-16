package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.BuildConfig
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.executeCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * Intro and end credits of a movie or an episode, from TheIntroDB (a public community base, by TMDB id): where
 * the intro starts and ends, and where the end credits start. Null values: unknown.
 */
data class VodSegments(val introStartMs: Long? = null, val introEndMs: Long? = null, val creditsStartMs: Long? = null) {
    val isEmpty: Boolean get() = introEndMs == null && creditsStartMs == null

    /** Length of the end credits of a file of [durationMs], for the next episode (null when unknown or odd). */
    fun creditsLengthMs(durationMs: Long): Long? {
        val start = creditsStartMs ?: return null
        return (durationMs - start).takeIf { durationMs > 0 && it > 0 && it < durationMs / 3 }
    }

    companion object {
        private const val API = "https://api.theintrodb.org/v4/media"
        private const val MAX_SEGMENT_MS = 3L * 60 * 60 * 1000

        /**
         * Segments of an answer of TheIntroDB, or of the "segments" of the Télé API plugin (same shape): the first
         * intro, the first credits; malformed values are left out.
         */
        fun parse(answer: JSONObject?): VodSegments {
            if (answer == null) return VodSegments()
            fun first(kind: String): Pair<Long?, Long?>? {
                val item = answer.optJSONArray(kind)?.optJSONObject(0) ?: return null
                fun value(name: String): Long? =
                    if (item.isNull(name)) null else item.optLong(name, -1).takeIf { it in 0..MAX_SEGMENT_MS }
                val start = value("start_ms")
                val end = value("end_ms")
                if (start != null && end != null && end <= start) return null
                return start to end
            }
            val intro = first("intro")
            val credits = first("credits")
            return VodSegments(
                // A null start is the start of the file
                introStartMs = intro?.let { it.first ?: 0L },
                introEndMs = intro?.second,
                creditsStartMs = credits?.first
            )
        }

        /** Asks TheIntroDB directly (no Télé API plugin): a movie without [season], else an episode. */
        suspend fun fetch(tmdbId: String, season: Int?, episode: Int?): VodSegments? = withContext(Dispatchers.IO) {
            if (tmdbId.isBlank() || tmdbId.any { !it.isDigit() } || (season == null) != (episode == null)) return@withContext null
            val url = API.toHttpUrl().newBuilder().addQueryParameter("tmdb_id", tmdbId).apply {
                if (season != null && episode != null) {
                    addQueryParameter("season", season.toString())
                    addQueryParameter("episode", episode.toString())
                }
            }.build()
            val request = Request.Builder().url(url).header("User-Agent", "Tele v${BuildConfig.VERSION_NAME}").get().build()
            try {
                HttpClients.base.newCall(request).executeCancellable { response ->
                    if (response.code == 404) return@executeCancellable VodSegments()
                    if (!response.isSuccessful) return@executeCancellable null
                    parse(JSONObject(HttpClients.jsonString(response.body)))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
    }
}

/** Where to skip the intro to, from the segments of TheIntroDB: offered from its start until a little before its end. */
internal fun segmentSkipTarget(positionMs: Long, startMs: Long, endMs: Long, minPositionMs: Long, marginMs: Long): Long? =
    endMs.takeIf { positionMs >= maxOf(startMs, minPositionMs) && positionMs < endMs - marginMs }
