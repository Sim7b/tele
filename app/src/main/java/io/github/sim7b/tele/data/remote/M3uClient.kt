package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.UserFacingException
import io.github.sim7b.tele.data.failure
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.parser.M3uEntry
import io.github.sim7b.tele.data.parser.M3uParser
import io.github.sim7b.tele.data.parser.M3uPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * M3U / M3U8 playlist source (direct link), with an optional XMLTV guide.
 */
object M3uClient {

    suspend fun loadChannels(config: SourceConfig): Result<SourceChannels> = withContext(Dispatchers.IO) {
        try {
            val playlist = download(config)
            if (playlist.entries.isEmpty()) {
                return@withContext failure(R.string.error_playlist_no_live_channel)
            }

            val channels = toChannels(playlist.entries, "m3u-${config.id.take(8)}-")

            // Guide entered by the user, else the one announced by the playlist (url-tvg)
            val guideUrls = config.epgUrl?.takeIf { it.isNotBlank() }?.let { listOf(it.trim()) } ?: playlist.epgUrls
            Result.success(SourceChannels(channels, guideUrls))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Checks the playlist (connection test): number of live channels and presence of a guide. */
    suspend fun probe(config: SourceConfig): Result<UiText> = withContext(Dispatchers.IO) {
        try {
            val playlist = download(config)
            if (playlist.entries.isEmpty()) {
                return@withContext failure(R.string.error_playlist_no_live_channel)
            }
            val guide = when {
                !config.epgUrl.isNullOrBlank() -> R.string.playlist_guide_entered
                playlist.epgUrls.isNotEmpty() -> R.string.playlist_guide_from_playlist
                else -> R.string.playlist_guide_none
            }
            Result.success(UiText.Plural(R.plurals.playlist_valid, playlist.entries.size, playlist.entries.size, UiText.Res(guide)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Stable ids (favorites, selected streams): hash of the tvg-id or name, numbered when duplicated.
     * The 32-bit hash collides on large playlists: a colliding id gets a suffix, so ids stay unique
     * (lazy list keys) while the ids of existing installs are kept.
     */
    internal fun toChannels(entries: List<M3uEntry>, idPrefix: String): List<Channel> {
        val seen = HashMap<String, Int>()
        val used = HashSet<String>(entries.size * 2)
        return entries.mapIndexed { index, entry ->
            val key = (entry.tvgId ?: entry.name).lowercase()
            val occurrence = seen.merge(key, 1, Int::plus) ?: 1
            val baseId = idPrefix + Integer.toHexString(key.hashCode()) + if (occurrence > 1) "-$occurrence" else ""
            var id = baseId
            var collision = 1
            while (!used.add(id)) id = "$baseId~${++collision}"
            Channel(
                id = id,
                name = entry.name,
                group = entry.group ?: "Other",
                logoUrl = entry.logo,
                streamUrl = entry.url,
                tvgId = entry.tvgId ?: entry.tvgName,
                number = entry.number ?: (index + 1)
            )
        }
    }

    private suspend fun download(config: SourceConfig): M3uPlaylist {
        val request = Request.Builder()
            .url(config.normalizedUrl)
            .header("User-Agent", config.customUserAgent?.takeIf { it.isNotBlank() } ?: HttpClients.DEFAULT_USER_AGENT)
            .get()
            .build()
        return HttpClients.base.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) {
                throw UserFacingException(
                    when (response.code) {
                        401, 403 -> UiText.Res(R.string.error_playlist_access_refused, response.code)
                        404 -> UiText.Res(R.string.error_playlist_not_found)
                        else -> UiText.Res(R.string.error_server_http, response.code)
                    }
                )
            }
            M3uParser.parse(response.body.byteStream())
        }
    }
}
