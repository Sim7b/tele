package io.github.sim7b.tele.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.github.sim7b.tele.ui.components.touchClick
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/*
 * Sub-screens of the Movies and Series tabs: the rows of the streaming services and of the genres, and the
 * heading of the one opened.
 */

internal const val PLATFORMS_ROW = "platforms"
internal const val GENRES_ROW = "genres"

/** An entry of a row of sub-screens: a streaming service or a genre, its name and possibly its logo. */
internal data class SubviewEntry<T>(val value: T, val key: String, val label: String, val logo: String?)

/** A row of sub-screens (streaming services, genres) drawn as their logo or name; OK opens one. */
@Composable
internal fun <T> SubviewRow(
    label: String,
    entries: List<SubviewEntry<T>>,
    onOpen: (T) -> Unit,
    onEntryFocused: (T) -> Unit = {},
    focusOf: (T) -> FocusRequester,
    rowFocus: FocusRequester,
    onRowFocused: () -> Unit,
    onLeftAtStart: (() -> Unit)?,
    firstFocus: FocusRequester?
) {
    var focusedIndex by remember { mutableIntStateOf(-1) }
    Column {
        SectionLabel(text = label)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = RowBleed, top = 10.dp, bottom = 4.dp, end = 24.dp),
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val open = onLeftAtStart ?: return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && focusedIndex == 0) {
                        open()
                        true
                    } else {
                        false
                    }
                }
                .layout { measurable, constraints ->
                    val bleed = RowBleed.roundToPx()
                    val placeable = measurable.measure(
                        if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + bleed) else constraints
                    )
                    layout((placeable.width - bleed).coerceAtLeast(0), placeable.height) { placeable.place(-bleed, 0) }
                }
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                SubviewTile(
                    label = entry.label,
                    logo = entry.logo,
                    onClick = { onOpen(entry.value) },
                    onFocused = {
                        focusedIndex = index
                        onRowFocused()
                        onEntryFocused(entry.value)
                    },
                    modifier = Modifier
                        .focusRequester(focusOf(entry.value))
                        .then(if (index == 0) Modifier.focusRequester(rowFocus) else Modifier)
                        .then(if (firstFocus != null && index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                )
            }
        }
    }
}

/**
 * A sub-screen: its logo in one color (white, dimmed until focused), without a card behind, or its name in that
 * color. Focused, it grows and is underlined.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SubviewTile(label: String, logo: String?, onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .widthIn(min = 120.dp)
            .height(64.dp)
            .touchClick(onClick, null),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent
        )
    ) {
        Box(modifier = Modifier.height(64.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            SubviewLogo(
                label = label,
                logo = logo,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.55f),
                height = 30.dp,
                modifier = Modifier.widthIn(max = 150.dp)
            )
            if (focused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .width(36.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White)
                )
            }
        }
    }
}

/** A logo drawn in [color] (its shape only), else [label] in that color. */
@Composable
private fun SubviewLogo(label: String, logo: String?, color: Color, height: Dp, modifier: Modifier = Modifier) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) {
        AsyncImage(
            model = logo,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(color, BlendMode.SrcIn),
            onError = { failed = true },
            modifier = modifier.height(height)
        )
    } else {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    }
}

/** Heading of an opened sub-screen: its logo or name, its titles in the library, the loading of its lists. */
@Composable
internal fun SubviewHeader(label: String, logo: String?, count: Int, loading: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 10.dp)
    ) {
        SubviewLogo(label = label, logo = logo, color = TextPrimary, height = 28.dp, modifier = Modifier.widthIn(max = 180.dp))
        if (count > 0) {
            Text(text = pluralStringResource(R.plurals.vod_titles_count, count, count), fontSize = 13.sp, color = TextSecondary)
        }
        if (loading) {
            CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Text(
                text = stringResource(R.string.vod_platform_loading, label),
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
