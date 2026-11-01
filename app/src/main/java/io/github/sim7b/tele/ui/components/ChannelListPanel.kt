package io.github.sim7b.tele.ui.components

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.epg.GuideTimeline
import io.github.sim7b.tele.data.model.Categories
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.ui.theme.AccentCyan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sentinels of the "all channels", favorites and recent channels categories; their labels come from the resources. */
private const val ALL_CHANNELS = "__all__"
private const val FAVORITES = "__favorites__"
private const val RECENTS = "__recents__"
private const val AUTO_HIDE_MS = 20_000L
// Time on a channel before its program details show, so they do not flicker while scrolling
private const val DETAILS_DELAY_MS = 700L
// Channels skipped by CH+ / CH− (about a screen)
private const val PAGE_SIZE = 7
private val PanelWidth = 430.dp
private val ScheduleWidth = 370.dp
private val FadeWidth = 140.dp

/** Part of the panel holding the focus: the category selector, the channels, or the programs of one of them. */
private enum class PanelZone { HEADER, LIST, SCHEDULE }

/**
 * Channel panel shown over the live stream (left arrow in the player), TiviMate style:
 * the video keeps playing behind a transparent gradient, each row shows the current program and its
 * progress, the focused channel expands its category and the next program, then, after a moment, the
 * details of its program on the right.
 * ▶ moves into the programs of the channel (replays above, coming ones below), ◀ goes back, then up to
 * the category selector (◀ ▶): favorites and recent channels first. CH+ / CH− skip a page, the digits
 * go to a channel number, a long press on OK opens the menu of the channel.
 * OK switches channel, Back or inactivity closes the panel.
 */
