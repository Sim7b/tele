package io.github.sim7b.tele.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.RecordDot
import io.github.sim7b.tele.ui.components.groupLabel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.model.Categories
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.ChannelList
import io.github.sim7b.tele.data.model.ChannelListView
import io.github.sim7b.tele.data.model.HomeRow
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.remote.ProgramPosterRepository
import io.github.sim7b.tele.data.sport.SportEvent
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.ui.components.ChannelActionMenuDialog
import io.github.sim7b.tele.ui.components.CompactChannelCard
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.LogoPlate
import io.github.sim7b.tele.ui.components.ProgressLine
import io.github.sim7b.tele.ui.components.RoundIconButton
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.focusRing
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.components.touchClick
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.SurfaceCard
import io.github.sim7b.tele.ui.theme.SurfaceDark
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import io.github.sim7b.tele.ui.vod.PosterCard
import io.github.sim7b.tele.ui.vod.ResumeRow
import io.github.sim7b.tele.ui.vod.VodChip
import io.github.sim7b.tele.ui.vod.VodTitleActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the immersive home shows over the whole screen for the focused item: a channel (its program, then its
 * live picture after [previewDelayMs]), a sport event, or a movie or series.
 */
private data class Spotlight(
    val key: String,
    val title: String,
    val overline: String?,
    val details: String?,
    val description: String?,
    val imageUrl: String?,
    // Looked up by title (TVMaze) when the guide has no image
    val imageTitle: String?,
    val logoUrl: String?,
    val live: Boolean,
    val progress: Float?,
    // On air: its live picture after the delay
    val channel: Channel?
)

