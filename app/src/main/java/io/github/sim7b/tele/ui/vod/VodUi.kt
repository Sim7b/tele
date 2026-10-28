package io.github.sim7b.tele.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.vod.VodRow
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.data.vod.VodVersion
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.ProgressLine
import io.github.sim7b.tele.ui.components.focusRing
import io.github.sim7b.tele.ui.theme.SurfaceCard
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import java.util.Locale

internal val PosterWidth = 112.dp

/** Width of the posters, from the display settings. */
internal val LocalPosterWidth = compositionLocalOf { PosterWidth }
private const val POSTER_LOOKUP_DELAY_MS = 400L
internal val PosterHeight = 168.dp

/**
 * Poster of a movie or a series (2:3), its title below; the title in the card when there is no image.
 * [progress] draws the bar of a started movie or episode.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PosterCard(
    title: VodTitle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    // Position in a ranking (TMDB trends), drawn in the corner
    rank: Int? = null,
    width: Dp = LocalPosterWidth.current,
    // Hold OK: the actions on the title (collections, watched, continue watching)
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    // Poster looked up when the providers give none (only for the cards actually shown)
    findPoster: (suspend (VodTitle) -> String?)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val poster by produceState(title.posterUrl, title.key) {
        if (value == null && findPoster != null) {
            // Cards scrolled past quickly are not looked up
            delay(POSTER_LOOKUP_DELAY_MS)
            value = findPoster(title)
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(modifier = modifier.width(width)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            modifier = Modifier
                .width(width)
                .height(width * 1.5f),
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = focusRing(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceCard, focusedContainerColor = SurfaceCard)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (poster == null) {
                    Text(
                        text = title.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(10.dp)
                    )
                } else {
                    FadeInImage(url = poster, contentDescription = title.title, modifier = Modifier.fillMaxSize())
                }
                rank?.let {
                    Text(
                        text = it.toString(),
                        style = TextStyle(
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White,
                            shadow = Shadow(androidx.compose.ui.graphics.Color.Black, blurRadius = 8f)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 6.dp, bottom = 2.dp)
                    )
                }
                if (progress != null && progress > 0f) {
                    ProgressLine(
                        progress = progress,
                        height = 3.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
                    )
                }
            }
        }
        Text(
            text = title.title,
            fontSize = 12.sp,
            color = if (focused) TextPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
internal fun rowTitle(row: VodRow): String = when (row.kind) {
    VodRow.Kind.CONTINUE -> stringResource(R.string.vod_row_continue)
    VodRow.Kind.TRENDING -> stringResource(R.string.vod_row_trending)
    VodRow.Kind.MY_LIST -> stringResource(R.string.vod_row_my_list)
    VodRow.Kind.COLLECTION, VodRow.Kind.GENRE -> row.name.orEmpty()
    VodRow.Kind.RECENT_MOVIES -> stringResource(R.string.vod_row_recent_movies)
    VodRow.Kind.RECENT_SERIES -> stringResource(R.string.vod_row_recent_series)
    VodRow.Kind.POPULAR_MOVIES -> stringResource(R.string.vod_row_popular_movies)
    VodRow.Kind.POPULAR_SERIES -> stringResource(R.string.vod_row_popular_series)
    VodRow.Kind.NOW_PLAYING -> stringResource(R.string.vod_row_now_playing)
    VodRow.Kind.UPCOMING -> stringResource(R.string.vod_row_upcoming)
    VodRow.Kind.TOP_MOVIES -> stringResource(R.string.vod_row_top_movies)
    VodRow.Kind.TOP_SERIES -> stringResource(R.string.vod_row_top_series)
}

/** Country name of an ISO 3166 code ("KR" -> Corée du Sud) in the language of the TV. */
internal fun countryName(code: String): String =
    try {
        Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault()).ifBlank { code }
    } catch (e: java.util.IllformedLocaleException) {
        code
    }

/** "2 h 46", "58 min". */
@Composable
internal fun durationLabel(seconds: Int?): String? {
    val minutes = (seconds ?: return null) / 60
    if (minutes <= 0) return null
    return if (minutes >= 60) stringResource(R.string.vod_duration_hours, minutes / 60, minutes % 60)
    else stringResource(R.string.vod_duration_minutes, minutes)
}

/** "★ 8,5" in the language of the TV. */
@Composable
internal fun ratingLabel(rating: Double): String =
    "★ " + String.format(LocalConfiguration.current.locales[0], "%.1f", rating)

/** "1:23:45" / "23:45" for a playback position. */
internal fun positionLabel(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

/** Name of a language code of a provider ("fre", "fra", "fr", "eng") in the language of the TV. */
internal fun languageName(code: String?): String? {
    val normalized = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    // ISO 639-2/B codes unknown to Locale
    val iso = when (normalized) {
        "fre" -> "fr"
        "ger" -> "de"
        "dut" -> "nl"
        "chi" -> "zh"
        "cze" -> "cs"
        "gre" -> "el"
        "per" -> "fa"
        "rum" -> "ro"
        else -> normalized
    }
    val name = Locale.forLanguageTag(iso).getDisplayLanguage(Locale.getDefault())
    return name.takeIf { it.isNotBlank() && !it.equals(iso, ignoreCase = true) }
        ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        ?: code
}

/** Audio of a file: its tracks ("Français · 5.1 · E-AC3"), else what its name says ("MULTI", "VF"). */
@Composable
internal fun audioLabels(version: VodVersion): List<String> {
    if (version.audio.isNotEmpty()) {
        // Tracks without any detail fall back to what the name says
        version.audio.map { track ->
            listOfNotNull(
                languageName(track.language),
                // 6 channels = 5.1, 8 = 7.1
                track.channels?.let { channels -> if (channels >= 6) "${channels - 1}.1" else "$channels.0" },
                track.codec
            ).joinToString(" · ")
        }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return version.languageTags.mapNotNull { tag ->
        when (tag) {
            "MULTI" -> stringResource(R.string.vod_lang_multi)
            "VF" -> stringResource(R.string.vod_lang_vf)
            "VFQ" -> stringResource(R.string.vod_lang_vfq)
            "VO", "VOSTFR" -> stringResource(R.string.vod_lang_vo)
            else -> null
        }
    }
}

/** Subtitles of a file: its tracks, else "French" for a VOSTFR file. */
@Composable
internal fun subtitleLabels(version: VodVersion): List<String> {
    if (version.subtitles.isNotEmpty()) return version.subtitles.mapNotNull { languageName(it) }
    return if ("VOSTFR" in version.languageTags) listOf(stringResource(R.string.vod_lang_vf)) else emptyList()
}
