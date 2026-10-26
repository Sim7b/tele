package io.github.sim7b.tele.ui.screens

import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.R
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.*
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.ui.components.CategoryActionDialog
import io.github.sim7b.tele.ui.components.ChannelActionMenuDialog
import io.github.sim7b.tele.ui.components.CompactChannelCard
import io.github.sim7b.tele.ui.components.DiscreteCategoryChip
import io.github.sim7b.tele.ui.components.FavoriteChannelCard
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.RoundIconButton
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.groupLabel
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.*
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Sentinel of the "all categories" chip; its label comes from the resources. */
private const val ALL_CATEGORIES = "__all__"

private const val FAVORITE_SLOTS = 8
private const val FAVORITE_KEY_PREFIX = "fav:"
private const val LIST_KEY_PREFIX = "list:"
private const val FAVORITE_COLUMNS = 4

/**
 * The 8 top tiles: the favorites in the chosen order (even if their category is hidden),
 * filled up with the first visible channels.
 */
fun pickFavoriteChannels(
    allChannels: List<Channel>,
    visibleChannels: List<Channel>,
    favoriteIds: List<String>
): List<Channel> {
    // Only the favorites are indexed: the lists may hold tens of thousands of channels
    val favoriteSet = favoriteIds.toHashSet()
    val byId = allChannels.filter { it.id in favoriteSet }.associateBy { it.id }
    val favorites = favoriteIds.mapNotNull { byId[it] }
    val filler = visibleChannels.asSequence().filterNot { it.id in favoriteSet }.take(FAVORITE_SLOTS)
    return (favorites + filler).take(FAVORITE_SLOTS)
}

