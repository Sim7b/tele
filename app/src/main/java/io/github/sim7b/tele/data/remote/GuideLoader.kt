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
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Channels loaded from an Xtream or M3U source, with the XMLTV guide links to use.
 */
class SourceChannels(
    val channels: List<Channel>,
    val guideUrls: List<String>,
    val userName: String? = null
)

/**
 * Programs matched to channels (by application channel id).
 */
class ChannelGuide(
    val programs: Map<String, List<GuideProgram>>,
    val error: UiText? = null
)

/**
 * Downloads an XMLTV guide and matches it to channels: by tvg-id, else by normalized name.
 */
object GuideLoader {

    private const val PAST_WINDOW_MS = 3 * 60 * 60 * 1000L
    private const val FUTURE_WINDOW_MS = 30 * 60 * 60 * 1000L

    suspend fun load(urls: List<String>, channels: List<Channel>, userAgent: String): ChannelGuide =
        withContext(Dispatchers.IO) {
            if (urls.isEmpty()) return@withContext ChannelGuide(emptyMap())

            val wantedIds = channels.mapNotNull { it.tvgId?.lowercase() }.toHashSet()
            val wantedNames = channels.map { EpgResolver.normalizeName(it.name) }.toHashSet()
            val now = System.currentTimeMillis()
            val merged = HashMap<String, List<GuideProgram>>()
            val errors = mutableListOf<UiText>()

            // Several guides possible (url-tvg="a.xml,b.xml"): the first one covering a channel wins
            for (url in urls) {
                try {
                    val guide = download(url, userAgent) { input ->
                        XmltvParser.parse(input, now - PAST_WINDOW_MS, now + FUTURE_WINDOW_MS) { id, names ->
                            id.lowercase() in wantedIds ||
                                EpgResolver.normalizeName(id) in wantedNames ||
                                names.any { EpgResolver.normalizeName(it) in wantedNames }
                        }
                    }
                    match(guide, channels).forEach { (channelId, programs) -> merged.putIfAbsent(channelId, programs) }
                } catch (e: Exception) {
                    errors += e.toUiText()
                }
            }

            val error = if (merged.isEmpty() && errors.isNotEmpty()) UiText.Res(R.string.error_guide_unavailable, errors.first()) else null
            ChannelGuide(merged, error)
        }

    private fun <T> download(url: String, userAgent: String, read: (java.io.InputStream) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
        HttpClients.base.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            return read(body.byteStream())
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
