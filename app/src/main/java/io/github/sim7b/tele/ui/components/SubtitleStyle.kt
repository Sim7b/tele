package io.github.sim7b.tele.ui.components

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * Subtitles in white letters with a dark outline, straight on the picture: no opaque box behind them
 * (the default of many TVs), whatever the system caption settings or the styles embedded in the file.
 */
@OptIn(UnstableApi::class)
fun SubtitleView.applyTeleStyle() {
    setStyle(
        CaptionStyleCompat(
            Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.BLACK,
            null
        )
    )
    // Colors and boxes of the file (ASS, WebVTT, TTML) dropped, its positions kept
    setApplyEmbeddedStyles(false)
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * TEXT_SCALE)
}

/** A bit larger than the default: the outline thins the letters. */
private const val TEXT_SCALE = 1.1f