/**
 * Home: favorites, categories and channel grid.
 * [channels] only contains the visible channels (hidden channels and categories filtered upstream),
 * [favorites] the 8 top tiles (see [pickFavoriteChannels]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    channels: List<Channel>,
    favorites: List<Channel>,
    activeConfig: SourceConfig?,
    favoriteIds: List<String> = emptyList(),
    isLoading: Boolean,
    loadError: UiText? = null,
    onChannelSelected: (Channel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onHideChannel: (String) -> Unit,
    loadStreams: suspend (Channel) -> Result<List<StreamSource>>,
    onSelectStream: (Channel, StreamSource?) -> Unit,
    onMoveFavorite: (channelId: String, toIndex: Int) -> Unit,
    onHideCategory: (String) -> Unit,
    automaticStreamDescription: String,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Saved states: restored when coming back from the player or the settings
    var selectedCategory by rememberSaveable { mutableStateOf(ALL_CATEGORIES) }
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val categoriesState = rememberLazyListState()

    var actionMenuChannel by remember { mutableStateOf<Channel?>(null) }
    var categoryMenu by remember { mutableStateOf<String?>(null) }
    val restoreFocusRequester = remember { FocusRequester() }
    val emptyStateFocusRequester = remember { FocusRequester() }
    var focusRestoreRequest by remember { mutableIntStateOf(0) }

    // Actual favorites at the top of the grid (the following tiles only fill up the 8 slots)
    val favoriteChannels = favorites
    val movableCount = remember(favorites, favoriteIds) { favorites.takeWhile { it.id in favoriteIds }.size }
    val hasFavorites = movableCount > 0

    // "Move" mode: the chosen tile follows the arrows, OK or Back confirms
    var movingChannelId by remember { mutableStateOf<String?>(null) }
    val movingFocus = remember { FocusRequester() }
    LaunchedEffect(movingChannelId, favorites) {
        if (movingChannelId != null) movingFocus.requestFocusWithRetry()
    }

    fun moveBy(step: Int) {
        val movingId = movingChannelId ?: return
        val from = favorites.indexOfFirst { it.id == movingId }
        val to = from + step
        if (from == -1 || to < 0 || to >= movableCount) return
        onMoveFavorite(movingId, favoriteIds.indexOf(favorites[to].id))
    }

    // Categories
    val categories = remember(channels) {
        listOf(ALL_CATEGORIES) + channels.map { it.group }.distinct()
    }

    // A category that disappeared (hidden channels, server change) must not leave an empty list
    val effectiveCategory = selectedCategory.takeIf { it in categories } ?: ALL_CATEGORIES

    // Filtered channels
    val filteredChannels = remember(channels, effectiveCategory) {
        if (effectiveCategory == ALL_CATEGORIES) channels else channels.filter { it.group == effectiveCategory }
    }
    // Rows of 4 computed once per list change (and not on every grid recomposition)
    val channelRows = remember(filteredChannels) { filteredChannels.chunked(4) }

    // Item that gets the focus when shown: the last focused one, otherwise the first favorite.
    // Id sets built once per list change: the last focused key changes on every D-pad move.
    val favoriteIdSet = remember(favoriteChannels) { favoriteChannels.mapTo(HashSet()) { it.id } }
    val listIdSet = remember(filteredChannels) { filteredChannels.mapTo(HashSet(filteredChannels.size * 2)) { it.id } }
    val focusTargetKey = lastFocusedKey?.takeIf { key ->
        (key.startsWith(FAVORITE_KEY_PREFIX) && key.removePrefix(FAVORITE_KEY_PREFIX) in favoriteIdSet) ||
            (key.startsWith(LIST_KEY_PREFIX) && key.removePrefix(LIST_KEY_PREFIX) in listIdSet)
    }
        ?: favoriteChannels.firstOrNull()?.let { FAVORITE_KEY_PREFIX + it.id }
        ?: filteredChannels.firstOrNull()?.let { LIST_KEY_PREFIX + it.id }
    val hasContent = channels.isNotEmpty() || favorites.isNotEmpty()

    LaunchedEffect(focusRestoreRequest, hasContent) {
        // While moving, the moved tile keeps the focus
        if (movingChannelId != null) return@LaunchedEffect
        if (hasContent) {
            restoreFocusRequester.requestFocusWithRetry()
        } else {
            emptyStateFocusRequester.requestFocusWithRetry()
        }
    }

    fun Modifier.trackFocus(key: String): Modifier = this
        .then(if (key == focusTargetKey) Modifier.focusRequester(restoreFocusRequester) else Modifier)
        .onFocusChanged { if (it.isFocused) lastFocusedKey = key }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // In move mode, the arrows move the tile instead of moving the focus
                if (movingChannelId == null) return@onPreviewKeyEvent false
                val keyCode = event.nativeKeyEvent.keyCode
                val isConfirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_BACK
                if (event.type == KeyEventType.KeyUp) {
                    if (isConfirm) {
                        movingChannelId = null
                        focusRestoreRequest++
                    }
                    return@onPreviewKeyEvent true
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(+1)
                    KeyEvent.KEYCODE_DPAD_UP -> moveBy(-FAVORITE_COLUMNS)
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(+FAVORITE_COLUMNS)
                }
                true
            }
            .padding(start = 48.dp, end = 48.dp, top = 22.dp, bottom = 8.dp) // TV Safe Area
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: time and date, sync status, settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderClock()

                Spacer(modifier = Modifier.weight(1f))

                if (isLoading && hasContent) {
                    CircularProgressIndicator(
                        color = TextSecondary,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.home_syncing), fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.width(18.dp))
                } else if (loadError != null && hasContent) {
                    Text(
                        text = loadError.resolve(),
                        fontSize = 12.sp,
                        color = LiveRed.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 420.dp)
                    )
                    Spacer(modifier = Modifier.width(18.dp))
                }

                RoundIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.home_settings),
                    onClick = onOpenSettings
                )
            }

            if (isLoading && !hasContent) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = TextSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = stringResource(R.string.home_loading),
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else if (!hasContent) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(460.dp)
                    ) {
                        Text(
                            text = if (activeConfig == null) stringResource(R.string.home_welcome) else stringResource(R.string.home_no_channels),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                activeConfig == null -> stringResource(R.string.home_add_source_hint)
                                loadError != null -> loadError.resolve()
                                else -> stringResource(R.string.home_no_channels_hint)
                            },
                            fontSize = 14.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        MenuRow(
                            label = stringResource(R.string.home_open_settings),
                            icon = Icons.Filled.Settings,
                            onClick = onOpenSettings,
                            modifier = Modifier.focusRequester(emptyStateFocusRequester)
                        )
                        if (activeConfig != null) {
                            MenuRow(
                                label = stringResource(R.string.home_retry),
                                icon = Icons.Filled.Refresh,
                                onClick = onRefresh
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // SECTION 1: FAVORITES (2 rows x 4 columns)
                    item {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 10.dp)
                            ) {
                                SectionLabel(text = if (hasFavorites) stringResource(R.string.home_favorites) else stringResource(R.string.home_featured))
                                if (movingChannelId != null) {
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = stringResource(R.string.home_move_hint),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AccentCyan
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                favoriteChannels.chunked(4).forEach { favRow ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        for (channel in favRow) {
                                            val isMoving = channel.id == movingChannelId
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    // The other tiles fade out during a move
                                                    .alpha(if (movingChannelId != null && !isMoving) 0.45f else 1f)
                                            ) {
                                                FavoriteChannelCard(
                                                    channel = channel,
                                                    onClick = { onChannelSelected(channel) },
                                                    onLongClick = { actionMenuChannel = channel },
                                                    isMoving = isMoving,
                                                    modifier = Modifier
                                                        .then(if (isMoving) Modifier.focusRequester(movingFocus) else Modifier)
                                                        .trackFocus(FAVORITE_KEY_PREFIX + channel.id)
                                                )
                                            }
                                        }
                                        repeat(4 - favRow.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 2: CATEGORIES
                    item {
                        LazyRow(
                            state = categoriesState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(categories) { category ->
                                DiscreteCategoryChip(
                                    text = if (category == ALL_CATEGORIES) stringResource(R.string.home_category_all) else groupLabel(category),
                                    isSelected = category == effectiveCategory,
                                    onClick = { selectedCategory = category },
                                    onLongClick = if (category == ALL_CATEGORIES) null else ({ categoryMenu = category })
                                )
                            }
                        }
                    }

                    // SECTION 3: CHANNEL GRID (4 columns)
                    items(
                        channelRows,
                        // Stable key: already composed rows are reused on guide updates
                        key = { row -> "row:${row.first().id}" },
                        contentType = { "channelRow" }
                    ) { rowChannels ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            for (channel in rowChannels) {
                                Box(modifier = Modifier.weight(1f)) {
                                    CompactChannelCard(
                                        channel = channel,
                                        onClick = { onChannelSelected(channel) },
                                        onLongClick = { actionMenuChannel = channel },
                                        modifier = Modifier.trackFocus(LIST_KEY_PREFIX + channel.id)
                                    )
                                }
                            }
                            repeat(4 - rowChannels.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // Context menu on long press
        actionMenuChannel?.let { ch ->
            val isFav = favoriteIds.contains(ch.id) || ch.isFavorite
            // Movable if it holds one of the favorite slots of the grid and there are at least two favorites
            val canMove = movableCount > 1 && favorites.take(movableCount).any { it.id == ch.id }
            ChannelActionMenuDialog(
                channel = ch,
                isFavorite = isFav,
                onToggleFavorite = { onToggleFavorite(ch.id) },
                onHideChannel = { onHideChannel(ch.id) },
                loadStreams = { loadStreams(ch) },
                onSelectStream = { stream -> onSelectStream(ch, stream) },
                onMove = if (canMove) ({ movingChannelId = ch.id }) else null,
                automaticDescription = automaticStreamDescription,
                onDismiss = {
                    actionMenuChannel = null
                    // Give the focus back to the grid (the original card may have disappeared if the channel was hidden)
                    focusRestoreRequest++
                }
            )
        }

        // Category menu (long press on its chip)
        categoryMenu?.let { category ->
            CategoryActionDialog(
                category = category,
                channelCount = channels.count { it.group == category },
                onHide = { onHideCategory(category) },
                onDismiss = {
                    categoryMenu = null
                    focusRestoreRequest++
                }
            )
        }
    }
}

/** Discreet time and date at the top of the home screen. */
@Composable
private fun HeaderClock() {
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(5_000)
        }
    }
    val time = remember(now.hour, now.minute) { now.format(DateTimeFormatter.ofPattern("HH:mm")) }
    val locale = LocalConfiguration.current.locales[0]
    val datePattern = stringResource(R.string.home_date_pattern)
    val date = remember(now.toLocalDate(), locale, datePattern) {
        now.format(DateTimeFormatter.ofPattern(datePattern, locale))
            .replaceFirstChar { it.titlecase(locale) }
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = time,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = date,
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}
