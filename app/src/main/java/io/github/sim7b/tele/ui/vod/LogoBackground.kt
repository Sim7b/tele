package io.github.sim7b.tele.ui.vod

/**
 * Whether a logo has its own opaque background (a wordmark on a white or colored box instead of a transparent
 * one): drawn in one color, such a logo would only show a filled rectangle, so its name is shown instead.
 * Reads the alpha of points around the border of the image; pure, for the tests.
 */
internal object LogoBackground {
    private const val SAMPLES_PER_SIDE = 12
    private const val OPAQUE_ALPHA = 200

    // Share of the border points that must be opaque: a wordmark touching an edge stays a transparent logo
    private const val OPAQUE_SHARE = 0.8

    fun isOpaque(width: Int, height: Int, alphaAt: (x: Int, y: Int) -> Int): Boolean {
        if (width <= 0 || height <= 0) return false
        var opaque = 0
        var total = 0
        for (i in 0 until SAMPLES_PER_SIDE) {
            val x = (i * (width - 1)) / (SAMPLES_PER_SIDE - 1)
            val y = (i * (height - 1)) / (SAMPLES_PER_SIDE - 1)
            listOf(x to 0, x to height - 1, 0 to y, width - 1 to y).forEach { (px, py) ->
                total++
                if (alphaAt(px, py) >= OPAQUE_ALPHA) opaque++
            }
        }
        return opaque >= total * OPAQUE_SHARE
    }
}
