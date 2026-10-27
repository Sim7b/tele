package io.github.sim7b.tele.ui.screens

import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.R
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import io.github.sim7b.tele.data.model.Categories
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.HomeLists
import io.github.sim7b.tele.data.model.StreamSlots
import io.github.sim7b.tele.data.model.StreamSource
import androidx.activity.compose.BackHandler
import io.github.sim7b.tele.ui.components.CategoryActionDialog
import io.github.sim7b.tele.ui.components.ChannelNumberBadge
import io.github.sim7b.tele.ui.components.rememberChannelNumberEntry
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
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Sentinel of the "all categories" chip; its label comes from the resources. */
private const val ALL_CATEGORIES = "__all__"

private const val FAVORITE_KEY_PREFIX = "fav:"
private const val LIST_KEY_PREFIX = "list:"
private const val FAVORITE_COLUMNS = 4

/**
 * Home: favorites, categories and channel grid.
 * [channels] only contains the visible channels (hidden channels and categories filtered upstream),
 * [favorites] the 8 top tiles (see [HomeLists.pickFavorites]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    channels: List<Channel>,
    favorites: List<Channel>,
    // At least one source is enabled (else the home screen invites to add one)
    hasSource: Boolean,
    favoriteIds: List<String> = emptyList(),
    isLoading: Boolean,
    loadError: UiText? = null,
    streamSlots: StreamSlots? = null,
    updateAvailable: Boolean = false,
    onChannelSelected: (Channel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onHideChannel: (String) -> Unit,
    loadStreams: suspend (Channel) -> Result<List<StreamSource>>,
    onSelectStream: (Channel, StreamSource?) -> Unit,
    onMoveFavorite: (channelId: String, toIndex: Int) -> Unit,
    onHideCategory: (String) -> Unit,
    // Category order chosen by the user (displayed names) and its change after a move
    categoryOrder: List<String> = emptyList(),
    onReorderCategories: (List<String>) -> Unit = {},
    automaticStreamDescription: (Channel) -> String,
    isCategoryRenamed: (String) -> Boolean = { false },
    onRenameCategory: (category: String, name: String?) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit,
    onOpenSearch: (voice: Boolean) -> Unit,
    onRefresh: () -> Unit,
    // Muted preview of the focused card: focus time before it starts (null: no preview), start / stop (null), video surface
    previewDelayMs: Long? = null,
    onPreviewChannel: (Channel?) -> Unit = {},
    previewSurface: @Composable () -> Unit = {},
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
    val categories = remember(channels, categoryOrder) {
        listOf(ALL_CATEGORIES) + Categories.ordered(channels.map { it.group }.distinct(), categoryOrder)
    }

    // Category "move" mode (from its long-press menu): the chip follows ← →, OK or Back confirms
    var movingCategory by remember { mutableStateOf<String?>(null) }
    val categoryMoveFocus = remember { FocusRequester() }
    LaunchedEffect(movingCategory, categories) {
        val moving = movingCategory ?: return@LaunchedEffect
        val index = categories.indexOf(moving)
        if (index >= 0) categoriesState.animateScrollToItem((index - 1).coerceAtLeast(0))
        categoryMoveFocus.requestFocusWithRetry()
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
        // While moving, the moved tile (or category chip) keeps the focus
        if (movingChannelId != null || movingCategory != null) return@LaunchedEffect
        if (hasContent) {
            restoreFocusRequester.requestFocusWithRetry()
        } else {
            emptyStateFocusRequester.requestFocusWithRetry()
        }
    }

    // Down from the favorites always lands on the selected category: the 2D focus search would pick
    // the nearest item below, a grid channel under the right columns (the chips are short and on the left)
    val categoryFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun focusSelectedCategory() {
        scope.launch {
            val index = categories.indexOf(effectiveCategory).coerceAtLeast(0)
            if (categoriesState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                categoriesState.scrollToItem(index)
            }
            categoryFocus.requestFocusWithRetry()
        }
    }

    // Remote digits: open the channel of the typed number
    val numberEntry = rememberChannelNumberEntry(channels, onChannelSelected)
    BackHandler(enabled = numberEntry.isTyping) {
        numberEntry.cancel()
    }

    // Card focused right now (key and channel), and the card showing the muted preview
    var focusedCard by remember { mutableStateOf<Pair<String, Channel>?>(null) }
    var previewKey by remember { mutableStateOf<String?>(null) }
    val previewAllowed = previewDelayMs != null && movingChannelId == null
    LaunchedEffect(focusedCard?.first, previewAllowed, previewDelayMs) {
        if (previewKey != null) {
            previewKey = null
            onPreviewChannel(null)
        }
        val (key, channel) = focusedCard ?: return@LaunchedEffect
        if (!previewAllowed) return@LaunchedEffect
        delay(previewDelayMs ?: return@LaunchedEffect)
        previewKey = key
        onPreviewChannel(channel)
    }

    fun Modifier.trackFocus(key: String, channel: Channel): Modifier = this
        .then(if (key == focusTargetKey) Modifier.focusRequester(restoreFocusRequester) else Modifier)
        .onFocusChanged {
            if (it.isFocused) {
                lastFocusedKey = key
                focusedCard = key to channel
            } else if (focusedCard?.first == key) {
                focusedCard = null
            }
        }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (movingChannelId == null && hasContent && numberEntry.onKey(event)) return@onPreviewKeyEvent true
                // Search key of the remote (or keyboard): voice search directly
                if (movingChannelId == null && hasContent && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_SEARCH) {
                    if (event.type == KeyEventType.KeyUp) onOpenSearch(true)
                    return@onPreviewKeyEvent true
                }
                // Category move mode: ← → move the chip, OK or Back confirm
                movingCategory?.let { moving ->
                    val code = event.nativeKeyEvent.keyCode
                    if (event.type == KeyEventType.KeyUp) {
                        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK) {
                            movingCategory = null
                        }
                    } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        val step = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1
                        onReorderCategories(Categories.moved(categories.drop(1), moving, step))
                    }
                    return@onPreviewKeyEvent true
                }
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

                streamSlots?.let { slots ->
                    StreamSlotsBadge(slots)
                    Spacer(modifier = Modifier.width(14.dp))
                }

                if (hasContent) {
                    RoundIconButton(
                        icon = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.home_search),
                        onClick = { onOpenSearch(false) }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }

                // Discreet accent dot on the settings button when an app update is available
                Box {
                    RoundIconButton(
                        icon = Icons.Filled.Settings,
                        contentDescription = stringResource(
                            if (updateAvailable) R.string.home_settings_update else R.string.home_settings
                        ),
                        onClick = onOpenSettings
                    )
                    if (updateAvailable) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AccentCyan)
                        )
                    }
                }
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
                            text = if (!hasSource) stringResource(R.string.home_welcome) else stringResource(R.string.home_no_channels),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                !hasSource -> stringResource(R.string.home_add_source_hint)
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
                        if (hasSource) {
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
                                favoriteChannels.chunked(FAVORITE_COLUMNS).forEachIndexed { rowIndex, favRow ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        for ((column, channel) in favRow.withIndex()) {
                                            val isMoving = channel.id == movingChannelId
                                            // No favorite below this one: Down goes to the categories
                                            val isBottom = (rowIndex + 1) * FAVORITE_COLUMNS + column >= favoriteChannels.size
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
                                                    preview = if (previewKey == FAVORITE_KEY_PREFIX + channel.id) previewSurface else null,
                                                    modifier = Modifier
                                                        .then(if (isMoving) Modifier.focusRequester(movingFocus) else Modifier)
                                                        .trackFocus(FAVORITE_KEY_PREFIX + channel.id, channel)
                                                        .onPreviewKeyEvent { event ->
                                                            val down = event.type == KeyEventType.KeyDown &&
                                                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                                            if (down && isBottom) focusSelectedCategory()
                                                            down && isBottom
                                                        }
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
                        if (movingCategory != null) {
                            Text(
                                text = stringResource(R.string.home_move_category_hint),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentCyan,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                            )
                        }
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
                                    onLongClick = if (category == ALL_CATEGORIES) null else ({ categoryMenu = category }),
                                    modifier = when (category) {
                                        movingCategory -> Modifier.focusRequester(categoryMoveFocus)
                                        effectiveCategory -> Modifier.focusRequester(categoryFocus)
                                        else -> Modifier
                                    }.alpha(if (movingCategory != null && category != movingCategory) 0.45f else 1f)
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
                                        preview = if (previewKey == LIST_KEY_PREFIX + channel.id) previewSurface else null,
                                        modifier = Modifier.trackFocus(LIST_KEY_PREFIX + channel.id, channel)
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

        ChannelNumberBadge(
            entry = numberEntry,
            channels = channels,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp)
        )

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
                automaticDescription = automaticStreamDescription(ch),
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
                isRenamed = isCategoryRenamed(category),
                onRename = { name -> onRenameCategory(category, name) },
                onMove = { movingCategory = category },
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

/**
 * Discreet Dispatcharr slots indicator: green dot when a playback slot is free, red when all are
 * taken, followed by the number of free slots.
 */
@Composable
private fun StreamSlotsBadge(slots: StreamSlots) {
    val description = stringResource(R.string.home_stream_slots, slots.free, slots.total)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(SurfaceDark)
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (slots.free > 0) SlotFreeGreen else LiveRed)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = slots.free.toString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
    }
}
