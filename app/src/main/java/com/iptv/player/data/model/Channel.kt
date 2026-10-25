package com.iptv.player.data.model

/**
 * Flux sélectionnable d'une chaîne. [id] null = mode automatique (Dispatcharr choisit et bascule en cas de panne).
 */
data class StreamSource(
    val name: String,
    val url: String,
    val id: String? = null,
    val provider: String? = null,
    val qualityTags: List<String> = emptyList(),
    val details: String? = null
)

data class EpgProgram(
    val title: String,
    val category: String? = null,
    val startTime: String,
    val endTime: String,
    val progress: Float = 0f,
    val description: String? = null,
    val posterUrl: String? = null,
    val nextTitle: String? = null,
    val nextStartTime: String? = null,
    // Horodatages réels (epoch ms) pour recalculer la progression et détecter la fin du programme
    val startMillis: Long = 0L,
    val endMillis: Long = 0L
)

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    val tvgId: String? = null,
    val isFavorite: Boolean = false,
    val currentProgram: EpgProgram? = null,
    val availableStreams: List<StreamSource> = emptyList(),
    val selectedStreamUrl: String? = null,
    val selectedStreamName: String? = null,
    // Identifiant numérique Dispatcharr (requis pour lister les flux de la chaîne)
    val serverId: String? = null,
    val number: Int? = null
) {
    val activeStreamUrl: String
        get() = selectedStreamUrl ?: streamUrl
}
