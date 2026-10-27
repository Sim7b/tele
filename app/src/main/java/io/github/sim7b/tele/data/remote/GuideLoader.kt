package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.toUiText
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.parser.XmltvGuide
import io.github.sim7b.tele.data.parser.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * Channels loaded from an Xtream or M3U source, with the XMLTV guide links to use.
 */
class SourceChannels(
    val channels: List<Channel>,
    val guideUrls: List<String>,
    val userName: String? = null
)

/**
 * Programs matched to channels (by application channel id). [cache] is what to store for the next load
 * (null when a guide file could not be downloaded); [unchanged] when the cached guide was still current.
 */
class ChannelGuide(
    val programs: Map<String, List<GuideProgram>>,
    val error: UiText? = null,
    val cache: CachedGuide? = null,
    val unchanged: Boolean = false
)

/** HTTP validators of a guide file, sent back to only download it again when it changed. */
class GuideValidator(val etag: String?, val lastModified: String?) {
    val isEmpty: Boolean get() = etag == null && lastModified == null
}

/**
 * Guide of a source as matched to its channels: [fingerprint] of the channels and guide URLs it was
 * matched with, [validators] by hashed guide URL.
 */
class CachedGuide(
    val fingerprint: String,
    val savedAt: Long,
    val validators: Map<String, GuideValidator>,
    val programs: Map<String, List<GuideProgram>>
)

/**
 * Downloads an XMLTV guide and matches it to channels: by tvg-id, else by normalized name.
 */
object GuideLoader {

    const val PAST_WINDOW_MS = 3 * 60 * 60 * 1000L
    private const val FUTURE_WINDOW_MS = 30 * 60 * 60 * 1000L
    // A guide covers 30 hours from its download: past this age it is downloaded again in any case
    private const val MAX_REUSE_MS = 12 * 60 * 60 * 1000L
    private const val HTTP_NOT_MODIFIED = 304

    /**
     * Guide of [channels] from [urls]. With a [previous] guide matched to the same channels and recent
     * enough, each file is requested conditionally, and the previous guide is kept when none changed.
     */
    suspend fun load(
        urls: List<String>,
        channels: List<Channel>,
        userAgent: String,
        previous: CachedGuide? = null,
        now: Long = System.currentTimeMillis()
    ): ChannelGuide = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext ChannelGuide(emptyMap())

        val fingerprint = fingerprint(urls, channels)
        val reusable = previous?.takeIf { it.fingerprint == fingerprint && now - it.savedAt in 0..MAX_REUSE_MS }
        val wantedIds = channels.mapNotNull { it.tvgId?.lowercase() }.toHashSet()
        val wantedNames = channels.map { EpgResolver.normalizeName(it.name) }.toHashSet()
        val byUrl = HashMap<String, Map<String, List<GuideProgram>>>()
        val validators = HashMap<String, GuideValidator>()
        val unchangedUrls = mutableListOf<String>()
        val errors = mutableListOf<UiText>()

        suspend fun fetch(url: String, validator: GuideValidator?) {
            ensureActive()
            try {
                val result = download(url, userAgent, validator) { input ->
                    XmltvParser.parse(input, now - PAST_WINDOW_MS, now + FUTURE_WINDOW_MS) { id, names ->
                        id.lowercase() in wantedIds ||
                            EpgResolver.normalizeName(id) in wantedNames ||
                            names.any { EpgResolver.normalizeName(it) in wantedNames }
                    }
                }
                if (result == null) {
                    unchangedUrls += url
                } else {
                    byUrl[url] = match(result.first, channels)
                    if (!result.second.isEmpty) validators[urlKey(url)] = result.second
                }
            } catch (e: Exception) {
                // A cancelled load stops here (the cancelled call fails with an IOException)
                ensureActive()
                errors += e.toUiText()
            }
        }

        urls.forEach { url -> fetch(url, reusable?.validators?.get(urlKey(url))) }
        if (reusable != null && unchangedUrls.size == urls.size) {
            return@withContext ChannelGuide(reusable.programs, cache = reusable, unchanged = true)
        }
        // Some files changed: the unchanged ones are downloaded again, to merge them in order
        unchangedUrls.toList().forEach { url -> fetch(url, null) }

        // Several guides possible (url-tvg="a.xml,b.xml"): the first one covering a channel wins
        val merged = HashMap<String, List<GuideProgram>>()
        urls.forEach { url -> byUrl[url]?.forEach { (channelId, programs) -> merged.putIfAbsent(channelId, programs) } }

        val error = if (merged.isEmpty() && errors.isNotEmpty()) UiText.Res(R.string.error_guide_unavailable, errors.first()) else null
        val cache = if (errors.isEmpty()) CachedGuide(fingerprint, now, validators, merged) else null
        ChannelGuide(merged, error, cache)
    }

    /** Identity of the channels and guide URLs a guide is matched with. */
    internal fun fingerprint(urls: List<String>, channels: List<Channel>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(text: String?) {
            digest.update(text.orEmpty().toByteArray())
            digest.update(0)
        }
        urls.forEach(::add)
        channels.forEach { channel ->
            add(channel.id)
            add(channel.tvgId)
            add(channel.name)
        }
        return hex(digest.digest())
    }

    /** Key of a guide URL in the cache: hashed, the URL may embed credentials. */
    internal fun urlKey(url: String): String = hex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray()))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Downloaded and read file with its validators, null when the server answered that it did not change. */
    private suspend fun <T> download(
        url: String,
        userAgent: String,
        validator: GuideValidator?,
        read: (java.io.InputStream) -> T
    ): Pair<T, GuideValidator>? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).get().apply {
            validator?.etag?.let { header("If-None-Match", it) }
            validator?.lastModified?.let { header("If-Modified-Since", it) }
        }.build()
        return HttpClients.base.newCall(request).executeCancellable { response ->
            if (response.code == HTTP_NOT_MODIFIED && validator != null) return@executeCancellable null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            read(response.body.byteStream()) to GuideValidator(response.header("ETag"), response.header("Last-Modified"))
        }
    }

    private fun match(guide: XmltvGuide, channels: List<Channel>): Map<String, List<GuideProgram>> {
        if (guide.programs.isEmpty()) return emptyMap()

        val byId = HashMap<String, String>()
        val byName = HashMap<String, String>()
        guide.programs.keys.forEach { key ->
            byId.putIfAbsent(key.lowercase(), key)
            byName.putIfAbsent(EpgResolver.normalizeName(key), key)
            guide.displayNames[key].orEmpty().forEach { byName.putIfAbsent(EpgResolver.normalizeName(it), key) }
        }

        val result = HashMap<String, List<GuideProgram>>()
        channels.forEach { channel ->
            val key = channel.tvgId?.lowercase()?.let { byId[it] }
                ?: byName[EpgResolver.normalizeName(channel.name)]
            key?.let { guide.programs[it] }?.let { result[channel.id] = it }
        }
        return result
    }
}
