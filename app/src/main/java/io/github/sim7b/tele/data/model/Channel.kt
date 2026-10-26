package io.github.sim7b.tele.data.model

/**
 * Selectable stream of a channel. Null [id] = automatic mode (Dispatcharr picks one and fails over on outage).
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
    // Actual timestamps (epoch ms) to recompute the progress and detect the end of the program
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
    // Dispatcharr numeric id (required to list the channel streams)
    val serverId: String? = null,
    val number: Int? = null
) {
    val activeStreamUrl: String
        get() = selectedStreamUrl ?: streamUrl

    companion object {
        /** Group of the channels without a category: displayed with a localized label (see groupLabel). */
        const val NO_GROUP = ""
    }
}