/**
 * Live TV home where the focused item fills the screen (its program artwork, then its live picture), the
 * rows of channels along the bottom: favorites, movies and series in progress, followed sport, the Trakt
 * watchlist, then each category. The usual home stays the default (see [HomeScreen]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
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
    onOpenVod: (() -> Unit)? = null,
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
    onOpenTitle: (VodTitle) -> Unit = {}
) {
    var spotlight by remember { mutableStateOf<Spotlight?>(null) }
    var menuChannel by remember { mutableStateOf<Channel?>(null) }
    // Row holding the focus (0: the first one, the header shown)
    var focusedRow by remember { mutableIntStateOf(0) }
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    // Side menu (left at the start of a row): the other screens, then every row to jump to it
    var railOpen by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun rowRequester(key: String) = rowFocus.getOrPut(key) { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Live picture of the focused channel once it stayed focused for the delay
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

    // Categories: the lists of the user first, then the groups in the chosen order
    val categoryRows = remember(channels, categoryOrder, channelLists, rows) {
        if (HomeRow.CATEGORIES !in rows) {
            emptyList()
        } else {
            channelLists.filter { it.channels.isNotEmpty() }.map { "list:${it.id}" to (it.name to it.channels) } +
                Categories.ordered(channels.map { it.group }.distinct(), categoryOrder).map { group ->
                    "group:$group" to (group to channels.filter { it.group == group })
                }
        }
    }

    fun channelSpotlight(key: String, channel: Channel): Spotlight {
        val program = channel.currentProgram
        return Spotlight(
            key = key,
            title = program?.title ?: channel.name,
            overline = channel.name,
            details = program?.let { listOfNotNull("${it.startTime} â ${it.endTime}", it.category, it.nextTitle?.let { next -> "${it.nextStartTime ?: ""} ${next}".trim() }).joinToString("  Â·  ") },
            description = program?.description,
            imageUrl = program?.posterUrl,
            imageTitle = program?.title,
            logoUrl = channel.logoUrl,
            live = true,
            progress = program?.progress,
            channel = channel
        )
    }

    fun eventSpotlight(event: SportEvent): Spotlight {
        val program = event.program
        val onAir = program.startMillis <= now && program.endMillis > now
        return Spotlight(
            key = "sport:${event.channel.id}:${program.startMillis}",
            title = program.title,
            overline = event.channel.name,
            details = listOfNotNull(
                "${EpgResolver.formatClock(program.startMillis)} â ${EpgResolver.formatClock(program.endMillis)}",
                program.category
            ).joinToString("  Â·  "),
            description = program.description,
            imageUrl = program.posterUrl,
            imageTitle = program.title,
            logoUrl = event.channel.logoUrl,
            live = onAir,
            progress = if (onAir) ((now - program.startMillis).toFloat() / (program.endMillis - program.startMillis)).coerceIn(0f, 1f) else null,
            channel = event.channel.takeIf { onAir }
        )
    }

    fun titleSpotlight(title: VodTitle): Spotlight = Spotlight(
        key = "vod:${title.key}",
        title = title.title,
        overline = null,
        details = listOfNotNull(title.year?.toString(), title.genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() }).joinToString("  Â·  "),
        description = title.plot,
        imageUrl = title.backdropUrl ?: title.posterUrl,
        imageTitle = null,
        logoUrl = null,
        live = false,
        progress = null,
        channel = null
    )

    fun Modifier.spot(key: String, make: () -> Spotlight): Modifier = this
        .then(if (key == (lastFocusedKey ?: "fav:${favorites.firstOrNull()?.id}")) Modifier.focusRequester(firstFocus) else Modifier)
        .onFocusChanged {
            if (it.isFocused) {
                lastFocusedKey = key
                spotlight = make()
            }
        }

    LaunchedEffect(favorites.isNotEmpty(), channels.isNotEmpty()) {
        if (favorites.isNotEmpty() || channels.isNotEmpty()) firstFocus.requestFocusWithRetry()
    }

    // The rows, in the order shown: key and name (for the side menu)
    val rowEntries = buildList {
        if (favorites.isNotEmpty()) add("favorites" to stringResource(R.string.home_favorites))
        if (HomeRow.RESUME in rows && resume.isNotEmpty()) add("resume" to stringResource(R.string.home_resume))
        if (HomeRow.SPORT in rows && sport.isNotEmpty()) add("sport" to stringResource(R.string.home_row_sport))
        if (HomeRow.TRAKT in rows && watchlist.isNotEmpty()) add("trakt" to stringResource(R.string.home_row_trakt))
        categoryRows.forEach { (key, content) -> add(key to groupLabel(content.first)) }
    }
    LaunchedEffect(railOpen) { if (railOpen) railFocus.requestFocusWithRetry() }
    BackHandler(enabled = railOpen) {
        railOpen = false
        scope.launch { firstFocus.requestFocusWithRetry() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
            .onPreviewKeyEvent { event ->
                // Left with nothing more on the left: the side menu
                if (railOpen || menuChannel != null || event.type != KeyEventType.KeyDown || event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                if (!focusManager.moveFocus(FocusDirection.Left)) railOpen = true
                true
            }
    ) {
        // Artwork of the focused item, then its live picture
        Crossfade(targetState = spotlight?.key, label = "spotlight") { key ->
            val shown = spotlight?.takeIf { it.key == key }
            if (shown != null) SpotlightBackdrop(shown)
        }
        AnimatedVisibility(visible = previewKey != null && previewKey == spotlight?.key, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) { previewSurface() }
        }
        // Below the first row: the header goes, the details move up in its place
        val headerAlpha by animateFloatAsState(if (focusedRow == 0) 1f else 0f, label = "header")
        val detailsTop by animateDpAsState(if (focusedRow == 0) 104.dp else 40.dp, label = "details")
        // Scrims: the texts on the left, the rows at the bottom stay readable over any picture
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.6f to Color.Transparent)
            )
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.4f to Color.Transparent, 0.7f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.85f))
            )
        )

        // Header: time, the other screens, search, settings; gone once below the first row (back with it)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = headerAlpha }
                .padding(start = screenMargin(48.dp), end = screenMargin(48.dp), top = 24.dp)
        ) {
            HeaderClock()
            Spacer(modifier = Modifier.weight(1f))
            onOpenGuide?.let {
                VodChip(label = stringResource(R.string.home_open_guide), selected = false, onClick = it)
                Spacer(modifier = Modifier.width(10.dp))
            }
            onOpenVod?.let {
                VodChip(label = stringResource(R.string.home_open_vod), selected = false, onClick = it)
                Spacer(modifier = Modifier.width(10.dp))
            }
            onOpenRecordings?.let {
                VodChip(label = stringResource(R.string.recordings_screen_title), selected = false, onClick = it)
                Spacer(modifier = Modifier.width(10.dp))
            }
            RoundIconButton(icon = Icons.Filled.Search, contentDescription = stringResource(R.string.home_search), onClick = { onOpenSearch(false) })
            Spacer(modifier = Modifier.width(10.dp))
            SettingsButton(updateAvailable = updateAvailable, onClick = onOpenSettings)
        }

        // The focused item: channel, title, hours, summary
        spotlight?.let { shown ->
            SpotlightDetails(
                shown,
                live = previewKey != null && previewKey == shown.key,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = screenMargin(48.dp), top = detailsTop)
                    .widthIn(max = 620.dp)
            )
        }

        // Rows along the bottom: the focused one comes up first
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(0.42f)
                .fadingTop(RowsFade),
            contentPadding = PaddingValues(top = RowsFade, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var rowIndex = 0
            fun Modifier.bringUp(index: Int): Modifier = focusRequester(rowRequester(rowEntries.getOrNull(index)?.first.orEmpty()))
                .onFocusChanged {
                    if (it.hasFocus) {
                        focusedRow = index
                        scope.launch { listState.animateScrollToItem(index) }
                    }
                }

            if (favorites.isNotEmpty()) {
                val index = rowIndex++
                item(key = "favorites") {
                    ChannelRow(stringResource(R.string.home_favorites), favorites, "fav", Modifier.bringUp(index), onChannelSelected, { menuChannel = it }) { key, channel ->
                        Modifier.spot(key) { channelSpotlight(key, channel) }
                    }
                }
            }
            if (HomeRow.RESUME in rows && resume.isNotEmpty()) {
                val index = rowIndex++
                item(key = "resume") {
                    ResumeRow(
                        items = resume,
                        onOpen = onOpenResume,
                        actions = resumeActions,
                        myList = vodMyList,
                        collections = vodCollections,
                        onFocused = { title -> spotlight = titleSpotlight(title) },
                        modifier = Modifier.padding(start = screenMargin(48.dp)).bringUp(index)
                    )
                }
            }
            if (HomeRow.SPORT in rows && sport.isNotEmpty()) {
                val index = rowIndex++
                item(key = "sport") {
                    Column(modifier = Modifier.bringUp(index)) {
                        SectionLabel(text = stringResource(R.string.home_row_sport), modifier = Modifier.padding(start = screenMargin(48.dp)))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(start = screenMargin(48.dp), end = 48.dp, top = 10.dp)
                        ) {
                            items(sport, key = { "${it.channel.id}:${it.program.startMillis}" }) { event ->
                                val spot = eventSpotlight(event)
                                SportCard(event, now, onClick = { onOpenSport(event) }, modifier = Modifier.spot(spot.key) { spot })
                            }
                        }
                    }
                }
            }
            if (HomeRow.TRAKT in rows && watchlist.isNotEmpty()) {
                val index = rowIndex++
                item(key = "trakt") {
                    Column(modifier = Modifier.bringUp(index)) {
                        SectionLabel(text = stringResource(R.string.home_row_trakt), modifier = Modifier.padding(start = screenMargin(48.dp)))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(start = screenMargin(48.dp), end = 48.dp, top = 10.dp)
                        ) {
                            items(watchlist, key = { it.key }) { title ->
                                PosterCard(
                                    title = title,
                                    onClick = { onOpenTitle(title) },
                                    width = 120.dp,
                                    onFocused = { spotlight = titleSpotlight(title) },
                                    modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedKey = "vod:${title.key}" }
                                )
                            }
                        }
                    }
                }
            }
            categoryRows.forEach { (rowKey, content) ->
                val (name, rowChannels) = content
                val index = rowIndex++
                item(key = rowKey) {
                    ChannelRow(name, rowChannels, rowKey, Modifier.bringUp(index), onChannelSelected, { menuChannel = it }) { key, channel ->
                        Modifier.spot(key) { channelSpotlight(key, channel) }
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
            SideMenu(
                rows = rowEntries,
                focus = railFocus,
                onHome = {
                    railOpen = false
                    scope.launch { firstFocus.requestFocusWithRetry() }
                },
                onGuide = onOpenGuide,
                onVod = onOpenVod,
                onRecordings = onOpenRecordings,
                onSearch = { onOpenSearch(false) },
                onSettings = onOpenSettings,
                onRow = { index, key ->
                    railOpen = false
                    scope.launch {
                        listState.scrollToItem(index)
                        rowRequester(key).requestFocusWithRetry()
                    }
                },
                onClose = {
                    railOpen = false
                    scope.launch { firstFocus.requestFocusWithRetry() }
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
                scope.launch { firstFocus.requestFocusWithRetry() }
            }
        )
    }
}

/** A labelled row of channels; [focusModifier] reports the focused one (key and channel). */
@Composable
private fun ChannelRow(
    label: String,
    channels: List<Channel>,
    keyPrefix: String,
    modifier: Modifier,
    onSelect: (Channel) -> Unit,
    onMenu: (Channel) -> Unit,
    focusModifier: (key: String, Channel) -> Modifier
) {
    Column(modifier = modifier) {
        SectionLabel(text = label, modifier = Modifier.padding(start = screenMargin(48.dp)))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = screenMargin(48.dp), end = 48.dp, top = 10.dp)
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { _, channel ->
                val key = "$keyPrefix:${channel.id}"
                Box(modifier = Modifier.width(ImmersiveCardWidth)) {
                    CompactChannelCard(
                        channel = channel,
                        onClick = { onSelect(channel) },
                        onLongClick = { onMenu(channel) },
                        modifier = focusModifier(key, channel)
                    )
                }
            }
        }
    }
}

