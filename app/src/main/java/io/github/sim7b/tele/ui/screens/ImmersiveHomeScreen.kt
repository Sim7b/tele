package io.github.sim7b.tele.ui.screens

import androidx.compose.foundation.layout.wrapContentWidth
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.home.HomeFeed
import io.github.sim7b.tele.data.home.HomeItem
import io.github.sim7b.tele.data.model.Categories
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.ChannelList
import io.github.sim7b.tele.data.model.ChannelListView
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.model.HomeRow
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.remote.ProgramPosterRepository
import io.github.sim7b.tele.data.sport.SportEvent
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.ui.components.ChannelActionMenuDialog
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.GlassClock
import io.github.sim7b.tele.ui.components.GlassNavBar
import io.github.sim7b.tele.ui.components.ListsMenu
import io.github.sim7b.tele.ui.components.NavSection
import io.github.sim7b.tele.ui.components.ScreenNav
import io.github.sim7b.tele.ui.components.glass
import io.github.sim7b.tele.ui.components.GuideActions
import io.github.sim7b.tele.ui.components.LogoPlate
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.ProgressLine
import io.github.sim7b.tele.ui.components.RecordDot
import io.github.sim7b.tele.ui.components.RecordRed
import io.github.sim7b.tele.ui.components.groupLabel
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.components.touchClick
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.SurfaceCard
import io.github.sim7b.tele.ui.vod.CollectionPickerDialog
import io.github.sim7b.tele.ui.vod.TitleActionsDialog
import io.github.sim7b.tele.ui.vod.VodTitleActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * What the home shows over the whole screen for an item (featured, or focused in a row): its artwork (then its
 * live picture after the preview delay when it is on air), its title (as a TMDB logo when there is one) and its
 * details.
 */
private data class Spotlight(
    val key: String,
    val title: String,
    val overline: String?,
    val logoUrl: String?,
    val live: Boolean,
    val details: String?,
    val description: String?,
    val progress: Float?,
    val imageUrl: String?,
    // Looked up by title (TMDB, TVMaze) when there is no image, and for the title logo
    val lookupTitle: String?,
    val tmdbId: String?,
    val movie: Boolean?,
    // On air: its live picture after the delay
    val channel: Channel?
)

/** A row of the home: its key, its name and what it shows. */
private data class Shelf(val key: String, val name: String, val items: List<HomeItem>) {
    /** Its name as shown (a category of the source by its displayed name). */
    val isGroup: Boolean get() = key.startsWith("group:")
}

@Composable
private fun Shelf.label(): String = if (isGroup) groupLabel(name) else name

