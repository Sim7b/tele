package io.github.sim7b.tele.data.model

/**
 * Buffer of the player: how much is kept ahead of the playback position, and how much is needed to start or to
 * resume after a stall. Larger buffers ride out a weak connection or a busy provider, smaller ones spare the
 * memory of small devices. The larger ones are also capped in size, so a 4K stream cannot fill the memory.
 */
enum class BufferSize(
    val minMs: Int,
    val maxMs: Int,
    // Buffered before a channel starts: the zap speed
    val startMs: Int,
    val afterStallMs: Int,
    // True: the durations alone count; false: the default size caps of the player apply too
    val timeOverSize: Boolean
) {
    SMALL(8_000, 15_000, 500, 2_000, true),
    NORMAL(15_000, 30_000, 500, 2_500, true),
    LARGE(30_000, 60_000, 1_000, 5_000, false),
    MAXIMUM(50_000, 120_000, 2_000, 8_000, false);

    /** The next size, for a setting row that goes through them. */
    fun next(): BufferSize = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(name: String?): BufferSize = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}
