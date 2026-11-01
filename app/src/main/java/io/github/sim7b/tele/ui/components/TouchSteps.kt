package io.github.sim7b.tele.ui.components

/** Arithmetic of the touch gestures (phones and tablets), apart from Compose so it runs in JVM tests. */
object TouchSteps {

    /** Third of the screen a tap fell in. */
    enum class Side { LEFT, CENTER, RIGHT }

    /** Third of [width] holding [x]. */
    fun side(x: Float, width: Float): Side = when {
        width <= 0f -> Side.CENTER
        x < width / 3f -> Side.LEFT
        x >= width * 2f / 3f -> Side.RIGHT
        else -> Side.CENTER
    }

    /** Vertical swipe of [dy] (negative: up): +1 past [threshold] up (next), -1 past it down (previous), else 0. */
    fun swipe(dy: Float, threshold: Float): Int = when {
        threshold <= 0f -> 0
        dy <= -threshold -> 1
        dy >= threshold -> -1
        else -> 0
    }

    /**
     * Whole steps of [stepPx] in a horizontal drag of [dx] (negative: to the left). Dragging to the left
     * moves forward (positive steps), like turning a page; the rest of the drag is kept for the next move.
     */
    fun steps(dx: Float, stepPx: Float): Int = if (stepPx <= 0f) 0 else -(dx / stepPx).toInt()

    /** Drag left over once [steps] steps of [stepPx] are taken from [dx]. */
    fun remainder(dx: Float, steps: Int, stepPx: Float): Float = dx + steps * stepPx

    /** Position of a progress bar of [width] touched at [x], for a duration of [durationMs]. */
    fun seekPosition(x: Float, width: Float, durationMs: Long): Long {
        if (width <= 0f || durationMs <= 0L) return 0L
        return ((x / width).coerceIn(0f, 1f) * durationMs).toLong()
    }
}
