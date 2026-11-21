package io.github.sim7b.tele.data.model

import org.json.JSONObject

/** Size of the subtitles, relative to the default size of the player. */
enum class SubtitleSize(val scale: Float) {
    SMALL(0.85f), NORMAL(1.1f), LARGE(1.35f), HUGE(1.65f);

    fun next(): SubtitleSize = entries[(ordinal + 1) % entries.size]
}

/** Color of the letters (ARGB). */
enum class SubtitleColor(val argb: Int) {
    WHITE(0xFFFFFFFF.toInt()), YELLOW(0xFFFFE14D.toInt()), CYAN(0xFF7FE3FF.toInt()), GREEN(0xFF8EF08E.toInt());

    fun next(): SubtitleColor = entries[(ordinal + 1) % entries.size]
}

/** What is behind the letters: an outline only, a shadow, a light box or an opaque one. */
enum class SubtitleBackground {
    OUTLINE, SHADOW, BOX, OPAQUE;

    fun next(): SubtitleBackground = entries[(ordinal + 1) % entries.size]
}

/** Height of the subtitles: at the bottom of the picture, or higher (above a banner burnt into the picture). */
enum class SubtitlePosition(val bottomFraction: Float) {
    BOTTOM(0.08f), RAISED(0.18f), HIGH(0.28f);

    fun next(): SubtitlePosition = entries[(ordinal + 1) % entries.size]
}

/** How the subtitles look in every player (movies, series, channels), whatever the file or the system settings. */
data class SubtitleAppearance(
    val size: SubtitleSize = SubtitleSize.NORMAL,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.OUTLINE,
    val position: SubtitlePosition = SubtitlePosition.BOTTOM
) {
    fun toJson(): String = JSONObject()
        .put("size", size.name).put("color", color.name).put("background", background.name).put("position", position.name)
        .toString()

    companion object {
        /** Saved by [toJson]; the default for what is missing or unknown. */
        fun fromJson(raw: String?): SubtitleAppearance {
            val json = try {
                JSONObject(raw ?: return SubtitleAppearance())
            } catch (e: Exception) {
                return SubtitleAppearance()
            }
            fun <T : Enum<T>> pick(values: Array<T>, key: String, default: T): T =
                values.firstOrNull { it.name == json.optString(key) } ?: default
            return SubtitleAppearance(
                size = pick(SubtitleSize.entries.toTypedArray(), "size", SubtitleSize.NORMAL),
                color = pick(SubtitleColor.entries.toTypedArray(), "color", SubtitleColor.WHITE),
                background = pick(SubtitleBackground.entries.toTypedArray(), "background", SubtitleBackground.OUTLINE),
                position = pick(SubtitlePosition.entries.toTypedArray(), "position", SubtitlePosition.BOTTOM)
            )
        }
    }
}
