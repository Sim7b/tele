package io.github.sim7b.tele.ui.components

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.sim7b.tele.data.model.SubtitleAppearance
import io.github.sim7b.tele.data.model.SubtitleBackground

/**
 * Subtitles as set in the settings ([SubtitleAppearance]; by default white letters with a dark outline, straight on
 * the picture), whatever the system caption settings or the styles embedded in the file.
 */
@OptIn(UnstableApi::class)
fun SubtitleView.applyTeleStyle(appearance: SubtitleAppearance = SubtitleAppearance()) {
    val (background, edgeType) = when (appearance.background) {
        SubtitleBackground.OUTLINE -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleBackground.SHADOW -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleBackground.BOX -> Color.argb(0x99, 0, 0, 0) to CaptionStyleCompat.EDGE_TYPE_NONE
        SubtitleBackground.OPAQUE -> Color.BLACK to CaptionStyleCompat.EDGE_TYPE_NONE
    }
    setStyle(CaptionStyleCompat(appearance.color.argb, background, Color.TRANSPARENT, edgeType, Color.BLACK, null))
    // Colors and boxes of the file (ASS, WebVTT, TTML) dropped, its positions kept
    setApplyEmbeddedStyles(false)
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * appearance.size.scale)
    setBottomPaddingFraction(appearance.position.bottomFraction)
}