@Composable
fun ChannelListPanel(
    channels: List<Channel>,
    playingChannelId: String,
    onSelectChannel: (Channel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Category order chosen on the home screen
    categoryOrder: List<String> = emptyList(),
    favorites: List<Channel> = emptyList(),
    // Last watched first
    recents: List<Channel> = emptyList(),
    // Programs of a channel sorted by start time (empty without guide)
    programsOf: (Channel) -> List<GuideProgram> = { emptyList() },
    actions: GuideActions = GuideActions()
) {
    val categories = remember(channels, categoryOrder, favorites.isEmpty(), recents.isEmpty()) {
        listOfNotNull(FAVORITES.takeIf { favorites.isNotEmpty() }, RECENTS.takeIf { recents.isNotEmpty() }, ALL_CHANNELS) +
            Categories.ordered(channels.map { it.group }.distinct(), categoryOrder)
    }
    var category by remember { mutableStateOf(ALL_CHANNELS) }
    if (category !in categories) category = ALL_CHANNELS
    val shown = remember(channels, favorites, recents, category) {
        when (category) {
            ALL_CHANNELS -> channels
            FAVORITES -> favorites
            RECENTS -> recents
            else -> channels.filter { it.group == category }
        }
    }
    fun moveCategory(step: Int) {
        category = categories[Math.floorMod(categories.indexOf(category) + step, categories.size)]
    }

    // The playing channel (or the first one of the category) gets the focus on opening
    val focusTargetIndex = shown.indexOfFirst { it.id == playingChannelId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusTargetIndex - 2).coerceAtLeast(0))
    // Held by the channel to focus back (on opening, coming back from the header or the programs)
    val rowFocus = remember { FocusRequester() }
    val headerFocus = remember { FocusRequester() }
    val scheduleFocus = remember { FocusRequester() }
    var anchorId by remember { mutableStateOf(shown.getOrNull(focusTargetIndex)?.id) }
    var focusedIndex by remember { mutableIntStateOf(focusTargetIndex) }
    var zone by remember { mutableStateOf(PanelZone.LIST) }
    var interactions by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val now = rememberNow()

    // Menus opened from the panel: it stays open behind them
    var menuChannel by remember { mutableStateOf<Channel?>(null) }
    var menuProgram by remember { mutableStateOf<Pair<Channel, GuideProgram>?>(null) }
    val menuOpen = menuChannel != null || menuProgram != null

    // Programs on the right: the ones browsed after ▶, else the details of the channel focused for a moment
    var scheduleChannel by remember { mutableStateOf<Channel?>(null) }
    var detailsChannel by remember { mutableStateOf<Channel?>(null) }
    val focusedChannel = shown.getOrNull(focusedIndex)
    LaunchedEffect(focusedChannel?.id, zone) {
        detailsChannel = null
        if (zone != PanelZone.LIST || focusedChannel == null) return@LaunchedEffect
        delay(DETAILS_DELAY_MS)
        detailsChannel = focusedChannel
    }
    val columnChannel = scheduleChannel ?: detailsChannel
    // Browsed: the programs that can be replayed come first, above the one on air
    val columnPrograms = remember(columnChannel, scheduleChannel, programsOf, now) {
        columnChannel?.let { channel ->
            val programs = programsOf(channel)
            val replays = if (scheduleChannel != null) programs.filter { actions.canReplay(channel, it, now) } else emptyList()
            replays + GuideTimeline.fromNow(programs, now)
        }.orEmpty()
    }
    val firstComing = remember(columnPrograms, now) { columnPrograms.indexOfFirst { it.endMillis > now }.coerceAtLeast(0) }
    var scheduleIndex by remember { mutableIntStateOf(0) }

    fun closeSchedule() {
        scheduleChannel = null
        scope.launch { rowFocus.requestFocusWithRetry() }
    }

    /** Focuses the channel at [index] of the list (number typed, page skipped). */
    fun focusChannel(index: Int) {
        val target = shown.getOrNull(index) ?: return
        anchorId = target.id
        focusedIndex = index
        scope.launch {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
            rowFocus.requestFocusWithRetry()
        }
    }

    // Remote digits: go to the channel of that number (in all the channels when the category does not have it)
    val numberEntry = rememberChannelNumberEntry(channels) { target ->
        val index = shown.indexOfFirst { it.id == target.id }
        if (index != -1) {
            focusChannel(index)
        } else {
            category = ALL_CHANNELS
            val inAll = channels.indexOfFirst { it.id == target.id }
            anchorId = target.id
            focusedIndex = inAll
            scope.launch {
                listState.scrollToItem((inAll - 2).coerceAtLeast(0))
                rowFocus.requestFocusWithRetry()
            }
        }
    }

    fun openProgram(channel: Channel, program: GuideProgram) {
        when (actions.choiceFor(channel, program, now)) {
            ProgramChoice.WATCH -> onSelectChannel(channel)
            ProgramChoice.REPLAY -> actions.replay?.invoke(channel, program)
            ProgramChoice.MENU -> menuProgram = channel to program
        }
    }

    // Automatic return to full screen after a few seconds without action (not while a menu is open)
    LaunchedEffect(interactions, menuOpen) {
        if (menuOpen) return@LaunchedEffect
        delay(AUTO_HIDE_MS)
        onDismiss()
    }

    LaunchedEffect(category) {
        // A channel already chosen (number typed) keeps the focus
        if (shown.none { it.id == anchorId }) {
            anchorId = shown.getOrNull(focusTargetIndex)?.id
            focusedIndex = focusTargetIndex
            listState.scrollToItem((focusTargetIndex - 2).coerceAtLeast(0))
        }
        // From the header, the focus stays there to go through the categories
        if (zone != PanelZone.HEADER) rowFocus.requestFocusWithRetry()
    }

    // Phone: the panel takes the whole width; phone or small tablet: the programs of a channel take the
    // place of the channels instead of showing beside them
    val screen = LocalScreen.current
    val compact = screen.compact
    val stacked = screen.touch && screen.width < PanelWidth + ScheduleWidth + FadeWidth / 2
    fun openSchedule(channel: Channel) {
        scheduleChannel = channel
        scope.launch { scheduleFocus.requestFocusWithRetry() }
    }
    val schedule: @Composable (Modifier) -> Unit = { columnModifier ->
        // Last channel shown, kept while the column closes
        val shownChannel = remember { mutableStateOf(columnChannel) }
        if (columnChannel != null) shownChannel.value = columnChannel
        ScheduleColumn(
            channel = shownChannel.value,
            programs = columnPrograms,
            firstComing = firstComing,
            now = now,
            interactive = scheduleChannel != null,
            firstFocus = scheduleFocus,
            actions = actions,
            onOpen = { program -> scheduleChannel?.let { openProgram(it, program) } },
            onFocusIndex = { scheduleIndex = it },
            onClose = if (stacked) ({ closeSchedule() }) else null,
            modifier = columnModifier
                .fillMaxHeight()
                .onFocusChanged { if (it.hasFocus) zone = PanelZone.SCHEDULE }
                .onAnyTouch { interactions++ }
                .blockTouches()
        )
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .drawBehind {
                if (compact) {
                    drawRect(Color.Black.copy(alpha = 0.9f))
                    return@drawBehind
                }
                // Opaque behind the text, then fading out over the video, whatever the width of the panel
                val contentEnd = ((size.width - FadeWidth.toPx()) / size.width).coerceIn(0.1f, 1f)
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.9f),
                        contentEnd * 0.82f to Color.Black.copy(alpha = 0.8f),
                        1f to Color.Transparent
                    )
                )
            }
            .onPreviewKeyEvent { event ->
                interactions++
                if (numberEntry.onKey(event)) return@onPreviewKeyEvent true
                // Back cancels a number, closes the programs, then the panel. Without this, Compose first
                // uses it to move the focus out of the list to the player, and it takes two presses.
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.type == KeyEventType.KeyUp) {
                        when {
                            numberEntry.isTyping -> numberEntry.cancel()
                            scheduleChannel != null -> closeSchedule()
                            else -> onDismiss()
                        }
                    }
                    true
                } else {
                    false
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        when (zone) {
                            PanelZone.HEADER -> moveCategory(-1)
                            PanelZone.LIST -> headerFocus.requestFocus()
                            PanelZone.SCHEDULE -> closeSchedule()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        when (zone) {
                            PanelZone.HEADER -> moveCategory(+1)
                            PanelZone.LIST -> {
                                val channel = focusedChannel
                                if (channel != null && programsOf(channel).isNotEmpty()) {
                                    scheduleChannel = channel
                                    scope.launch { scheduleFocus.requestFocusWithRetry() }
                                }
                            }
                            PanelZone.SCHEDULE -> Unit
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> when (zone) {
                        PanelZone.HEADER -> true
                        PanelZone.LIST -> {
                            if (focusedIndex <= 0) headerFocus.requestFocus()
                            focusedIndex <= 0
                        }
                        // The focus must not leave the panel through the top or bottom of the lists
                        PanelZone.SCHEDULE -> scheduleIndex <= 0
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> when (zone) {
                        PanelZone.HEADER -> {
                            scope.launch { rowFocus.requestFocusWithRetry() }
                            true
                        }
                        PanelZone.LIST -> focusedIndex >= shown.lastIndex
                        PanelZone.SCHEDULE -> scheduleIndex >= columnPrograms.lastIndex
                    }
                    // A page of channels at a time
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        if (zone == PanelZone.LIST) focusChannel((focusedIndex + PAGE_SIZE).coerceAtMost(shown.lastIndex))
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                        if (zone == PanelZone.LIST) focusChannel((focusedIndex - PAGE_SIZE).coerceAtLeast(0))
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            Column(
                modifier = Modifier
                    .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(PanelWidth))
                    .fillMaxHeight()
                    // Touch: taps here stay in the panel (beside it, they close it)
                    .onAnyTouch { interactions++ }
                    .blockTouches()
            ) {
                val index = categories.indexOf(category)
                PanelHeader(
                    category = categoryLabel(category),
                    previous = categoryLabel(categories[Math.floorMod(index - 1, categories.size)]),
                    next = categoryLabel(categories[Math.floorMod(index + 1, categories.size)]),
                    count = shown.size,
                    onPrevious = { moveCategory(-1) },
                    onNext = { moveCategory(+1) },
                    onClose = onDismiss,
                    modifier = Modifier
                        .focusRequester(headerFocus)
                        .onFocusChanged { if (it.isFocused) zone = PanelZone.HEADER }
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (it.hasFocus) zone = PanelZone.LIST },
                    contentPadding = PaddingValues(start = 18.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(shown, key = { _, channel -> channel.id }) { index, channel ->
                        PanelChannelRow(
                            channel = channel,
                            isPlaying = channel.id == playingChannelId,
                            // The channel whose programs are open stays highlighted
                            isSelected = channel.id == scheduleChannel?.id,
                            onClick = { onSelectChannel(channel) },
                            onLongClick = if (actions.loadStreams != null) ({ menuChannel = channel }) else null,
                            // Touch: the arrow of the remote's ▶, toward the programs of the channel
                            onPrograms = if (Device.isTouch && programsOf(channel).isNotEmpty()) ({ openSchedule(channel) }) else null,
                            modifier = Modifier
                                .then(if (channel.id == anchorId) Modifier.focusRequester(rowFocus) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focusedIndex = index
                                        anchorId = channel.id
                                    }
                                }
                        )
                    }
                }

                // Keys of the remote: buttons on a touch screen
                if (!Device.isTouch) {
                    Text(
                        text = stringResource(
                            when (zone) {
                                PanelZone.HEADER -> R.string.panel_header_keys_hint
                                PanelZone.LIST -> R.string.panel_keys_hint
                                PanelZone.SCHEDULE -> R.string.panel_schedule_keys_hint
                            }
                        ),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.38f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 30.dp, bottom = 22.dp, top = 6.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (!stacked) {
                AnimatedVisibility(
                    visible = columnChannel != null && columnPrograms.isNotEmpty(),
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    schedule(Modifier.width(ScheduleWidth))
                }
            }

            if (!compact) Spacer(modifier = Modifier.width(FadeWidth))
        }

        // Phone or small tablet: the programs browsed over the channels, until their arrow brings them back
        if (stacked) {
            AnimatedVisibility(
                visible = scheduleChannel != null && columnPrograms.isNotEmpty(),
                enter = fadeIn() + slideInHorizontally { it / 4 },
                exit = fadeOut() + slideOutHorizontally { it / 4 }
            ) {
                schedule(
                    (if (compact) Modifier.fillMaxWidth() else Modifier.width(PanelWidth))
                        .background(Color.Black.copy(alpha = 0.94f))
                )
            }
        }

        ChannelNumberBadge(
            entry = numberEntry,
            channels = channels,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = PanelWidth - 150.dp, top = 80.dp)
        )
    }

    menuChannel?.let { channel ->
        val loadStreams = actions.loadStreams
        if (loadStreams != null) {
            ChannelActionMenuDialog(
                channel = channel,
                isFavorite = actions.isFavorite(channel),
                onToggleFavorite = { actions.toggleFavorite(channel) },
                onHideChannel = { actions.hideChannel(channel) },
                loadStreams = { loadStreams(channel) },
                onSelectStream = { stream ->
                    // Choosing a stream starts the channel with it
                    actions.selectStream(channel, stream)
                    onSelectChannel(channel)
                },
                onDismiss = {
                    menuChannel = null
                    scope.launch { rowFocus.requestFocusWithRetry() }
                },
                automaticDescription = actions.automaticStreamDescription(channel)
            )
        }
    }

    menuProgram?.let { (channel, program) ->
        ProgramActionDialog(
            channel = channel,
            program = program,
            now = now,
            hasReminder = actions.hasReminder(channel, program),
            canReplay = actions.canReplay(channel, program, now),
            onWatchLive = { onSelectChannel(channel) },
            onToggleReminder = { actions.toggleReminder(channel, program) },
            onReplay = { actions.replay?.invoke(channel, program) },
            onDismiss = {
                menuProgram = null
                interactions++
            }
        )
    }
}

@Composable
private fun categoryLabel(category: String): String = when (category) {
    ALL_CHANNELS -> stringResource(R.string.panel_all_channels)
    FAVORITES -> stringResource(R.string.home_favorites)
    RECENTS -> stringResource(R.string.panel_recent_channels)
    else -> groupLabel(category)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelHeader(
    category: String,
    previous: String,
    next: String,
    count: Int,
    // Touch: the arrows go through the categories, a button closes the panel
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val now by produceState(initialValue = LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(5_000)
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    // Touch: the neighbors always shown, the arrows are buttons
    val isFocused by interactionSource.collectIsFocusedAsState()
    val expanded = isFocused || Device.isTouch

    Column(modifier = Modifier.padding(start = 18.dp, end = 10.dp, top = 30.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 12.dp, end = 12.dp)) {
            Text(
                text = stringResource(R.string.panel_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                color = Color.White.copy(alpha = 0.7f)
            )
            if (Device.isTouch) {
                Spacer(modifier = Modifier.width(14.dp))
                RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.vod_close), onClick = onClose, size = 36.dp)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Category selector: focused, ◀ ▶ go through the categories (no action on OK), the neighbors on its sides
        Surface(
            onClick = {},
            interactionSource = interactionSource,
            modifier = modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.13f)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                if (expanded) {
                    Text(
                        text = previous,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 90.dp)
                            .touchTap(onPrevious)
                    )
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (expanded) 0.9f else 0.45f),
                    // Small: the touch area grows to the minimum touch size
                    modifier = Modifier
                        .size(18.dp)
                        .touchTap(onPrevious)
                )
                Text(
                    text = category,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = "  ·  $count",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (expanded) 0.9f else 0.45f),
                    modifier = Modifier
                        .size(18.dp)
                        .touchTap(onNext)
                )
                if (expanded) {
                    Text(
                        text = next,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 90.dp)
                            .touchTap(onNext)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelChannelRow(
    channel: Channel,
    isPlaying: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // Touch: button toward the programs of the channel (null: none)
    onPrograms: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val program = channel.currentProgram

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().touchClick(onClick, onLongClick),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Color.White.copy(alpha = 0.07f) else Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.13f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text(
                text = channel.number?.toString() ?: "",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = if (isFocused) 0.75f else 0.4f),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .width(30.dp)
                    .padding(top = 9.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            LogoPlate(
                logoUrl = channel.logoUrl,
                name = channel.name,
                corner = 6.dp,
                padding = 4.dp,
                modifier = Modifier.size(width = 54.dp, height = 36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(AccentCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = channel.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (program != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${program.startTime} – ${program.endTime}",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }
                }

                Text(
                    text = program?.title ?: stringResource(R.string.panel_no_information),
                    fontSize = 12.sp,
                    // Without guide: discreet, the channels with a program stand out
                    color = Color.White.copy(alpha = if (program != null) 0.72f else 0.35f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (program != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ProgressLine(
                        progress = program.progress,
                        trackColor = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Details expanded on the focused channel: category and next program (synopsis on the right)
                AnimatedVisibility(
                    visible = isFocused && program != null &&
                        (!program.category.isNullOrBlank() || !program.nextTitle.isNullOrBlank()),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        if (!program?.category.isNullOrBlank()) {
                            Text(
                                text = program?.category.orEmpty().uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp,
                                color = Color.White.copy(alpha = 0.45f)
                            )
                        }
                        if (!program?.nextTitle.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.panel_next_program, program?.nextStartTime.orEmpty(), program?.nextTitle.orEmpty()),
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }

            onPrograms?.let { open ->
                Spacer(modifier = Modifier.width(8.dp))
                RoundIconButton(
                    icon = Icons.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.panel_programs, channel.name),
                    onClick = open,
                    size = 36.dp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Programs of a channel, right of the channels. Shown alone after a moment on a channel (from now on,
 * its program on air in detail), browsed after ▶ ([interactive]: the replays above [firstComing], the
 * focused program in detail; OK watches, replays or opens the menu of a coming program).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ScheduleColumn(
    channel: Channel?,
    programs: List<GuideProgram>,
    firstComing: Int,
    now: Long,
    interactive: Boolean,
    firstFocus: FocusRequester,
    actions: GuideActions,
    onOpen: (GuideProgram) -> Unit,
    onFocusIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Shown in place of the channels (phone): an arrow back to them
    onClose: (() -> Unit)? = null
) {
    val zone = remember { ZoneId.systemDefault() }
    // The program on air at the top, the replays above it
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstComing)
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            onClose?.let { close ->
                RoundIconButton(
                    icon = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.panel_title),
                    onClick = close,
                    size = 36.dp,
                    modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 4.dp)
                )
            }
            Text(
                text = channel?.name.orEmpty(),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 34.dp, bottom = 14.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 0.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(programs) { index, program ->
                // Day change: a separator before the first program of each new day
                val day = GuideTimeline.dayOffset(program.startMillis, now, zone)
                val previousDay = programs.getOrNull(index - 1)?.let { GuideTimeline.dayOffset(it.startMillis, now, zone) } ?: 0
                if (day != previousDay) {
                    Text(
                        text = dayLabel(program.startMillis, now).uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        color = AccentCyan,
                        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp)
                    )
                }
                val replay = channel != null && actions.canReplay(channel, program, now)
                val reminder = channel != null && actions.hasReminder(channel, program)
                if (interactive) {
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()
                    Surface(
                        onClick = { onOpen(program) },
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == firstComing) Modifier.focusRequester(firstFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocusIndex(index) }
                            .touchClick({ onOpen(program) }),
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.13f)
                        )
                    ) {
                        ScheduleEntry(program, now, expanded = isFocused, replay = replay, reminder = reminder)
                    }
                } else {
                    ScheduleEntry(program, now, expanded = index == 0, replay = replay, reminder = reminder)
                }
            }
        }
    }
}

@Composable
private fun ScheduleEntry(program: GuideProgram, now: Long, expanded: Boolean, replay: Boolean, reminder: Boolean) {
    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
        if (expanded) {
            ProgramDetails(
                program = program,
                now = now,
                primary = Color.White,
                secondary = Color.White.copy(alpha = 0.55f),
                titleSize = 15,
                descriptionLines = 6,
                badges = programBadges(replay, reminder)
            )
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = EpgResolver.formatClock(program.startMillis),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.width(46.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = program.title,
                            fontSize = 13.sp,
                            // Past: dimmer
                            color = Color.White.copy(alpha = if (program.endMillis <= now) 0.6f else 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (replay) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Tag(text = stringResource(R.string.program_replay_tag), color = AccentCyan)
                        }
                        if (reminder) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Filled.Notifications, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(13.dp))
                        }
                    }
                    if (program.startMillis <= now && now < program.endMillis) {
                        Spacer(modifier = Modifier.height(5.dp))
                        ProgressLine(
                            progress = EpgResolver.progress(program.startMillis, program.endMillis, now),
                            trackColor = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
