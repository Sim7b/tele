package io.github.sim7b.tele.data.model

/**
 * Focus time on a home card before its muted preview starts, or no preview. "Immediate" still waits a
 * moment: browsing the grid with the remote must not open a provider connection on every card passed.
 */
enum class PreviewDelay(val millis: Long?, val seconds: Int) {
    OFF(null, -1),
    IMMEDIATE(300L, 0),
    ONE_SECOND(1_000L, 1),
    TWO_SECONDS(2_000L, 2),
    THREE_SECONDS(3_000L, 3),
    FIVE_SECONDS(5_000L, 5);

    companion object {
        val DEFAULT = TWO_SECONDS

        fun fromName(name: String?): PreviewDelay? = entries.firstOrNull { it.name == name }
    }
}
