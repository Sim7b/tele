package io.github.sim7b.tele.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen refresh rate matching the video: 50 Hz for 25 or 50 frames per second (European channels), 23.976
 * or 24 Hz for movies, 59.94 or 60 Hz for American channels. A 25 fps video on a 60 Hz screen repeats some
 * frames more than others: the movements judder (sport, scrolling texts).
 */
object DisplayModes {

    /** A mode of the screen, as Android lists them. */
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    // 59.94 and 60 Hz (0.1 % apart) count as the same rate: one frame dropped every 16 s is invisible
    private const val TOLERANCE = 0.002f
    private const val MAX_MULTIPLE = 4

    /**
     * The mode for a video of [fps] frames per second, same resolution as [current]: an exact multiple of
     * its rate, between 47 and 61 Hz first (48, 50, 59.94, 60 Hz: no flicker), else the lowest one. Null to
     * keep [current] (already right, unknown rate, nothing better).
     */
    fun pick(modes: List<Mode>, current: Mode, fps: Float?): Mode? {
        if (fps == null || !fps.isFinite() || fps < MIN_FPS || fps > MAX_FPS) return null
        if (matches(current.refreshRate, fps)) return null
        val candidates = modes.filter { it.width == current.width && it.height == current.height && matches(it.refreshRate, fps) }
        return candidates.minWithOrNull(
            compareBy<Mode> { if (it.refreshRate in 47f..61f) 0 else 1 }
                .thenBy { error(it.refreshRate, fps) }
                .thenBy { it.refreshRate }
        )
    }

    /** True when [refreshRate] shows every frame of [fps] the same number of times. */
    fun matches(refreshRate: Float, fps: Float): Boolean = error(refreshRate, fps) <= TOLERANCE

    private fun error(refreshRate: Float, fps: Float): Float {
        val multiple = (refreshRate / fps).roundToInt()
        if (multiple !in 1..MAX_MULTIPLE) return Float.MAX_VALUE
        return abs(refreshRate - multiple * fps) / refreshRate
    }

    private const val MIN_FPS = 10f
    private const val MAX_FPS = 120f
}
