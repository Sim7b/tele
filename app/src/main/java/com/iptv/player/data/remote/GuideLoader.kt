package com.iptv.player.data.remote

import com.iptv.player.data.epg.EpgResolver
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.GuideProgram
import com.iptv.player.data.parser.XmltvGuide
import com.iptv.player.data.parser.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Chaînes chargées depuis une source Xtream ou M3U, avec les liens de guide XMLTV à utiliser.
 */
class SourceChannels(
    val channels: List<Channel>,
    val guideUrls: List<String>,
    val userName: String? = null
)

/**
 * Programmes associés aux chaînes (par identifiant de chaîne de l'application).
 */
class ChannelGuide(
    val programs: Map<String, List<GuideProgram>>,
    val error: String? = null
)

/**
 * Téléchargement d'un guide XMLTV et association à des chaînes : par tvg-id, sinon par nom normalisé.
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
            val errors = mutableListOf<String>()

            // Plusieurs guides possibles (url-tvg="a.xml,b.xml") : le premier qui couvre une chaîne l'emporte
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
                    errors += e.localizedMessage ?: "erreur réseau"
                }
            }

            val error = if (merged.isEmpty() && errors.isNotEmpty()) "Guide EPG indisponible : ${errors.first()}" else null
            ChannelGuide(merged, error)
        }

    private fun <T> download(url: String, userAgent: String, read: (java.io.InputStream) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
        HttpClients.base.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Réponse vide")
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