/**
 * Live TV home in the style of the Apple TV app: a featured item over the whole screen (the program on air of a
 * favorite, a title to resume, a followed match, one after the other), with glass tabs and buttons, then rows of
 * landscape cards (up next, favorites, Trakt watchlist, categories): the focused one lifts, the screen shows it.
 * Left with nothing more on the left opens a side menu (screens, then every row). The classic home stays the
 * default (see [HomeScreen]).
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImmersiveHomeScreen(
    channels: List<Channel>,
    favorites: List<Channel>,
    rows: Set<HomeRow>,
    favoriteIds: List<String>,
    onChannelSelected: (Channel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onHideChannel: (String) -> Unit,
    loadStreams: suspend (Channel) -> Result<List<StreamSource>>,
    onSelectStream: (Channel, StreamSource?) -> Unit,
    automaticStreamDescription: (Channel) -> String,
    onOpenSettings: () -> Unit,
    onOpenSearch: (voice: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    categoryOrder: List<String> = emptyList(),
    updateAvailable: Boolean = false,
    onOpenVod: ((VodKind) -> Unit)? = null,
    onOpenGuide: (() -> Unit)? = null,
    onOpenRecordings: (() -> Unit)? = null,
    previewDelayMs: Long? = null,
    onPreviewChannel: (Channel?) -> Unit = {},
    previewSurface: @Composable () -> Unit = {},
    channelLists: List<ChannelListView> = emptyList(),
    savedLists: List<ChannelList> = emptyList(),
    onToggleInList: (listId: String, Channel) -> Unit = { _, _ -> },
    onCreateList: (name: String, Channel) -> Unit = { _, _ -> },
    resume: List<Pair<VodTitle, VodProgress>> = emptyList(),
    onOpenResume: (VodTitle) -> Unit = {},
    resumeActions: VodTitleActions? = null,
    vodMyList: List<String> = emptyList(),
    vodCollections: List<VodCollection> = emptyList(),
    // Followed sport found in the guide, and what OK does on one (the channel, a reminder, the replay)
    sport: List<SportEvent> = emptyList(),
    onOpenSport: (SportEvent) -> Unit = {},
    // Trakt watchlist found in the library
    watchlist: List<VodTitle> = emptyList(),
    onOpenTitle: (VodTitle) -> Unit = {},
    // Reminders and recordings of the programs (buttons of the featured item)
    guideActions: GuideActions = GuideActions()
) {
    val now = System.currentTimeMillis()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // The featured items and the rows
    val upNextName = stringResource(R.string.home_row_up_next)
    val favoritesName = stringResource(R.string.home_favorites)
    val traktName = stringResource(R.string.home_row_trakt)
    val upNext = remember(resume, sport, rows) {
        HomeFeed.upNext(if (HomeRow.RESUME in rows) resume else emptyList(), if (HomeRow.SPORT in rows) sport else emptyList(), now)
    }
    val featured = remember(favorites, upNext, channels) { HomeFeed.featured(favorites, upNext, channels) }
    val shelves = remember(upNext, favorites, watchlist, channels, categoryOrder, channelLists, rows) {
        buildList {
            if (upNext.isNotEmpty()) add(Shelf("upnext", upNextName, upNext))
            if (favorites.isNotEmpty()) add(Shelf("favorites", favoritesName, favorites.map { HomeItem.Live(it) }))
            if (HomeRow.TRAKT in rows && watchlist.isNotEmpty()) add(Shelf("trakt", traktName, watchlist.map { HomeItem.Title(it) }))
            if (HomeRow.CATEGORIES in rows) {
                channelLists.filter { it.channels.isNotEmpty() }.forEach { list ->
                    add(Shelf("list:${list.id}", list.name, list.channels.map { HomeItem.Live(it) }))
                }
                Categories.ordered(channels.map { it.group }.distinct(), categoryOrder).forEach { group ->
                    add(Shelf("group:$group", group, channels.filter { it.group == group }.map { HomeItem.Live(it) }))
                }
            }
        }
    }

    // Where the focus is: -1 the featured item (and the tabs), else the index of the row
    var focusedRow by remember { mutableIntStateOf(-1) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pageIndex = if (featured.isEmpty()) 0 else page.coerceIn(0, featured.lastIndex)
    var focusedItem by remember { mutableStateOf<HomeItem?>(null) }
    var heroButton by remember { mutableIntStateOf(-1) }
    // Number of buttons of the featured item shown (read by the keys, not drawn)
    val heroButtonCount = remember { intArrayOf(0) }
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val heroFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var menuChannel by remember { mutableStateOf<Channel?>(null) }
    var menuTitle by remember { mutableStateOf<HomeItem.Title?>(null) }
    var pickerFor by remember { mutableStateOf<VodTitle?>(null) }
    // Side menu (Left with nothing more on the left): the other screens, then every row to jump to it
    var railOpen by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun rowRequester(key: String) = rowFocus.getOrPut(key) { FocusRequester() }

    val shownItem = if (focusedRow < 0) featured.getOrNull(pageIndex) else focusedItem ?: featured.getOrNull(pageIndex)
    val spotlight = shownItem?.let { spotlightOf(it, now) }

    // Live picture of the item shown once it stayed for the delay
    var previewKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(spotlight?.key, previewDelayMs) {
        if (previewKey != null) {
            previewKey = null
            onPreviewChannel(null)
        }
        val shown = spotlight ?: return@LaunchedEffect
        val channel = shown.channel ?: return@LaunchedEffect
        delay(previewDelayMs ?: return@LaunchedEffect)
        previewKey = shown.key
        onPreviewChannel(channel)
    }

    // The featured items go by themselves while one is chosen, as long as no live picture plays
    LaunchedEffect(pageIndex, heroButton >= 0, previewKey, featured.size) {
        if (featured.size < 2 || heroButton < 0 || previewKey != null) return@LaunchedEffect
        delay(FEATURED_ROTATION_MS)
        page = (pageIndex + 1) % featured.size
        heroFocus.requestFocusWithRetry()
    }

    // The focused row comes up under the details
    LaunchedEffect(focusedRow) {
        if (focusedRow < 0) return@LaunchedEffect
        // One smooth move to the row (the list itself does not follow the focus, see NoFollow)
        val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusedRow }
        if (row != null) listState.animateScrollBy(row.offset.toFloat(), tween(MOVE_MS, easing = FastOutSlowInEasing))
        else listState.scrollToItem(focusedRow)
    }

    fun toHero() {
        focusedRow = -1
        lastFocusedKey = null
        scope.launch {
            listState.scrollToItem(0)
            heroFocus.requestFocusWithRetry()
        }
    }

    // The featured item first, or the card left last (back from a channel)
    LaunchedEffect(featured.isNotEmpty()) {
        if (featured.isEmpty()) return@LaunchedEffect
        if (lastFocusedKey == null) heroFocus.requestFocusWithRetry() else cardFocus.requestFocusWithRetry()
    }
    LaunchedEffect(railOpen) { if (railOpen) railFocus.requestFocusWithRetry() }
    BackHandler(enabled = railOpen) {
        railOpen = false
        scope.launch { if (focusedRow < 0) heroFocus.requestFocusWithRetry() else cardFocus.requestFocusWithRetry() }
    }
    // In the rows, Back goes back up to the featured item
    BackHandler(enabled = !railOpen && focusedRow >= 0) { toHero() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(LocalAppColors.current.background)
            .onPreviewKeyEvent { event ->
                if (railOpen || menuChannel != null || menuTitle != null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        when {
                            // On the featured item: the previous one, before it the side menu
                            heroButton == 0 && pageIndex > 0 -> {
                                page = pageIndex - 1
                                scope.launch { heroFocus.requestFocusWithRetry() }
                            }
                            heroButton == 0 -> railOpen = shelves.isNotEmpty()
                            !focusManager.moveFocus(FocusDirection.Left) -> railOpen = shelves.isNotEmpty()
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        // After its last button: the next featured item
                        if (heroButton >= 0 && heroButton == heroButtonCount[0] - 1 && featured.size > 1) {
                            page = (pageIndex + 1) % featured.size
                            scope.launch { heroFocus.requestFocusWithRetry() }
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionUp -> {
                        if (focusedRow == 0) {
                            toHero()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        val height = maxHeight
        val inRows = focusedRow >= 0

        // Artwork of the item shown, then its live picture
        Crossfade(targetState = spotlight?.key, label = "backdrop") { key ->
            val shown = spotlight?.takeIf { it.key == key }
            if (shown != null) Backdrop(shown)
        }
        AnimatedVisibility(visible = previewKey != null && previewKey == spotlight?.key, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) { previewSurface() }
        }
        // Scrims: the texts on the left and the rows at the bottom stay readable over any picture
        Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.75f), 0.4f to Color.Black.copy(alpha = 0.3f), 0.65f to Color.Transparent)))
        val bottomScrim by animateFloatAsState(if (inRows) 0.62f else 0.5f, label = "scrim")
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    (1f - bottomScrim) to Color.Transparent,
                    (1f - bottomScrim * 0.55f) to Color.Black.copy(alpha = 0.6f),
                    1f to Color.Black.copy(alpha = 0.92f)
                )
            )
        )

        // Glass tabs and the time: with the featured item only
        val headerAlpha by animateFloatAsState(if (inRows) 0f else 1f, label = "tabs")
        GlassNavBar(
            nav = ScreenNav(
                onHome = {},
                onGuide = onOpenGuide,
                onVod = onOpenVod,
                onRecordings = onOpenRecordings,
                onSearch = { onOpenSearch(false) },
                onSettings = onOpenSettings,
                updateAvailable = updateAvailable
            ),
            current = NavSection.HOME,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
                .graphicsLayer { alpha = headerAlpha }
        )
        GlassClock(modifier = Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = screenMargin(36.dp)).graphicsLayer { alpha = headerAlpha })

        // The featured item with its buttons, or the details of the focused card
        spotlight?.let { shown ->
            if (!inRows) {
                val item = featured.getOrNull(pageIndex)
                // Low on the screen, just above the first row: the picture stays clear
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = screenMargin(48.dp), bottom = height * 0.20f + SpotlightGap)
                        .widthIn(max = 460.dp)
                ) {
                    SpotlightText(shown, compact = false, live = previewKey == shown.key)
                    Spacer(modifier = Modifier.height(18.dp))
                    val buttons = item?.let { heroButtons(it, now, guideActions, vodMyList, resumeActions, onChannelSelected, onOpenResume, onOpenTitle, onOpenSport) }.orEmpty()
                    heroButtonCount[0] = buttons.size
                    // Wider than the text above when needed: a button is never cut
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)
                    ) {
                        buttons.forEachIndexed { index, button ->
                            GlassButton(
                                button = button,
                                modifier = (if (index == 0) Modifier.focusRequester(heroFocus) else Modifier)
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            heroButton = index
                                            focusedRow = -1
                                        } else if (heroButton == index) {
                                            heroButton = -1
                                        }
                                    }
                            )
                        }
                    }
                    if (featured.size > 1) {
                        Spacer(modifier = Modifier.height(16.dp))
                        PageDots(count = featured.size, current = pageIndex)
                    }
                }
            } else {
                Column(modifier = Modifier.padding(start = screenMargin(48.dp), top = 28.dp).widthIn(max = 480.dp)) {
                    SpotlightText(shown, compact = true, live = previewKey == shown.key)
                }
            }
        }

        // The rows: the first one peeks under the featured item, the focused one comes up under the details
        val rowsTop by animateDpAsState(if (inRows) height * 0.40f else height * 0.80f, tween(MOVE_MS, easing = FastOutSlowInEasing), label = "rows")
        // The rows move only by the scroll above (not by the focus as well: two moves made it bounce); the cards
        // of a row follow the focus smoothly
        val rowSpec = remember { SmoothFollow }
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoFollow) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .offset(y = rowsTop)
                .fillMaxWidth()
                .height(height * 0.60f)
                .fadingTop(if (inRows) RowsFade else 0.dp),
            // Room below the last row: any row can come up under the details
            contentPadding = PaddingValues(top = if (inRows) RowsFade else 0.dp, bottom = height * 0.4f),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            itemsIndexed(shelves, key = { _, shelf -> shelf.key }) { index, shelf ->
                Column(
                    modifier = Modifier
                        .focusRequester(rowRequester(shelf.key))
                        .onFocusChanged {
                            if (it.hasFocus) focusedRow = index
                        }
                ) {
                    Text(
                        text = shelf.label(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(start = screenMargin(48.dp), bottom = 10.dp)
                    )
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(start = screenMargin(48.dp), end = 48.dp, top = 6.dp, bottom = 4.dp)
                    ) {
                        items(shelf.items, key = { it.key }) { item ->
                            val cardKey = "${shelf.key}|${item.key}"
                            HomeCard(
                                item = item,
                                now = now,
                                onClick = {
                                    when (item) {
                                        is HomeItem.Live -> onChannelSelected(item.channel)
                                        is HomeItem.Title -> if (item.progress != null) onOpenResume(item.title) else onOpenTitle(item.title)
                                        is HomeItem.Event -> onOpenSport(item.event)
                                    }
                                },
                                onLongClick = {
                                    when (item) {
                                        is HomeItem.Live -> menuChannel = item.channel
                                        is HomeItem.Title -> if (resumeActions != null) menuTitle = item
                                        is HomeItem.Event -> menuChannel = item.event.channel
                                    }
                                },
                                modifier = (if (cardKey == lastFocusedKey) Modifier.focusRequester(cardFocus) else Modifier)
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            lastFocusedKey = cardKey
                                            focusedItem = item
                                        }
                                    }
                            )
                        }
                    }
                    }
                }
            }
        }
        }

        AnimatedVisibility(
            visible = railOpen,
            enter = fadeIn() + slideInHorizontally { -it / 3 },
            exit = fadeOut() + slideOutHorizontally { -it / 3 },
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            // The rows of the home only: the other screens are in the glass bar
            ListsMenu(
                title = stringResource(R.string.side_menu_rows),
                entries = shelves.map { it.key to it.label() },
                current = focusedRow,
                focus = railFocus,
                onPick = { index, key ->
                    railOpen = false
                    scope.launch {
                        focusedRow = index
                        listState.scrollToItem(index)
                        rowRequester(key).requestFocusWithRetry()
                    }
                },
                onClose = {
                    railOpen = false
                    scope.launch { if (focusedRow < 0) heroFocus.requestFocusWithRetry() else cardFocus.requestFocusWithRetry() }
                }
            )
        }
    }

    menuChannel?.let { channel ->
        ChannelActionMenuDialog(
            channel = channel,
            isFavorite = favoriteIds.contains(channel.id) || channel.isFavorite,
            onToggleFavorite = { onToggleFavorite(channel.id) },
            onHideChannel = { onHideChannel(channel.id) },
            loadStreams = { loadStreams(channel) },
            onSelectStream = { stream -> onSelectStream(channel, stream) },
            onMove = null,
            automaticDescription = automaticStreamDescription(channel),
            lists = savedLists,
            onToggleInList = { listId -> onToggleInList(listId, channel) },
            onCreateList = { name -> onCreateList(name, channel) },
            onDismiss = {
                menuChannel = null
                scope.launch { cardFocus.requestFocusWithRetry() }
            }
        )
    }
    menuTitle?.let { item ->
        val title = item.title
        TitleActionsDialog(
            title = title,
            inContinue = item.progress != null,
            watched = if (title.kind == VodKind.MOVIE) false else null,
            removeFrom = null,
            onOpen = { onOpenTitle(title) },
            onAddTo = { pickerFor = title },
            onRemoveContinue = { resumeActions?.removeFromContinue?.invoke(title.key) },
            onToggleWatched = { resumeActions?.setWatched?.invoke(title, true) },
            onRemoveFrom = {},
            onDismiss = {
                menuTitle = null
                scope.launch { cardFocus.requestFocusWithRetry() }
            }
        )
    }
    pickerFor?.let { title ->
        CollectionPickerDialog(
            collections = vodCollections,
            titleKey = title.key,
            inMyList = title.key in vodMyList,
            onToggleMyList = { resumeActions?.toggleMyList?.invoke(title.key) },
            onToggle = { id -> resumeActions?.toggleInCollection?.invoke(id, title.key) },
            onCreate = { name -> resumeActions?.createCollectionWith?.invoke(name, title.key) },
            onDismiss = { pickerFor = null }
        )
    }
}

/** What the screen shows for [item]. */
@Composable
private fun spotlightOf(item: HomeItem, now: Long): Spotlight = when (item) {
    is HomeItem.Live -> {
        val channel = item.channel
        val program = channel.currentProgram
        Spotlight(
            key = item.key,
            title = program?.title ?: channel.name,
            overline = channel.name,
            logoUrl = channel.logoUrl,
            live = true,
            details = program?.let { listOfNotNull("${it.startTime} – ${it.endTime}", it.category).joinToString(" · ") },
            description = program?.description,
            progress = program?.progress,
            imageUrl = program?.posterUrl,
            lookupTitle = program?.title,
            tmdbId = null,
            movie = null,
            channel = channel
        )
    }
    is HomeItem.Title -> {
        val title = item.title
        val left = item.progress?.let { (it.durationMs - it.positionMs).coerceAtLeast(0) }
        Spotlight(
            key = item.key,
            title = title.title,
            overline = null,
            logoUrl = null,
            live = false,
            details = listOfNotNull(
                stringResource(if (title.kind == VodKind.MOVIE) R.string.home_kind_movie else R.string.home_kind_series),
                title.year?.toString(),
                title.genres.take(2).joinToString(", ").takeIf { it.isNotEmpty() },
                left?.takeIf { it > 0 }?.let { stringResource(R.string.home_time_left, durationLabel(it)) }
            ).joinToString(" · "),
            description = title.plot,
            progress = item.progress?.fraction,
            imageUrl = title.backdropUrl ?: title.posterUrl,
            lookupTitle = title.title,
            tmdbId = title.tmdbId,
            movie = title.kind == VodKind.MOVIE,
            channel = null
        )
    }
    is HomeItem.Event -> {
        val event = item.event
        val program = event.program
        val onAir = program.startMillis <= now && program.endMillis > now
        Spotlight(
            key = item.key,
            title = program.title,
            overline = event.channel.name,
            logoUrl = event.channel.logoUrl,
            live = onAir,
            details = listOfNotNull(
                "${EpgResolver.formatClock(program.startMillis)} – ${EpgResolver.formatClock(program.endMillis)}",
                program.category
            ).joinToString(" · "),
            description = program.description,
            progress = if (onAir) ((now - program.startMillis).toFloat() / (program.endMillis - program.startMillis)).coerceIn(0f, 1f) else null,
            imageUrl = program.posterUrl,
            lookupTitle = program.title,
            tmdbId = null,
            movie = null,
            channel = event.channel.takeIf { onAir }
        )
    }
}