/** Artwork of the focused item over the whole screen: the program's, else the channel logo on a plain background. */
@Composable
private fun SpotlightBackdrop(shown: Spotlight) {
    // The largest picture known: the TMDB backdrop of the program (with a TMDB key), else the guide's, else TVMaze's
    val image by produceState(initialValue = shown.imageUrl, shown.key) {
        value = shown.imageUrl
        val title = shown.imageTitle ?: return@produceState
        ProgramPosterRepository.getBackdropUrl(title)?.let {
            value = it
            return@produceState
        }
        if (value.isNullOrBlank()) value = ProgramPosterRepository.getPosterUrl(title)
    }
    Box(modifier = Modifier.fillMaxSize().background(SurfaceDark)) {
        if (!image.isNullOrBlank()) {
            FadeInImage(url = image, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else if (shown.logoUrl != null || shown.overline != null) {
            LogoPlate(
                logoUrl = shown.logoUrl,
                name = shown.overline ?: shown.title,
                corner = 16.dp,
                padding = 18.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 140.dp, bottom = 120.dp)
                    .size(width = 280.dp, height = 190.dp)
            )
        }
    }
}

/** The focused item: channel, title, hours, progress, summary; over its live picture ([live]), the title and the summary only. */
@Composable
private fun SpotlightDetails(shown: Spotlight, live: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (shown.overline != null && !live) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shown.live) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(LiveRed))
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(text = stringResource(R.string.player_live), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = Color.White.copy(alpha = 0.85f))
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(text = shown.overline, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        Text(text = shown.title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        shown.details?.takeIf { it.isNotBlank() && !live }?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        shown.progress?.takeIf { !live }?.let {
            Spacer(modifier = Modifier.height(8.dp))
            ProgressLine(progress = it, height = 3.dp, trackColor = Color.White.copy(alpha = 0.2f), modifier = Modifier.width(260.dp))
        }
        shown.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
        }
    }
}

