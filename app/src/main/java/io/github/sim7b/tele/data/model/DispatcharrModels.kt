package io.github.sim7b.tele.data.model

import org.json.JSONObject

/**
 * Channel returned by the Dispatcharr API.
 */
data class DispatcharrChannel(
    val id: String,
    val uuid: String,
    val channelNumber: Int? = null,
    val name: String,
    val group: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    // Resolved EPG key: tvg_id of the linked EPGData (epg_data_id) or tvg_id of the channel
    val tvgId: String = "",
    val isRadio: Boolean = false,
    val isFavorite: Boolean = false
) {
    fun toChannel(): Channel {
        return Channel(
            id = uuid,
            name = name,
            group = group,
            logoUrl = logoUrl,
            streamUrl = streamUrl,
            tvgId = tvgId.ifBlank { uuid },
            isFavorite = isFavorite,
            serverId = id.takeIf { it != uuid },
            number = channelNumber
        )
    }
}

/**
 * Dispatcharr EPGData entry (/api/epg/epgdata/): links the epg_data_id of a channel to the tvg_id of the grid.
 */
data class DispatcharrEpgData(
    val id: Int,
    val tvgId: String,
    val name: String
)

/**
 * EPG guide program, from the Dispatcharr grid or an XMLTV guide (Xtream / M3U).
 */
data class GuideProgram(
    val id: String,
    val channelUuid: String,
    val title: String,
    val description: String? = null,
    val startTimeIso: String,
    val endTimeIso: String,
    val posterUrl: String? = null,
    val rating: String? = null,
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val category: String? = null
)

/**
 * 16:9 artwork and category of a program (missing from the EPG grid, provided by the program search).
 */
data class ProgramArtwork(
    val imageUrl: String?,
    val category: String?
)

/**
 * EPG grid of a channel.
 */
data class DispatcharrEpgChannel(
    val channelUuid: String,
    val channelName: String,
    val programs: List<GuideProgram>
)

/**
 * Playback slots of the Dispatcharr provider accounts: [total] simultaneous streams allowed by the
 * active M3U profiles that have a limit, of which [used] are currently taken.
 */
data class StreamSlots(val used: Int, val total: Int) {
    val free: Int
        get() = (total - used).coerceAtLeast(0)

    /**
     * A home preview may start: it would still leave a free connection for the channel the user opens.
     * A preview already playing holds one of the used connections, given back when it moves to another card.
     */
    fun allowsPreview(previewing: Boolean): Boolean = free + (if (previewing) 1 else 0) >= MIN_FREE_FOR_PREVIEW

    companion object {
        private const val MIN_FREE_FOR_PREVIEW = 2

        /**
         * Sums the limited, active profiles of the active accounts (`/api/m3u/accounts/` items).
         * A profile with `max_streams` 0 is unlimited and not counted; null when no profile has a limit.
         * The connections in use come from [stats] (`/proxy/stats/`: live channels and movie or episode
         * connections, each with its provider profile, read live by the server); without it, from the
         * `current_viewers` of the profiles, which the server does not keep up to date.
         */
        fun fromAccounts(accounts: List<JSONObject>, stats: JSONObject? = null): StreamSlots? {
            val inUse = stats?.let { connectionsByProfile(it) }
            var used = 0
            var total = 0
            for (account in accounts) {
                if (!account.optBoolean("is_active", true)) continue
                val profiles = account.optJSONArray("profiles") ?: continue
                for (i in 0 until profiles.length()) {
                    val profile = profiles.optJSONObject(i) ?: continue
                    if (!profile.optBoolean("is_active", true)) continue
                    val max = profile.optInt("max_streams", 0)
                    if (max <= 0) continue
                    total += max
                    val connections = if (inUse != null) inUse[profile.optString("id")] ?: 0 else profile.optInt("current_viewers", 0)
                    used += connections.coerceIn(0, max)
                }
            }
            return if (total > 0) StreamSlots(used, total) else null
        }

        /** Provider connections open by profile id: one per live channel, one per movie or episode connection. */
        internal fun connectionsByProfile(stats: JSONObject): Map<String, Int> {
            val counts = HashMap<String, Int>()
            fun count(item: JSONObject?) {
                val id = item?.opt("m3u_profile_id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: return
                counts[id] = (counts[id] ?: 0) + 1
            }
            stats.optJSONObject("live")?.optJSONArray("channels")?.let { channels ->
                for (i in 0 until channels.length()) count(channels.optJSONObject(i))
            }
            stats.optJSONObject("vod")?.optJSONArray("vod_connections")?.let { contents ->
                for (i in 0 until contents.length()) {
                    val connections = contents.optJSONObject(i)?.optJSONArray("connections") ?: continue
                    for (j in 0 until connections.length()) count(connections.optJSONObject(j))
                }
            }
            return counts
        }
    }
}