/** "1 h 12" or "45 min". */
private fun durationLabel(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(1)
    return if (minutes >= 60) "%d h %02d".format(minutes / 60, minutes % 60) else "$minutes min"
}

/** A button of the featured item. */
private class HeroButton(val label: String, val icon: ImageVector?, val primary: Boolean, val record: Boolean = false, val onClick: () -> Unit)

/** The buttons of [item]: watch or open it first, then favorite / my list, reminder, recording. */
@Composable
private fun heroButtons(
    item: HomeItem,
    now: Long,
    guideActions: GuideActions,
    myList: List<String>,
    resumeActions: VodTitleActions?,
    onChannel: (Channel) -> Unit,
    onOpenResume: (VodTitle) -> Unit,
    onOpenTitle: (VodTitle) -> Unit,
    onOpenSport: (SportEvent) -> Unit
): List<HeroButton> {
    val watch = stringResource(R.string.reminder_watch)
    // Watching only: adding to the favorites and recording stay in the guide
    return when (item) {
        is HomeItem.Live -> listOf(HeroButton(watch, Icons.Filled.PlayArrow, primary = true) { onChannel(item.channel) })
        is HomeItem.Title -> {
            val title = item.title
            val inList = title.key in myList
            listOfNotNull(
                HeroButton(stringResource(R.string.vod_menu_open), Icons.Filled.PlayArrow, primary = true) {
                    if (item.progress != null) onOpenResume(title) else onOpenTitle(title)
                },
                resumeActions?.let { actions ->
                    HeroButton(stringResource(R.string.vod_row_my_list), if (inList) Icons.Filled.Check else Icons.Filled.Add, primary = false) {
                        actions.toggleMyList(title.key)
                    }
                }
            )
        }
        is HomeItem.Event -> {
            val event = item.event
            val program = event.program
            val primary = when {
                program.endMillis <= now -> HeroButton(stringResource(R.string.program_replay), Icons.Filled.PlayArrow, primary = true) { onOpenSport(event) }
                program.startMillis > now -> HeroButton(
                    if (guideActions.hasReminder(event.channel, program)) stringResource(R.string.program_cancel_reminder)
                    else stringResource(R.string.program_remind, EpgResolver.formatClock(program.startMillis)),
                    Icons.Filled.Notifications,
                    primary = true
                ) { onOpenSport(event) }
                else -> HeroButton(watch, Icons.Filled.PlayArrow, primary = true) { onOpenSport(event) }
            }
            listOf(primary)
        }
    }
}