/** A followed match: on air (with its progress), its start, or its replay. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SportCard(event: SportEvent, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val program = event.program
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val onAir = program.startMillis <= now && program.endMillis > now
    val past = program.endMillis <= now
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.width(ImmersiveCardWidth).height(78.dp).touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = focusRing(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceDark, focusedContainerColor = SurfaceCard)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = when {
                    onAir -> stringResource(R.string.player_live)
                    past -> stringResource(R.string.program_replay_tag)
                    else -> programWhenLabel(program.startMillis, now)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = if (onAir) LiveRed else TextSecondary
            )
            Text(
                text = program.title,
                fontSize = 14.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = event.channel.name, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onAir) {
                Spacer(modifier = Modifier.weight(1f))
                ProgressLine(
                    progress = ((now - program.startMillis).toFloat() / (program.endMillis - program.startMillis)).coerceIn(0f, 1f),
                    height = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** "21:00", or "Tomorrow 18:30" for a later day. */
@Composable
private fun programWhenLabel(start: Long, now: Long): String {
    val clock = EpgResolver.formatClock(start)
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> clock
        today.plusDays(1) -> stringResource(R.string.sport_tomorrow, clock)
        else -> stringResource(R.string.sport_later, day.dayOfMonth, clock)
    }
}

private val ImmersiveCardWidth = 260.dp

