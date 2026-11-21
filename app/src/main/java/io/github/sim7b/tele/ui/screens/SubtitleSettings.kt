package io.github.sim7b.tele.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.model.SubtitleAppearance
import io.github.sim7b.tele.data.model.SubtitleBackground
import io.github.sim7b.tele.data.model.SubtitleColor
import io.github.sim7b.tele.data.model.SubtitlePosition
import io.github.sim7b.tele.data.model.SubtitleSize

/** A picture with a line of subtitles as the players will show it ([SubtitleAppearance]). */
@Composable
internal fun SubtitlePreview(appearance: SubtitleAppearance, modifier: Modifier = Modifier) {
    val color = Color(appearance.color.argb)
    val edge = when (appearance.background) {
        SubtitleBackground.OUTLINE -> Shadow(Color.Black, Offset.Zero, blurRadius = 6f)
        SubtitleBackground.SHADOW -> Shadow(Color.Black, Offset(3f, 3f), blurRadius = 4f)
        else -> null
    }
    val box = when (appearance.background) {
        SubtitleBackground.BOX -> Color.Black.copy(alpha = 0.6f)
        SubtitleBackground.OPAQUE -> Color.Black
        else -> Color.Transparent
    }
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A6073), Color(0xFF16222A), Color(0xFF8E9EAB))))
    ) {
        Text(
            text = stringResource(R.string.settings_subtitle_sample),
            style = TextStyle(
                color = color,
                fontSize = (18 * appearance.size.scale).sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                shadow = edge
            ),
            modifier = Modifier
                .padding(bottom = (150 * appearance.position.bottomFraction).dp)
                .background(box)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun subtitleSizeLabel(size: SubtitleSize): String = stringResource(
    when (size) {
        SubtitleSize.SMALL -> R.string.settings_subtitle_size_small
        SubtitleSize.NORMAL -> R.string.settings_subtitle_size_normal
        SubtitleSize.LARGE -> R.string.settings_subtitle_size_large
        SubtitleSize.HUGE -> R.string.settings_subtitle_size_huge
    }
)

@Composable
internal fun subtitleColorLabel(color: SubtitleColor): String = stringResource(
    when (color) {
        SubtitleColor.WHITE -> R.string.settings_subtitle_color_white
        SubtitleColor.YELLOW -> R.string.settings_subtitle_color_yellow
        SubtitleColor.CYAN -> R.string.settings_subtitle_color_cyan
        SubtitleColor.GREEN -> R.string.settings_subtitle_color_green
    }
)

@Composable
internal fun subtitleBackgroundLabel(background: SubtitleBackground): String = stringResource(
    when (background) {
        SubtitleBackground.OUTLINE -> R.string.settings_subtitle_background_outline
        SubtitleBackground.SHADOW -> R.string.settings_subtitle_background_shadow
        SubtitleBackground.BOX -> R.string.settings_subtitle_background_box
        SubtitleBackground.OPAQUE -> R.string.settings_subtitle_background_opaque
    }
)

@Composable
internal fun subtitlePositionLabel(position: SubtitlePosition): String = stringResource(
    when (position) {
        SubtitlePosition.BOTTOM -> R.string.settings_subtitle_position_bottom
        SubtitlePosition.RAISED -> R.string.settings_subtitle_position_raised
        SubtitlePosition.HIGH -> R.string.settings_subtitle_position_high
    }
)