/** The artwork of [shown] over the whole screen: the largest known, else its channel logo on a plain background. */
@Composable
private fun Backdrop(shown: Spotlight) {
    val image by produceState(initialValue = shown.imageUrl?.takeIf { shown.lookupTitle == null }, shown.key) {
        val title = shown.lookupTitle
        if (title == null) {
            value = shown.imageUrl
            return@produceState
        }
        value = ProgramPosterRepository.getBackdropUrl(title) ?: shown.imageUrl ?: ProgramPosterRepository.getPosterUrl(title)
    }
    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2433), Color(0xFF0B0F17))))) {
        if (!image.isNullOrBlank()) {
            FadeInImage(url = image, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else if (shown.overline != null) {
            // No picture: the logo of the channel, discreet, above the rows
            LogoPlate(
                logoUrl = shown.logoUrl,
                name = shown.overline,
                corner = 12.dp,
                padding = 12.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 70.dp, end = 110.dp)
                    .size(width = 150.dp, height = 100.dp)
                    .graphicsLayer { alpha = 0.85f }
            )
        }
    }
}

/** The title (its TMDB logo when there is one), its channel and details; over its live picture, less. */
@Composable
private fun SpotlightText(shown: Spotlight, compact: Boolean, live: Boolean) {
    val context = LocalContext.current
    val logo by produceState<String?>(initialValue = null, shown.key) {
        value = shown.lookupTitle?.let { ProgramPosterRepository.getTitleLogoUrl(it, shown.tmdbId, shown.movie) }
    }
    if (shown.overline != null && !compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoPlate(logoUrl = shown.logoUrl, name = shown.overline, corner = 6.dp, padding = 3.dp, modifier = Modifier.size(width = 44.dp, height = 28.dp))
            if (shown.live) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(LiveRed))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.player_live), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Color(0xFFFFB4B4))
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
    }
    val logoHeight = if (compact) 64.dp else 96.dp
    if (logo != null) {
        AsyncImage(
            model = remember(logo) { ImageRequest.Builder(context).data(logo).crossfade(200).build() },
            contentDescription = shown.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.height(logoHeight).widthIn(max = if (compact) 280.dp else 360.dp)
        )
    } else {
        Text(
            text = shown.title,
            fontSize = if (compact) 26.sp else 38.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = if (compact) 30.sp else 42.sp
        )
    }
    if (live && !compact) return
    shown.details?.takeIf { it.isNotBlank() }?.let {
        Spacer(modifier = Modifier.height(10.dp))
        val prefix = if (compact && shown.overline != null) "${shown.overline} · " else ""
        Text(text = prefix + it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    shown.progress?.let {
        Spacer(modifier = Modifier.height(10.dp))
        ProgressLine(progress = it, height = 3.dp, trackColor = Color.White.copy(alpha = 0.25f), color = Color.White, modifier = Modifier.width(200.dp))
    }
    shown.description?.takeIf { it.isNotBlank() }?.let {
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
    }
}

/** A landscape card: the program or title artwork (else the channel logo), its channel, its progress; it lifts when focused. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeCard(item: HomeItem, now: Long, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val channel = when (item) {
        is HomeItem.Live -> item.channel
        is HomeItem.Event -> item.event.channel
        is HomeItem.Title -> null
    }
    val program = (item as? HomeItem.Live)?.channel?.currentProgram
    val event = (item as? HomeItem.Event)?.event?.program
    val onAir = when (item) {
        is HomeItem.Live -> program != null
        is HomeItem.Event -> event!!.startMillis <= now && event.endMillis > now
        is HomeItem.Title -> false
    }
    val progress = when (item) {
        is HomeItem.Live -> program?.progress
        is HomeItem.Event -> if (onAir) ((now - event!!.startMillis).toFloat() / (event.endMillis - event.startMillis)).coerceIn(0f, 1f) else null
        is HomeItem.Title -> item.progress?.fraction
    }
    val lookup = program?.title ?: event?.title
    val guideImage = program?.posterUrl ?: event?.posterUrl
    val image by produceState(initialValue = (item as? HomeItem.Title)?.let { it.title.backdropUrl ?: it.title.posterUrl } ?: guideImage, item.key) {
        if (value.isNullOrBlank() && lookup != null) {
            value = ProgramPosterRepository.getBackdropUrl(lookup) ?: ProgramPosterRepository.getPosterUrl(lookup)
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val titleTop by animateDpAsState(if (focused) 16.dp else 9.dp, label = "cardTitle")
    Column(modifier = Modifier.width(CardWidth)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            modifier = modifier.size(width = CardWidth, height = CardHeight).touchClick(onClick, onLongClick),
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.Black.copy(alpha = 0.7f), 18.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceCard, focusedContainerColor = SurfaceCard)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!image.isNullOrBlank()) {
                    FadeInImage(url = image, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else if (channel != null) {
                    // No artwork: the logo of the channel
                    LogoPlate(logoUrl = channel.logoUrl, name = channel.name, corner = 8.dp, padding = 6.dp, modifier = Modifier.align(Alignment.Center).size(width = 76.dp, height = 50.dp))
                }
                if (channel != null && !image.isNullOrBlank()) {
                    LogoPlate(
                        logoUrl = channel.logoUrl,
                        name = channel.name,
                        corner = 5.dp,
                        padding = 2.dp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(7.dp).size(width = 34.dp, height = 22.dp)
                    )
                }
                progress?.takeIf { it > 0f }?.let {
                    ProgressLine(
                        progress = it,
                        height = 3.dp,
                        trackColor = Color.Black.copy(alpha = 0.35f),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(titleTop))
        Text(
            text = when (item) {
                is HomeItem.Live -> program?.title ?: item.channel.name
                is HomeItem.Title -> item.title.title
                is HomeItem.Event -> item.event.program.title
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = if (focused) 1f else 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = when (item) {
                // Without a program, the name is already the title
                is HomeItem.Live -> program?.let { "${item.channel.name} · ${it.startTime} – ${it.endTime}" }.orEmpty()
                is HomeItem.Title -> item.progress?.let { p ->
                    val left = (p.durationMs - p.positionMs).coerceAtLeast(0)
                    if (left > 0) stringResource(R.string.home_time_left, durationLabel(left)) else null
                } ?: item.title.year?.toString().orEmpty()
                is HomeItem.Event -> "${eventWhen(item.event.program, now)} · ${item.event.channel.name}"
            },
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "En direct", "21:00", "Demain 18:30", "Le 9 à 20:45", or the replay. */
@Composable
private fun eventWhen(program: GuideProgram, now: Long): String {
    if (program.endMillis <= now) return stringResource(R.string.program_replay_tag)
    if (program.startMillis <= now) return stringResource(R.string.player_live)
    val clock = EpgResolver.formatClock(program.startMillis)
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(program.startMillis).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> clock
        today.plusDays(1) -> stringResource(R.string.sport_tomorrow, clock)
        else -> stringResource(R.string.sport_later, day.dayOfMonth, clock)
    }
}

