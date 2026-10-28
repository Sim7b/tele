package io.github.sim7b.tele.data.vod

import android.util.Log
import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A production company (movies) or a network (series), as TMDB names it. */
data class Studio(val id: String, val name: String)

/**
 * What TMDB tells of a title, for the filters and the rankings: [found] false when TMDB does not know it
 * (looked up again after [RETRY_MS]).
 */
data class TmdbFacts(
    val tmdbId: String?,
    val countries: List<String> = emptyList(),
    val studios: List<Studio> = emptyList(),
    val popularity: Double = 0.0,
    val vote: Double = 0.0,
    val language: String? = null,
    val resolvedAt: Long = 0L,
    val found: Boolean = tmdbId != null
)

/** Progress of the indexing: titles known to TMDB (or looked up) out of the library. */
data class VodIndexProgress(val indexed: Int = 0, val total: Int = 0, val running: Boolean = false)

/**
 * TMDB facts of the library titles (countries, studios, popularity), fetched in the background with
 * the user's key, [PARALLEL] requests at a time, and kept on the device in an append-only file: only the
 * new titles are looked up afterwards. The key never appears in the file nor in the logs.
 */
class VodIndex(private val file: File) {

    private val facts = ConcurrentHashMap<String, TmdbFacts>()
    private val lock = Any()
    private var loaded = false

    // Bumped when facts are added (the filters and rankings are rebuilt from it)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _progress = MutableStateFlow(VodIndexProgress())
    val progress: StateFlow<VodIndexProgress> = _progress.asStateFlow()

    fun facts(titleKey: String): TmdbFacts? = facts[titleKey]

    /** Snapshot for the background computations. */
    fun all(): Map<String, TmdbFacts> = HashMap(facts)

