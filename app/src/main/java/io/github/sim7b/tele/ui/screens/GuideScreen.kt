package io.github.sim7b.tele.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import io.github.sim7b.tele.ui.components.Device
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.GlassClock
import io.github.sim7b.tele.ui.components.GlassInk
import io.github.sim7b.tele.ui.components.GlassNavBar
import io.github.sim7b.tele.ui.components.ListsMenu
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import io.github.sim7b.tele.ui.components.LocalImmersive
import io.github.sim7b.tele.ui.components.NavSection
import io.github.sim7b.tele.ui.components.ScreenNav
import io.github.sim7b.tele.ui.components.glass
import io.github.sim7b.tele.data.remote.ProgramPosterRepository
import androidx.compose.ui.graphics.Brush
import io.github.sim7b.tele.ui.components.LocalScreen
import io.github.sim7b.tele.ui.components.TouchSteps
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.components.touchTap
import io.github.sim7b.tele.ui.vod.VodChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.epg.Catchup
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.epg.GuideNavigation
import io.github.sim7b.tele.data.epg.GuidePosition
import io.github.sim7b.tele.data.epg.GuideTimeline
import io.github.sim7b.tele.data.model.Categories
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.ui.components.ChannelNumberBadge
import io.github.sim7b.tele.ui.components.GuideActions
import io.github.sim7b.tele.ui.components.LogoPlate
import io.github.sim7b.tele.ui.components.ProgramActionDialog
import io.github.sim7b.tele.ui.components.ProgramChoice
import io.github.sim7b.tele.ui.components.choiceFor
import io.github.sim7b.tele.ui.components.programBadges
import io.github.sim7b.tele.ui.components.rememberChannelNumberEntry
import io.github.sim7b.tele.ui.components.ProgramDetails
import io.github.sim7b.tele.ui.components.dayLabel
import io.github.sim7b.tele.ui.components.groupLabel
import io.github.sim7b.tele.ui.components.rememberNow
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import java.time.ZoneId

private const val ALL_CHANNELS = "__all__"
private const val FAVORITES = "__favorites__"
private const val RECENTS = "__recents__"
private const val SPORT = "__sport__"
// Guide kept on the device: 30 hours ahead (see GuideLoader)
private const val GUIDE_HOURS_AHEAD = 30L
private val RowHeight = 46.dp

/** Width of the channel column and of a minute of the grid. */
private class GuideScale(val channelColumn: Dp, val perMinute: Dp)

// 150 dp per half hour: about 2 h 30 visible on a TV
private val TvScale = GuideScale(channelColumn = 210.dp, perMinute = 5.dp)
// Phone: logos and names only, 90 dp per half hour (more than an hour visible in portrait)
private val CompactScale = GuideScale(channelColumn = 112.dp, perMinute = 3.dp)