/** A button of the featured item: white for the main one, glass for the others. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GlassButton(button: HeroButton, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = button.onClick,
        modifier = modifier
            .height(42.dp)
            .then(if (button.primary) Modifier else Modifier.glass(shape))
            .touchClick(button.onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.Black.copy(alpha = 0.5f), 14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (button.primary) Color.White else Color.Transparent,
            contentColor = if (button.primary) Color(0xFF0B0D12) else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B0D12)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                button.record -> Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(RecordRed))
                button.icon != null -> Icon(imageVector = button.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(text = button.label, fontSize = 14.sp, fontWeight = if (button.primary) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 20.dp else 6.dp, label = "dot")
            Box(
                modifier = Modifier
                    .size(width = width, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = if (index == current) 1f else 0.4f))
            )
        }
    }
}

// Featured item shown before the next one (while no live picture plays)
private const val FEATURED_ROTATION_MS = 8_000L
private val CardWidth = 176.dp
private val CardHeight = 99.dp
// Height over which the rows fade into the picture at the top of their area
private val RowsFade = 28.dp
// Between the featured item and the first row under it
private val SpotlightGap = 28.dp

/** The top [height] of the content fades out: rows scrolled up melt into the picture instead of being cut. */
private fun Modifier.fadingTop(height: Dp): Modifier = if (height == 0.dp) this else this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black),
            blendMode = BlendMode.DstIn
        )
    }

// Duration of the moves of the rows (from the featured item, from row to row)
private const val MOVE_MS = 320

/** The list of rows does not move by itself when the focus changes row: the home scrolls it once, smoothly. */
@OptIn(ExperimentalFoundationApi::class)
private object NoFollow : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** The cards of a row follow the focus as little as needed (the card fully shown), with a smooth ease. */
@OptIn(ExperimentalFoundationApi::class)
private object SmoothFollow : BringIntoViewSpec {
    @Suppress("DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(260, easing = FastOutSlowInEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 -> offset
            else -> trailing - containerSize
        }
    }
}