// Height over which the rows fade into the picture at the top of their area
private val RowsFade = 36.dp

/** The top [height] of the content fades out: rows scrolled up melt into the picture instead of being cut. */
private fun Modifier.fadingTop(height: androidx.compose.ui.unit.Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black),
            blendMode = BlendMode.DstIn
        )
    }

/**
 * Side menu of the immersive home, opened by Left at the start of a row: the home, the other screens, then
 * each row (Right or Back closes it).
 */
@Composable
private fun SideMenu(
    rows: List<Pair<String, String>>,
    focus: FocusRequester,
    onHome: () -> Unit,
    onGuide: (() -> Unit)?,
    onVod: (() -> Unit)?,
    onRecordings: (() -> Unit)?,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRow: (index: Int, key: String) -> Unit,
    onClose: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .width(SideMenuWidth)
            .background(Brush.horizontalGradient(0f to SurfaceDark, 0.85f to SurfaceDark.copy(alpha = 0.97f), 1f to SurfaceDark.copy(alpha = 0.9f)))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        contentPadding = PaddingValues(start = 24.dp, end = 16.dp, top = 32.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item { MenuRow(label = stringResource(R.string.side_menu_home), icon = Icons.Filled.Home, onClick = onHome, modifier = Modifier.focusRequester(focus)) }
        onGuide?.let { item { MenuRow(label = stringResource(R.string.home_open_guide), icon = Icons.Filled.DateRange, onClick = it) } }
        onVod?.let { item { MenuRow(label = stringResource(R.string.home_open_vod), icon = Icons.Filled.PlayArrow, onClick = it) } }
        onRecordings?.let { item { MenuRow(label = stringResource(R.string.recordings_screen_title), leading = { RecordDot() }, onClick = it) } }
        item { MenuRow(label = stringResource(R.string.home_search), icon = Icons.Filled.Search, onClick = onSearch) }
        item { MenuRow(label = stringResource(R.string.home_settings), icon = Icons.Filled.Settings, onClick = onSettings) }
        if (rows.isNotEmpty()) item {
            SectionLabel(text = stringResource(R.string.side_menu_rows), modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 6.dp))
        }
        itemsIndexed(rows, key = { _, row -> row.first }) { index, (key, name) ->
            MenuRow(label = name, onClick = { onRow(index, key) })
        }
    }
}

private val SideMenuWidth = 320.dp