    /** Reads the file once (off the main thread); later lines replace earlier ones. */
    fun load() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            if (!file.exists()) return
            var lines = 0
            try {
                file.bufferedReader().useLines { sequence ->
                    sequence.forEach { line ->
                        if (line.isBlank()) return@forEach
                        lines++
                        val obj = JSONObject(line)
                        val key = obj.optStringOrNull("k") ?: return@forEach
                        facts[key] = fromJson(obj)
                    }
                }
                // Many superseded lines: rewritten compact
                if (lines > facts.size * 3 / 2 + 100) rewrite()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read the TMDB index: ${e.javaClass.simpleName}")
            }
        }
        _version.value++
    }

    /**
     * Looks up the [titles] TMDB does not know yet (or not recently), until done or cancelled. Facts are
     * published every [PUBLISH_EVERY] titles.
     */
    suspend fun index(titles: List<VodTitle>, key: String, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()) =
        withContext(Dispatchers.IO) {
            load()
            val pending = titles.filter { title ->
                val known = facts[title.key]
                known == null || (!known.found && now - known.resolvedAt > RETRY_MS)
            }
            val total = titles.size
            var indexed = total - pending.size
            _progress.value = VodIndexProgress(indexed, total, running = pending.isNotEmpty())
            if (pending.isEmpty()) return@withContext
            val semaphore = Semaphore(PARALLEL)
            file.parentFile?.mkdirs()
            // Appended: the facts already on the device are kept
            val out: BufferedWriter = java.io.FileWriter(file, true).buffered()
            try {
                pending.chunked(PUBLISH_EVERY).forEach { batch ->
                    val results = coroutineScope {
                        batch.map { title ->
                            async { semaphore.withPermit { title.key to resolve(title, key, locale.toLanguageTag(), now) } }
                        }.awaitAll()
                    }
                    synchronized(lock) {
                        results.forEach { (titleKey, found) ->
                            if (found == null) return@forEach
                            facts[titleKey] = found
                            out.write(toJson(titleKey, found).toString())
                            out.newLine()
                        }
                        out.flush()
                    }
                    indexed += batch.size
                    _progress.value = VodIndexProgress(indexed, total, running = true)
                    _version.value++
                }
            } finally {
                out.close()
                _progress.value = _progress.value.copy(running = false)
            }
        }

    /** Facts of a title, null on a network error (looked up again next time). */
    private suspend fun resolve(title: VodTitle, key: String, language: String, now: Long): TmdbFacts? {
        val type = if (title.kind == VodKind.MOVIE) "movie" else "tv"
        return try {
            val id = title.tmdbId ?: run {
                val search = VodMetadata.tmdbGet("search/$type", key, language) {
                    addQueryParameter("query", title.title)
                    title.year?.let { addQueryParameter(if (type == "movie") "primary_release_year" else "first_air_date_year", it.toString()) }
                } ?: return null
                search.optJSONArray("results")?.optJSONObject(0)?.optStringOrNull("id")
            } ?: return TmdbFacts(tmdbId = null, resolvedAt = now)
            val details = VodMetadata.tmdbGet("$type/$id", key, language) {} ?: return null
            val countries = (
                VodMetadata.objects(details.optJSONArray("production_countries")).mapNotNull { it.optStringOrNull("iso_3166_1") } +
                    strings(details.optJSONArray("origin_country"))
                ).distinct()
            // Series: the network first (Netflix, HBO...), then the production companies
            val studios = (
                VodMetadata.objects(details.optJSONArray("networks")) +
                    VodMetadata.objects(details.optJSONArray("production_companies"))
                ).mapNotNull { company ->
                val companyId = company.optStringOrNull("id") ?: return@mapNotNull null
                val name = company.optStringOrNull("name") ?: return@mapNotNull null
                Studio(companyId, name)
            }.distinctBy { it.id }.take(MAX_STUDIOS)
            TmdbFacts(
                tmdbId = id,
                countries = countries,
                studios = studios,
                popularity = details.optDouble("popularity", 0.0),
                vote = details.optDouble("vote_average", 0.0),
                language = details.optStringOrNull("original_language"),
                resolvedAt = now
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun rewrite() {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.bufferedWriter().use { writer ->
            facts.forEach { (key, value) ->
                writer.write(toJson(key, value).toString())
                writer.newLine()
            }
        }
        if (!temp.renameTo(file)) temp.delete()
    }

    fun clear() {
        synchronized(lock) {
            facts.clear()
            file.delete()
        }
        _version.value++
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }

    private fun toJson(key: String, value: TmdbFacts) = JSONObject().apply {
        put("k", key)
        put("id", value.tmdbId)
        put("f", value.found)
        if (value.countries.isNotEmpty()) put("c", JSONArray(value.countries))
        if (value.studios.isNotEmpty()) put("s", JSONArray().apply { value.studios.forEach { put(JSONArray().put(it.id).put(it.name)) } })
        if (value.popularity > 0) put("p", value.popularity)
        if (value.vote > 0) put("v", value.vote)
        put("l", value.language)
        put("at", value.resolvedAt)
    }

    private fun fromJson(obj: JSONObject): TmdbFacts {
        val studios = obj.optJSONArray("s")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val pair = array.optJSONArray(index) ?: return@mapNotNull null
                Studio(pair.optString(0), pair.optString(1))
            }
        }.orEmpty()
        return TmdbFacts(
            tmdbId = obj.optStringOrNull("id"),
            countries = strings(obj.optJSONArray("c")),
            studios = studios,
            popularity = obj.optDouble("p", 0.0),
            vote = obj.optDouble("v", 0.0),
            language = obj.optStringOrNull("l"),
            resolvedAt = obj.optLong("at"),
            found = obj.optBoolean("f", obj.optStringOrNull("id") != null)
        )
    }

    private companion object {
        const val TAG = "VodIndex"
        const val PARALLEL = 6
        const val PUBLISH_EVERY = 120
        const val MAX_STUDIOS = 4
        const val RETRY_MS = 30L * 24 * 60 * 60 * 1000
    }
}
