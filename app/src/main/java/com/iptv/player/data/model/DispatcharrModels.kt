package com.iptv.player.data.model

/**
 * Modèle représentant une chaîne issue de l'API Dispatcharr.
 */
data class DispatcharrChannel(
    val id: String,
    val uuid: String,
    val channelNumber: Int? = null,
    val name: String,
    val group: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    // Clé EPG résolue : tvg_id de l'EPGData lié (epg_data_id) ou tvg_id de la chaîne
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
 * Entrée EPGData Dispatcharr (/api/epg/epgdata/) : relie l'epg_data_id d'une chaîne au tvg_id de la grille.
 */
data class DispatcharrEpgData(
    val id: Int,
    val tvgId: String,
    val name: String
)

/**
 * Programme du guide EPG, issu de la grille Dispatcharr ou d'un guide XMLTV (Xtream / M3U).
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
 * Visuel 16:9 et catégorie d'un programme (absents de la grille EPG, fournis par la recherche de programmes).
 */
data class ProgramArtwork(
    val imageUrl: String?,
    val category: String?
)

/**
 * Grille EPG d'une chaîne pour l'affichage TV Guide.
 */
data class DispatcharrEpgChannel(
    val channelUuid: String,
    val channelName: String,
    val programs: List<GuideProgram>
)