/**
 * Guide grid, opened from the home screen: the channels in rows and the next 30 hours in columns.
 * ◀ ▶ go from program to program (the hours scroll by half hours), ▲ ▼ change channel at the same time,
 * OK watches the channel. The details of the focused program are shown at the top.
 * Back first returns to now, then closes the guide. From the first row, ▲ reaches the categories (◀ ▶).
 * The whole grid is a single focusable element moved by the keys: tens of thousands of channels stay fluid.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GuideScreen(
    channels: List<Channel>,
    favorites: List<Channel>,
    schedules: Map<String, List<GuideProgram>>,
    playingChannelId: String?,
    onWatch: (Channel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    categoryOrder: List<String> = emptyList(),
    // Last watched first
    recents: List<Channel> = emptyList(),
    actions: GuideActions = GuideActions(),
    // Channels showing the followed sport (see SportEvents), first category after the recent ones
    sportChannels: List<Channel> = emptyList(),
    // Muted live picture of the focused channel, after [previewDelayMs] (null: none)
    previewDelayMs: Long? = null,
    onPreviewChannel: (Channel?) -> Unit = {},
    previewSurface: @Composable () -> Unit = {},
    // The other screens in a glass bar: the immersive style (the focused program over its picture, the grid in glass)
    nav: ScreenNav? = null
) {
    val now = rememberNow()
    val immersive = nav != null && !LocalScreen.current.compact
    val zone = remember { ZoneId.systemDefault() }

    val lists = actions.listCategories
    val categories = remember(channels, favorites.isEmpty(), recents.isEmpty(), sportChannels.isEmpty(), categoryOrder, lists) {
        listOfNotNull(FAVORITES.takeIf { favorites.isNotEmpty() }, RECENTS.takeIf { recents.isNotEmpty() }, SPORT.takeIf { sportChannels.isNotEmpty() }) + lists.keys + ALL_CHANNELS +
            Categories.ordered(channels.map { it.group }.distinct(), categoryOrder)
    }
    fun rowsOf(key: String): List<Channel> = when (key) {
        FAVORITES -> favorites
        RECENTS -> recents
        SPORT -> sportChannels
        ALL_CHANNELS -> channels
        in lists -> lists.getValue(key).channels
        else -> channels.filter { it.group == key }
    }
    var categoryKey by rememberSaveable { mutableStateOf(ALL_CHANNELS) }
    if (categoryKey !in categories) categoryKey = ALL_CHANNELS
    val rows = remember(categoryKey, channels, favorites, recents, sportChannels, lists) { rowsOf(categoryKey) }
    // With replay channels, the grid also goes back over the past day
    val hasReplay = remember(channels, actions.replay != null) { actions.replay != null && channels.any { it.catchupDays > 0 } }
    var menuProgram by remember { mutableStateOf<Pair<Channel, GuideProgram>?>(null) }
    fun programsAt(list: List<Channel>, row: Int): List<GuideProgram> =
        list.getOrNull(row)?.let { GuideTimeline.programsOf(it, schedules) }.orEmpty()

    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    val headerFocus = remember { FocusRequester() }
    // Immersive: the categories in a glass menu on the left, opened by Left at the start of the grid
    var categoriesOpen by remember { mutableStateOf(false) }
    val categoriesFocus = remember { FocusRequester() }
    LaunchedEffect(categoriesOpen) { if (categoriesOpen) categoriesFocus.requestFocusWithRetry() }
    val categoryLabel: @Composable (String) -> String = { key ->
        when (key) {
            FAVORITES -> stringResource(R.string.home_favorites)
            RECENTS -> stringResource(R.string.panel_recent_channels)
            SPORT -> stringResource(R.string.home_row_sport)
            ALL_CHANNELS -> stringResource(R.string.panel_all_channels)
            in lists -> lists.getValue(key).name
            else -> groupLabel(key)
        }
    }
    var headerFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val compact = LocalScreen.current.compact
    val scale = if (compact) CompactScale else TvScale

    val margin = screenMargin(40.dp)
    CompositionLocalProvider(LocalImmersive provides immersive) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(if (immersive) Modifier.background(GuideBackground) else Modifier)
    ) {
        // Immersive: the grid sits in a glass panel, inside its padding
        val gridWidth = maxWidth - margin * 2 - scale.channelColumn - if (immersive) PanelPadding * 2 else 0.dp
        val span = (gridWidth / scale.perMinute).toLong() * 60_000L
        val nowSlot = GuideTimeline.slotStart(now, zone)
        val minStart = if (hasReplay) GuideTimeline.slotStart(now - Catchup.GUIDE_PAST_WINDOW_MS, zone) else nowSlot
        val navigation = remember(span, minStart) {
            GuideNavigation(span, minStart, now + GUIDE_HOURS_AHEAD * 3_600_000L, zone)
        }

        // Saved field by field: the position comes back after watching a channel
        var row by rememberSaveable { mutableIntStateOf(rows.indexOfFirst { it.id == playingChannelId }.coerceAtLeast(0)) }
        var cursor by rememberSaveable { mutableLongStateOf(now) }
        var focusedStart by rememberSaveable { mutableLongStateOf(GuideTimeline.programAt(programsAt(rows, row), now)?.startMillis ?: -1L) }
        var viewStart by rememberSaveable { mutableLongStateOf(nowSlot) }
        val position = GuidePosition(row, cursor, focusedStart, viewStart)
        // Live picture of the focused channel once it stayed focused for the delay (not while choosing a category)
        val previewed = rows.getOrNull(row)?.takeIf { !headerFocused && previewDelayMs != null }
        var previewShown by remember { mutableStateOf(false) }
        LaunchedEffect(previewed?.id) {
            if (previewShown) {
                previewShown = false
                onPreviewChannel(null)
            }
            val channel = previewed ?: return@LaunchedEffect
            delay(previewDelayMs ?: return@LaunchedEffect)
            previewShown = true
            onPreviewChannel(channel)
        }
        fun moveTo(next: GuidePosition) {
            row = next.row
            cursor = next.cursor
            focusedStart = next.focusedStart
            viewStart = next.viewStart
        }
        if (row > rows.lastIndex) row = rows.lastIndex.coerceAtLeast(0)
        val focusedPrograms = programsAt(rows, row)
        val focusedProgram = navigation.focused(focusedPrograms, position)

        // Time goes by (or the guide stayed open a long time): the grid never starts before its limit
        LaunchedEffect(minStart) {
            if (cursor < minStart || viewStart < minStart) moveTo(navigation.now(row, focusedPrograms, now))
        }
        // The focused row stays visible
        LaunchedEffect(row, rows) {
            val first = listState.firstVisibleItemIndex
            val fullyVisible = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
            when {
                row < first -> listState.scrollToItem(row)
                // Next row: the grid moves by one
                row == first + fullyVisible -> listState.scrollToItem(row - fullyVisible + 1)
                // Jump (opening on the last channel watched, number, page): near the top, two channels above it
                row > first + fullyVisible -> listState.scrollToItem((row - 2).coerceAtLeast(0))
            }
        }
        LaunchedEffect(Unit) { gridFocus.requestFocusWithRetry() }

        fun selectCategory(key: String) {
            categoryKey = key
            val newRows = rowsOf(categoryKey)
            val newRow = newRows.indexOfFirst { it.id == playingChannelId }.coerceAtLeast(0)
            moveTo(navigation.toRow(newRow, programsAt(newRows, newRow), position))
            scope.launch { listState.scrollToItem((newRow - 2).coerceAtLeast(0)) }
        }

        fun changeCategory(step: Int) = selectCategory(categories[Math.floorMod(categories.indexOf(categoryKey) + step, categories.size)])

        fun moveToRow(target: Int) {
            if (rows.isEmpty()) return
            val index = target.coerceIn(0, rows.lastIndex)
            moveTo(navigation.toRow(index, programsAt(rows, index), position))
        }

        // Remote digits: the row of that channel number (in all the channels when the category does not have it)
        val numberEntry = rememberChannelNumberEntry(channels) { target ->
            val index = rows.indexOfFirst { it.id == target.id }
            if (index != -1) {
                moveToRow(index)
            } else {
                categoryKey = ALL_CHANNELS
                val inAll = channels.indexOfFirst { it.id == target.id }
                moveTo(navigation.toRow(inAll, programsAt(channels, inAll), position))
            }
        }

        fun openFocused() {
            val channel = rows.getOrNull(row) ?: return
            when (actions.choiceFor(channel, focusedProgram, now)) {
                ProgramChoice.WATCH -> onWatch(channel)
                ProgramChoice.REPLAY -> focusedProgram?.let { actions.replay?.invoke(channel, it) }
                ProgramChoice.MENU -> focusedProgram?.let { menuProgram = channel to it }
            }
        }

        fun backToNow() {
            moveTo(navigation.now(row, focusedPrograms, now))
            scope.launch { listState.scrollToItem((row - 2).coerceAtLeast(0)) }
        }

        // A number typed is cancelled, then back to now, then out of the guide
        fun back() {
            if (numberEntry.isTyping) {
                numberEntry.cancel()
            } else if (!headerFocused && (viewStart != nowSlot || cursor !in now - GuideTimeline.SLOT_MS..now + GuideTimeline.SLOT_MS)) {
                backToNow()
            } else {
                onBack()
            }
        }
        // Touch: Back even when the focus left the grid (the key handler below needs it)
        BackHandler(enabled = Device.isTouch) { back() }

        // Touch: a tap on a program shows it at the top, a second tap opens it (watch, replay, menu)
        fun tapAt(index: Int, time: Long) {
            scope.launch { gridFocus.requestFocusWithRetry() }
            val programs = programsAt(rows, index)
            if (index == row && GuideTimeline.programAt(programs, time)?.startMillis == focusedProgram?.startMillis) {
                openFocused()
            } else {
                moveTo(navigation.tapped(index, programs, time, position))
            }
        }

        // Immersive: the live picture of the focused channel (once previewed), else the picture of its program,
        // over the top of the screen
        if (immersive) {
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f)) {
                ProgramBackdrop(program = focusedProgram, modifier = Modifier.fillMaxSize())
                // Over it, seen once it plays (a channel that does not play leaves the picture)
                if (previewShown) previewSurface()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(0f to GuideBackground.copy(alpha = 0.92f), 0.5f to GuideBackground.copy(alpha = 0.45f), 0.85f to Color.Transparent))
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0f to GuideBackground.copy(alpha = 0.6f), 0.25f to Color.Transparent, 0.4f to Color.Transparent, 1f to GuideBackground))
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = margin, end = margin, top = if (immersive) 18.dp else 26.dp, bottom = 18.dp)
                .onPreviewKeyEvent { event ->
                    if (!headerFocused && numberEntry.onKey(event)) return@onPreviewKeyEvent true
                    if (event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) back()
                    true
                }
        ) {
            val header: @Composable () -> Unit = { GuideHeader(
                immersive = immersive,
                category = categoryLabel(categoryKey),
                count = rows.size,
                now = now,
                onPrevious = { changeCategory(-1) },
                onNext = { changeCategory(+1) },
                onNow = ::backToNow,
                modifier = Modifier
                    .focusRequester(headerFocus)
                    .onFocusChanged { headerFocused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { changeCategory(-1); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { changeCategory(+1); true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { gridFocus.requestFocus(); true }
                            // Immersive: up to the glass bar
                            KeyEvent.KEYCODE_DPAD_UP -> !immersive
                            else -> false
                        }
                    }
            ) }
            if (nav != null && immersive) {
                Box(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    GlassNavBar(nav = nav, current = NavSection.GUIDE, modifier = Modifier.align(Alignment.Center))
                    GlassClock(modifier = Modifier.align(Alignment.CenterEnd))
                }
            } else {
                header()
            }

            // Details of the focused program (or of the channel, without guide)
            val primary = if (immersive) Color.White else TextPrimary
            val secondary = if (immersive) Color.White.copy(alpha = 0.75f) else TextSecondary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (immersive) 150.dp else 116.dp)
                    .padding(top = if (immersive) 18.dp else 10.dp, bottom = 12.dp)
            ) {
                val channel = rows.getOrNull(row)
                if (focusedProgram != null) {
                    ProgramDetails(
                        program = focusedProgram,
                        now = now,
                        primary = primary,
                        secondary = secondary,
                        descriptionLines = 2,
                        // Immersive: its picture is the background
                        showPoster = !immersive,
                        titleSize = if (immersive) 28 else 19,
                        modifier = Modifier.fillMaxWidth(if (compact) 1f else if (immersive) 0.55f else 0.8f),
                        badges = channel?.let { programBadges(actions.canReplay(it, focusedProgram, now), actions.hasReminder(it, focusedProgram), actions.isRecorded(it, focusedProgram)) }.orEmpty()
                    )
                } else if (channel != null) {
                    Column {
                        Text(text = channel.name, fontSize = if (immersive) 28.sp else 19.sp, fontWeight = FontWeight.SemiBold, color = primary, maxLines = 1)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = stringResource(R.string.panel_no_information), fontSize = 12.sp, color = secondary)
                    }
                }
                if (previewShown && !compact && !immersive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                    ) { previewSurface() }
                }
            }

            val grid: @Composable ColumnScope.() -> Unit = {
            TimeRuler(viewStart = viewStart, span = span, now = now, gridWidth = gridWidth, scale = scale)

            // Touch: dragging sideways moves the hours by half hours (the rows scroll up and down by themselves)
            val slotPx = with(LocalDensity.current) { (scale.perMinute * (GuideTimeline.SLOT_MS / 60_000f)).toPx() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (!Device.isTouch) Modifier else Modifier.pointerInput(slotPx, navigation) {
                            var dragged = 0f
                            detectHorizontalDragGestures(onDragStart = { dragged = 0f }) { change, amount ->
                                change.consume()
                                dragged += amount
                                val slots = TouchSteps.steps(dragged, slotPx)
                                if (slots != 0) {
                                    moveTo(navigation.scrolled(GuidePosition(row, cursor, focusedStart, viewStart), slots))
                                    dragged = TouchSteps.remainder(dragged, slots, slotPx)
                                }
                            }
                        }
                    )
                    .focusRequester(gridFocus)
                    .focusable()
                    .onKeyEvent { event ->
                        val keyCode = event.nativeKeyEvent.keyCode
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (event.type == KeyEventType.KeyUp) openFocused()
                            return@onKeyEvent true
                        }
                        // A page of channels at a time
                        val page = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo(navigation.right(focusedPrograms, position))
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val next = navigation.left(focusedPrograms, position)
                                // Immersive: Left at the start of the grid opens the categories
                                if (immersive && next == position) categoriesOpen = true else moveTo(next)
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> if (row < rows.lastIndex) {
                                moveTo(navigation.toRow(row + 1, programsAt(rows, row + 1), position))
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> if (row > 0) {
                                moveTo(navigation.toRow(row - 1, programsAt(rows, row - 1), position))
                            } else {
                                headerFocus.requestFocus()
                            }
                            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> moveToRow(row + page)
                            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> moveToRow(row - page)
                            else -> return@onKeyEvent false
                        }
                        true
                    }
            ) {
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.guide_no_channels),
                        fontSize = 14.sp,
                        color = TextSecondary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                LazyColumn(
                    state = listState,
                    // Moved by the keys of the grid only (and by the finger on a touch screen)
                    userScrollEnabled = Device.isTouch,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(rows, key = { _, channel -> channel.id }) { index, channel ->
                        GuideRow(
                            channel = channel,
                            programs = GuideTimeline.inWindow(GuideTimeline.programsOf(channel, schedules), viewStart, viewStart + span),
                            isFocusedRow = index == row && !headerFocused,
                            focusedStart = if (index == row) focusedProgram?.startMillis else null,
                            holeCursor = if (index == row && focusedProgram == null) cursor else null,
                            isPlaying = channel.id == playingChannelId,
                            actions = actions,
                            viewStart = viewStart,
                            span = span,
                            now = now,
                            gridWidth = gridWidth,
                            zone = zone,
                            scale = scale,
                            onTapTime = { time -> tapAt(index, time) },
                            onTapChannel = { onWatch(channel) }
                        )
                    }
                }
                // Now line over the rows
                if (now in viewStart until viewStart + span) {
                    Box(
                        modifier = Modifier
                            .padding(start = scale.channelColumn + offsetOf(now, viewStart, scale))
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(if (immersive) Color.White.copy(alpha = 0.9f) else AccentCyan.copy(alpha = 0.85f))
                    )
                }
            }
            }
            if (immersive) {
                // The category selector and the grid in a glass panel
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(18.dp))
                        .background(PanelTint)
                        .padding(horizontal = PanelPadding, vertical = 10.dp)
                ) {
                    header()
                    Spacer(modifier = Modifier.height(8.dp))
                    grid()
                }
            } else {
                grid()
            }

            // Keys of the remote: taps and buttons on a touch screen
            if (!Device.isTouch) {
                Text(
                    text = stringResource(if (headerFocused) R.string.guide_header_keys_hint else R.string.guide_keys_hint),
                    fontSize = 11.sp,
                    color = TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        ChannelNumberBadge(
            entry = numberEntry,
            channels = channels,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 74.dp, end = margin)
        )

        fun closeCategories() {
            categoriesOpen = false
            scope.launch { gridFocus.requestFocusWithRetry() }
        }
        BackHandler(enabled = categoriesOpen) { closeCategories() }
        AnimatedVisibility(
            visible = categoriesOpen,
            enter = fadeIn() + slideInHorizontally { -it / 3 },
            exit = fadeOut() + slideOutHorizontally { -it / 3 },
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            ListsMenu(
                title = stringResource(R.string.guide_title),
                entries = categories.map { it to categoryLabel(it) },
                current = categories.indexOf(categoryKey),
                focus = categoriesFocus,
                onPick = { _, key ->
                    selectCategory(key)
                    closeCategories()
                },
                onClose = ::closeCategories
            )
        }
    }
    }

    menuProgram?.let { (channel, program) ->
        ProgramActionDialog(
            channel = channel,
            program = program,
            now = now,
            hasReminder = actions.hasReminder(channel, program),
            canReplay = actions.canReplay(channel, program, now),
            onWatchLive = { onWatch(channel) },
            onToggleReminder = { actions.toggleReminder(channel, program) },
            onReplay = { actions.replay?.invoke(channel, program) },
            canRecord = actions.canRecord(channel, program, now),
            isRecorded = actions.isRecorded(channel, program),
            onToggleRecording = { actions.toggleRecording?.invoke(channel, program) },
            onDismiss = {
                menuProgram = null
                scope.launch { gridFocus.requestFocusWithRetry() }
            }
        )
    }
}

private fun offsetOf(time: Long, viewStart: Long, scale: GuideScale): Dp = scale.perMinute * ((time - viewStart) / 60_000f)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideHeader(
    // The category selector alone, in glass (the title and the time are in the glass bar)
    immersive: Boolean,
    category: String,
    count: Int,
    now: Long,
    // Touch: the arrows of the selector, and a button back to now
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val compact = LocalScreen.current.compact
    // Touch: the arrows always shown, as buttons
    val arrows = isFocused || Device.isTouch
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!immersive) {
            Text(
                text = stringResource(R.string.guide_title),
                fontSize = if (compact) 20.sp else 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.width(if (compact) 12.dp else 22.dp))
        }
        // Category selector: focused, ◀ ▶ go through the categories
        val pill = RoundedCornerShape(50)
        Surface(
            onClick = {},
            interactionSource = interactionSource,
            modifier = modifier
                .then(if (compact) Modifier.weight(1f, fill = false) else Modifier)
                .then(if (immersive) Modifier.glass(pill) else Modifier),
            shape = ClickableSurfaceDefaults.shape(shape = pill),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            colors = if (immersive) {
                ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = GlassInk
                )
            } else {
                ClickableSurfaceDefaults.colors(
                    containerColor = SurfaceDark,
                    contentColor = TextSecondary,
                    focusedContainerColor = TextPrimary,
                    focusedContentColor = LocalAppColors.current.background
                )
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (arrows) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = if (Device.isTouch) stringResource(R.string.guide_previous_category) else null,
                        modifier = Modifier.size(18.dp).touchTap(onPrevious)
                    )
                }
                Text(
                    text = "$category  ·  $count",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .then(if (compact) Modifier.weight(1f, fill = false) else Modifier)
                        .widthIn(max = 360.dp)
                        .padding(horizontal = 6.dp)
                )
                if (arrows) {
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = if (Device.isTouch) stringResource(R.string.guide_next_category) else null,
                        modifier = Modifier.size(18.dp).touchTap(onNext)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (Device.isTouch) {
            Spacer(modifier = Modifier.width(10.dp))
            VodChip(label = stringResource(R.string.guide_now), selected = false, onClick = onNow)
        }
        // Phone: no room for the clock, the Now button is enough (immersive: in the glass bar)
        if (!compact && !immersive) {
            if (Device.isTouch) Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "${dayLabel(now, now)}  ${EpgResolver.formatClock(now)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                color = TextSecondary
            )
        }
    }
}

/** Hours of the visible columns, with the day when the grid shows another day than today. */
@Composable
private fun TimeRuler(viewStart: Long, span: Long, now: Long, gridWidth: Dp, scale: GuideScale) {
    Row(modifier = Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(scale.channelColumn)) {
            Text(
                text = dayLabel(viewStart, now),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (LocalImmersive.current) Color.White else AccentCyan
            )
        }
        Box(modifier = Modifier.width(gridWidth).fillMaxHeight().clipToBounds()) {
            var slot = viewStart
            while (slot < viewStart + span) {
                Text(
                    text = EpgResolver.formatClock(slot),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier
                        .padding(start = offsetOf(slot, viewStart, scale) + 6.dp)
                        .align(Alignment.CenterStart)
                )
                slot += GuideTimeline.SLOT_MS
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    // Programs overlapping the visible hours
    programs: List<GuideProgram>,
    isFocusedRow: Boolean,
    focusedStart: Long?,
    // Time focused in a hole of the guide (or in a channel without guide)
    holeCursor: Long?,
    isPlaying: Boolean,
    actions: GuideActions,
    viewStart: Long,
    span: Long,
    now: Long,
    gridWidth: Dp,
    zone: ZoneId,
    scale: GuideScale,
    // Touch: a tap on the hours (the time tapped), on the channel
    onTapTime: (Long) -> Unit,
    onTapChannel: () -> Unit
) {
    val replayTag = stringResource(R.string.program_replay_tag)
    val viewEnd = viewStart + span
    val compact = LocalScreen.current.compact
    Row(modifier = Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .width(scale.channelColumn)
                .fillMaxHeight()
                .padding(vertical = 3.dp, horizontal = 0.dp)
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        !LocalImmersive.current -> if (isFocusedRow) SurfaceCard else SurfaceDark.copy(alpha = 0.5f)
                        isFocusedRow -> Color.White.copy(alpha = 0.16f)
                        else -> Color.White.copy(alpha = 0.05f)
                    }
                )
                .touchTap(onTapChannel)
                .padding(horizontal = if (compact) 6.dp else 8.dp)
        ) {
            // Phone: the logo and the name only
            if (!compact) {
                Text(
                    text = channel.number?.toString().orEmpty(),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.width(30.dp)
                )
            }
            LogoPlate(
                logoUrl = channel.logoUrl,
                name = channel.name,
                corner = 5.dp,
                padding = 3.dp,
                modifier = Modifier.size(width = 42.dp, height = 28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isPlaying) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(AccentCyan))
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = channel.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val minutePx = with(LocalDensity.current) { scale.perMinute.toPx() }
        val tapTime by rememberUpdatedState(onTapTime)
        Box(
            modifier = Modifier
                .width(gridWidth)
                .fillMaxHeight()
                .clipToBounds()
                .then(
                    if (!Device.isTouch) Modifier else Modifier.pointerInput(viewStart, minutePx) {
                        detectTapGestures { offset -> tapTime(viewStart + (offset.x / minutePx * 60_000f).toLong()) }
                    }
                )
        ) {
            if (programs.isEmpty()) {
                // No program in the visible hours: a single cell, the focused half hour when it is a hole
                val start = holeCursor?.takeIf { focusedStart == null }?.let { GuideTimeline.slotStart(it, zone).coerceAtLeast(viewStart) }
                if (start != null && isFocusedRow) {
                    GuideCell(
                        title = stringResource(R.string.panel_no_information),
                        hours = null,
                        start = start,
                        end = minOf(start + GuideTimeline.SLOT_MS, viewEnd),
                        viewStart = viewStart,
                        scale = scale,
                        focused = true,
                        onAir = false
                    )
                } else {
                    GuideCell(
                        title = stringResource(R.string.panel_no_information),
                        hours = null,
                        start = viewStart,
                        end = viewEnd,
                        viewStart = viewStart,
                        scale = scale,
                        focused = false,
                        onAir = false,
                        dimmed = true
                    )
                }
            } else {
                programs.forEach { program ->
                    val hours = "${EpgResolver.formatClock(program.startMillis)} – ${EpgResolver.formatClock(program.endMillis)}"
                    val replay = actions.canReplay(channel, program, now)
                    GuideCell(
                        title = program.title,
                        hours = if (replay) "$replayTag  ·  $hours" else hours,
                        start = maxOf(program.startMillis, viewStart),
                        end = minOf(program.endMillis, viewEnd),
                        viewStart = viewStart,
                        scale = scale,
                        focused = isFocusedRow && program.startMillis == focusedStart,
                        onAir = program.startMillis <= now && now < program.endMillis,
                        // Past without replay: cannot be watched any more
                        dimmed = program.endMillis <= now && !replay,
                        reminder = actions.hasReminder(channel, program)
                    )
                }
                if (isFocusedRow && holeCursor != null && focusedStart == null) {
                    val start = GuideTimeline.slotStart(holeCursor, zone).coerceAtLeast(viewStart)
                    GuideCell(
                        title = stringResource(R.string.panel_no_information),
                        hours = null,
                        start = start,
                        end = minOf(start + GuideTimeline.SLOT_MS, viewEnd),
                        viewStart = viewStart,
                        scale = scale,
                        focused = true,
                        onAir = false
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    hours: String?,
    start: Long,
    end: Long,
    viewStart: Long,
    scale: GuideScale,
    focused: Boolean,
    onAir: Boolean,
    dimmed: Boolean = false,
    reminder: Boolean = false
) {
    val width = offsetOf(end, viewStart, scale) - offsetOf(start, viewStart, scale)
    if (width <= 2.dp) return
    // Immersive: veils of white over the glass
    val immersive = LocalImmersive.current
    val background = when {
        focused -> if (immersive) Color.White else TextPrimary
        onAir -> if (immersive) Color.White.copy(alpha = 0.14f) else SurfaceCard
        else -> if (immersive) Color.White.copy(alpha = 0.07f) else SurfaceDark
    }
    val content = when {
        !focused -> if (immersive) Color.White else TextPrimary
        immersive -> GlassInk
        else -> LocalAppColors.current.background
    }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(start = offsetOf(start, viewStart, scale))
            .width(width - 3.dp)
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (dimmed && !focused) background.copy(alpha = background.alpha * 0.4f) else background)
            .padding(horizontal = 10.dp)
    ) {
        // Too narrow for a readable title (a weather forecast between two programs): the cell alone
        if (width < 44.dp) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (reminder) {
                Icon(Icons.Filled.Notifications, contentDescription = null, tint = if (focused) content else AccentCyan, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (focused || onAir) FontWeight.SemiBold else FontWeight.Normal,
                color = if (dimmed && !focused) TextSecondary else content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // The hours when the cell is wide enough
        if (hours != null && width >= 110.dp) {
            Text(
                text = hours,
                fontSize = 11.sp,
                color = content.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
    }
}

/**
 * Picture of the focused program over the top of the immersive guide: its TMDB backdrop, else the picture of the
 * guide, else a TVMaze one. The previous one stays while the remote moves fast.
 */
@Composable
private fun ProgramBackdrop(program: GuideProgram?, modifier: Modifier = Modifier) {
    val title = program?.title
    // The TMDB picture could not be loaded: the one of the guide
    var failed by remember(title) { mutableStateOf(false) }
    val url by produceState<String?>(null, title, failed) {
        if (title == null) {
            value = null
            return@produceState
        }
        if (!failed) delay(BACKDROP_LOOKUP_DELAY_MS)
        value = ProgramPosterRepository.getBackdropUrl(title).takeUnless { failed } ?: program.posterUrl ?: ProgramPosterRepository.getPosterUrl(title)
    }
    FadeInImage(url = url, contentDescription = null, modifier = modifier, onError = { failed = true })
}

private const val BACKDROP_LOOKUP_DELAY_MS = 300L
// Immersive guide: behind the pictures, the tint of the glass panel and the space inside it
private val GuideBackground = Color(0xFF04060C)
private val PanelTint = Color(0x8C0E121A)
private val PanelPadding = 14.dp
