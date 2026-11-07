package io.github.sim7b.tele.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
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
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.ProgressLine
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.focusRing
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.components.touchClick
import io.github.sim7b.tele.ui.theme.SurfaceCard
import io.github.sim7b.tele.ui.theme.TextPrimary

/**
 * Movies and series started on this TV, on the live TV home: a wide card each, its sheet opened on OK, the
 * same actions as in the movies and series screen on a long press ([actions]). A card taken out of the row
 * gives the focus to the next one (the last one gone, the row leaves the home, which takes the focus back).
 */
@Composable
fun ResumeRow(
    items: List<Pair<VodTitle, VodProgress>>,
    onOpen: (VodTitle) -> Unit,
    modifier: Modifier = Modifier,
    actions: VodTitleActions? = null,
    myList: List<String> = emptyList(),
    collections: List<VodCollection> = emptyList()
) {
    var menuFor by remember { mutableStateOf<VodTitle?>(null) }
    var pickerFor by remember { mutableStateOf<VodTitle?>(null) }
    // Card taken out by an action: the focus goes to the one shown next once the row has changed
    var removedKey by remember { mutableStateOf<String?>(null) }
    val nextFocus = remember { FocusRequester() }
    val nextKey = removedKey?.let { key -> nextResumeKey(items.map { it.first.key }, key) }
    LaunchedEffect(items, removedKey) {
        val key = removedKey ?: return@LaunchedEffect
        if (items.any { it.first.key == key }) return@LaunchedEffect
        removedKey = null
        if (items.isNotEmpty()) nextFocus.requestFocusWithRetry()
    }
    Column(modifier = modifier) {
        SectionLabel(text = stringResource(R.string.home_resume))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 10.dp, end = 24.dp)
        ) {
            items(items, key = { it.first.key }) { (title, progress) ->
                ResumeCard(
                    title = title,
                    progress = progress.fraction,
                    onClick = { onOpen(title) },
                    onLongClick = if (actions != null) ({ menuFor = title }) else null,
                    modifier = if (title.key == nextKey) Modifier.focusRequester(nextFocus) else Modifier
                )
            }
        }
    }
    menuFor?.let { title ->
        TitleActionsDialog(
            title = title,
            inContinue = true,
            // Started, so not watched yet; a series is marked episode by episode in its sheet
            watched = if (title.kind == VodKind.MOVIE) false else null,
            removeFrom = null,
            onOpen = { onOpen(title) },
            onAddTo = { pickerFor = title },
            onRemoveContinue = {
                removedKey = title.key
                actions?.removeFromContinue?.invoke(title.key)
            },
            onToggleWatched = {
                removedKey = title.key
                actions?.setWatched?.invoke(title, true)
            },
            onRemoveFrom = {},
            onDismiss = { menuFor = null }
        )
    }
    pickerFor?.let { title ->
        CollectionPickerDialog(
            collections = collections,
            titleKey = title.key,
            inMyList = title.key in myList,
            onToggleMyList = { actions?.toggleMyList?.invoke(title.key) },
            onToggle = { id -> actions?.toggleInCollection?.invoke(id, title.key) },
            onCreate = { name -> actions?.createCollectionWith?.invoke(name, title.key) },
            onDismiss = { pickerFor = null }
        )
    }
}

/** Card that takes the focus once [removed] is out of the row: the one after it, else the one before. */
internal fun nextResumeKey(keys: List<String>, removed: String): String? {
    val index = keys.indexOf(removed)
    val rest = keys.filter { it != removed }
    return if (index < 0) rest.firstOrNull() else rest.getOrNull(index) ?: rest.lastOrNull()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResumeCard(title: VodTitle, progress: Float, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        modifier = modifier
            .width(ResumeCardWidth)
            .height(ResumeCardWidth * 9 / 16)
            .touchClick(onClick, onLongClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = focusRing(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceCard, focusedContainerColor = SurfaceCard)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FadeInImage(url = title.backdropUrl ?: title.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
            )
            Text(
                text = title.title,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            )
            ProgressLine(
                progress = progress,
                height = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }
    }
}

private val ResumeCardWidth = 220.dp
