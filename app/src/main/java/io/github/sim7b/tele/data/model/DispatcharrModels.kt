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

    companion object {
        /**
         * Sums the limited, active profiles of the active accounts (`/api/m3u/accounts/` items).
         * A profile with `max_streams` 0 is unlimited and not counted; null when no profile has a limit.
         */
        fun fromAccounts(accounts: List<JSONObject>): StreamSlots? {
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
                    used += profile.optInt("current_viewers", 0).coerceIn(0, max)
                }
            }
            return if (total > 0) StreamSlots(used, total) else null
        }
    }
}
