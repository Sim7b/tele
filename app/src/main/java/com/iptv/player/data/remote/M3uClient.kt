package com.iptv.player.data.remote

import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.parser.M3uParser
import com.iptv.player.data.parser.M3uPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Source playlist M3U / M3U8 (lien direct), avec guide XMLTV facultatif.
 */
object M3uClient {

    suspend fun loadChannels(config: SourceConfig): Result<SourceChannels> = withContext(Dispatchers.IO) {
        try {
            val playlist = download(config)
            if (playlist.entries.isEmpty()) {
                return@withContext Result.failure(IOException("Aucune chaîne en direct dans cette playlist"))
            }

            // Identifiants stables (favoris, flux choisis) : tvg-id ou nom, numérotés en cas de doublon
            val idPrefix = "m3u-${config.id.take(8)}-"
            val seen = HashMap<String, Int>()
            val channels = playlist.entries.mapIndexed { index, entry ->
                val key = (entry.tvgId ?: entry.name).lowercase()
                val occurrence = seen.merge(key, 1, Int::plus) ?: 1
                Channel(
                    id = idPrefix + Integer.toHexString(key.hashCode()) + if (occurrence > 1) "-$occurrence" else "",
                    name = entry.name,
                    group = entry.group ?: "Autres",
                    logoUrl = entry.logo,
                    streamUrl = entry.url,
                    tvgId = entry.tvgId ?: entry.tvgName,
                    number = entry.number ?: (index + 1)
                )
            }

            // Guide saisi par l'utilisateur, sinon celui annoncé par la playlist (url-tvg)
            val guideUrls = config.epgUrl?.takeIf { it.isNotBlank() }?.let { listOf(it.trim()) } ?: playlist.epgUrls
            Result.success(SourceChannels(channels, guideUrls))
        } catch (e: Exception) {
            Result.failure(IOException(HttpClients.friendlyError(e), e))
        }
    }

    /** Vérifie la playlist (test de connexion) : nombre de chaînes en direct et présence d'un guide. */
    suspend fun probe(config: SourceConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            val playlist = download(config)
            if (playlist.entries.isEmpty()) {
                return@withContext Result.failure(IOException("Aucune chaîne en direct dans cette playlist"))
            }
            val guide = when {
                !config.epgUrl.isNullOrBlank() -> "guide XMLTV renseigné"
                playlist.epgUrls.isNotEmpty() -> "guide fourni par la playlist"
                else -> "sans guide EPG"
            }
            Result.success("Playlist valide · ${playlist.entries.size} chaînes · $guide")
        } catch (e: Exception) {
            Result.failure(IOException(HttpClients.friendlyError(e), e))
        }
    }

    private fun download(config: SourceConfig): M3uPlaylist {
        val request = Request.Builder()
            .url(config.normalizedUrl)
            .header("User-Agent", config.customUserAgent?.takeIf { it.isNotBlank() } ?: HttpClients.DEFAULT_USER_AGENT)
            .get()
            .build()
        HttpClients.base.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        401, 403 -> "Accès à la playlist refusé (HTTP ${response.code})"
                        404 -> "Playlist introuvable (HTTP 404)"
                        else -> "Erreur serveur : HTTP ${response.code}"
                    }
                )
            }
            val body = response.body ?: throw IOException("Réponse vide")
            return M3uParser.parse(body.byteStream())
        }
    }
}
