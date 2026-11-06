package io.github.sim7b.tele

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil.imageLoader
import coil.request.ImageRequest
import io.github.sim7b.tele.data.epg.Catchup
import io.github.sim7b.tele.data.epg.GuideTimeline
import io.github.sim7b.tele.data.storage.Reminder
import io.github.sim7b.tele.data.storage.Reminders
import io.github.sim7b.tele.ui.components.GuideActions
import io.github.sim7b.tele.ui.components.ReminderDialog
import io.github.sim7b.tele.data.model.Categories
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.remote.ProgramPosterRepository
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.playback.TvPlayerManager
import io.github.sim7b.tele.ui.components.DISPATCHARR_AUTOMATIC
import io.github.sim7b.tele.ui.components.CastButton
import io.github.sim7b.tele.ui.components.Device
import io.github.sim7b.tele.ui.components.touchTap
import io.github.sim7b.tele.ui.components.PreviewVideo
import io.github.sim7b.tele.ui.screens.GuideScreen
import io.github.sim7b.tele.ui.screens.HomeScreen
import io.github.sim7b.tele.ui.screens.PlayerScreen
import io.github.sim7b.tele.ui.screens.QuickGuide
import io.github.sim7b.tele.ui.screens.SearchScreen
import io.github.sim7b.tele.ui.screens.SettingsScreen
import io.github.sim7b.tele.ui.screens.WelcomeScreen
import io.github.sim7b.tele.ui.theme.TeleTheme
import io.github.sim7b.tele.ui.vod.VodDetailScreen
import io.github.sim7b.tele.ui.vod.VodHomeScreen
import io.github.sim7b.tele.ui.vod.VodPlayRequest
import io.github.sim7b.tele.ui.vod.VodCategoryPicker
import io.github.sim7b.tele.ui.vod.VodPlayerScreen
import io.github.sim7b.tele.ui.vod.nextPlayRequest
import io.github.sim7b.tele.ui.vod.VodSearchScreen
import io.github.sim7b.tele.ui.vod.VodTitleActions
import io.github.sim7b.tele.ui.vod.VodSubtitles
import io.github.sim7b.tele.ui.vod.LocalPosterWidth
import androidx.compose.ui.unit.dp
import io.github.sim7b.tele.data.storage.VodStore
import io.github.sim7b.tele.data.storage.VodTrackPrefs
import io.github.sim7b.tele.data.vod.VodFilter
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodRows
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import io.github.sim7b.tele.update.UpdateState
import io.github.sim7b.tele.update.UpdateViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** Displayed screen (simple navigation, without a navigation library). */
sealed class CurrentScreen {
    object Home : CurrentScreen()
    object Settings : CurrentScreen()
    data class Search(val startWithVoice: Boolean) : CurrentScreen()

    /** Guide grid of the channels, opened from the home screen. */
    object Guide : CurrentScreen()

    /** Playback; Back returns to [returnTo] (the home screen, or the search the channel was opened from). */
    data class Player(val channel: Channel, val returnTo: CurrentScreen = Home) : CurrentScreen()

    /** Movies and series: library, sheet of a title, playback of a movie or an episode. */
    object Vod : CurrentScreen()
    object VodSearch : CurrentScreen()
    /** Sheet of a title; Back returns to [returnTo] (the library, or the search it was opened from). */
    data class VodDetail(val titleKey: String, val returnTo: CurrentScreen = Vod) : CurrentScreen()
    /** Choice of the categories of movies and series loaded from a source (from the settings). */
    data class VodCategories(val configId: String, val returnTo: CurrentScreen = Settings) : CurrentScreen()
    data class VodPlayer(val request: VodPlayRequest, val returnTo: CurrentScreen) : CurrentScreen()
}

private const val WELCOME_MIN_MS = 1_800L
private const val WELCOME_MAX_MS = 15_000L
// After the live TV is loaded: the movies and series library is prepared without slowing the startup
private const val VOD_PRELOAD_DELAY_MS = 15_000L
// Replays of TV programs go through the movie player, with this prefix on their key
private const val REPLAY_KEY_PREFIX = "replay:"
private const val REMINDER_CHECK_MS = 15_000L

/**
 * The app's single activity: hosts the Compose UI (home, player, settings),
 * the startup "Good morning/Good evening" welcome screen and the ExoPlayer instance shared across channels.
 */
class MainActivity : ComponentActivity() {

    private lateinit var playerManager: TvPlayerManager
    private val viewModel: MainViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()
    // Phone / tablet: a player is shown (system bars hidden)
    private var playerShown = false
    // A movies and series screen is displayed: its library must not be freed
    private var vodShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerManager = TvPlayerManager(this, castEnabled = Device.isTouch)
        val configRepository = viewModel.configRepository
        // Phone / tablet: drawn behind the system bars, the screens keep clear of them (safe area below)
        if (Device.isTouch) WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val enabledConfigs by configRepository.enabledConfigs.collectAsState()
            val uiDensity by configRepository.uiDensity.collectAsState()
            val themeBackground by configRepository.themeBackground.collectAsState()
            val accentColor by configRepository.accentColor.collectAsState()
            val favoriteIds by configRepository.favoriteIds.collectAsState()

            // Displayed channels: bouquet names and categories, grouped versions, renamed categories
            val displayedChannels by viewModel.displayChannels.collectAsState()
            val unmatchedChannels by viewModel.unmatchedChannels.collectAsState()
            val isLoadingChannels by viewModel.isLoading.collectAsState()
            val loadError by viewModel.loadError.collectAsState()
            val streamSlots by viewModel.streamSlots.collectAsState()
            val updateState by updateViewModel.state.collectAsState()
            val previewDelay by configRepository.previewDelay.collectAsState()
            // Movies and series module (its library and positions are read in its own screens only:
            // their updates must not recompose the live TV ones)
            val vodEnabled by configRepository.vodEnabled.collectAsState()
            val pictureFormat by configRepository.pictureFormat.collectAsState()
            val categoryOrder by configRepository.categoryOrder.collectAsState()
            // Audio language chosen in the playback options: preferred for every channel
            val audioLanguage by configRepository.audioLanguage.collectAsState()
            LaunchedEffect(audioLanguage) { playerManager.setPreferredAudioLanguage(audioLanguage) }

            val userName by viewModel.userName.collectAsState()
            // The guide grid is offered once a guide is loaded (only this flag: the programs are read in its screen)
            val hasGuide by remember { viewModel.schedules.map { schedules -> schedules.values.any { it.isNotEmpty() } }.distinctUntilChanged() }
                .collectAsState(initial = false)
            val context = LocalContext.current

            var currentScreen by remember { mutableStateOf<CurrentScreen>(CurrentScreen.Home) }
            // Last channel watched: the guide grid opens on it
            var lastWatchedId by rememberSaveable { mutableStateOf<String?>(null) }
            // Filters of the Movies and Series tabs, kept while browsing sheets; the defaults of the settings first
            val vodDisplay by configRepository.vodDisplay.collectAsState()
            var vodFilters by remember(vodDisplay.defaults) { mutableStateOf(vodDisplay.defaults) }
            LaunchedEffect(vodDisplay.hideTheater) { viewModel.vod.reapply() }
            // Collections are saved off the main thread
            fun vodStore(change: VodStore.() -> Unit) {
                lifecycleScope.launch(Dispatchers.IO) { configRepository.vodStore.change() }
            }
            // OpenSubtitles: the search needs the key, the subtitles already downloaded are always read back
            val openSubtitlesKey by configRepository.openSubtitlesKey.collectAsState()
            val subtitlesRemaining by viewModel.subtitles.remaining.collectAsState()
            val vodSubtitles = VodSubtitles(
                canSearch = openSubtitlesKey.isNotBlank(),
                search = { query, all -> viewModel.subtitles.search(query, all) },
                download = { key, result -> viewModel.subtitles.download(key, result) },
                cached = { key -> viewModel.subtitles.cached(key) },
                remaining = subtitlesRemaining
            )
            val vodActions = remember {
                VodTitleActions(
                    toggleMyList = { key -> vodStore { toggleMyList(key) } },
                    toggleInCollection = { id, key -> vodStore { toggleInCollection(id, key) } },
                    createCollectionWith = { name, key ->
                        vodStore {
                            val id = createCollection(name)
                            toggleInCollection(id, key)
                        }
                    },
                    removeFromContinue = { key -> vodStore { clearTitleProgress(key) } },
                    setWatched = { title, watched -> vodStore { if (watched) markWatched(title.key, title.key) else clearProgress(title.key) } }
                )
            }
            // The movies and series library is prepared in the background once the live TV is loaded, so that
            // it opens at once; it stays in memory (freed only when the system lacks memory, see onTrimMemory)
            LaunchedEffect(vodEnabled) {
                if (!vodEnabled) return@LaunchedEffect
                viewModel.initialSyncDone.first { it }
                delay(VOD_PRELOAD_DELAY_MS)
                viewModel.vod.open()
            }
            val inVod = currentScreen.let {
                it is CurrentScreen.Vod || it is CurrentScreen.VodSearch || it is CurrentScreen.VodDetail || it is CurrentScreen.VodPlayer
            }
            SideEffect { vodShown = inVod }
            // Back on the home screen: refresh the slots right away (leaving the player frees one)
            LaunchedEffect(currentScreen is CurrentScreen.Home) {
                if (currentScreen is CurrentScreen.Home) viewModel.refreshStreamSlots()
            }
            // Phone / tablet: the players take the whole screen, the other screens keep the navigation bar
            if (Device.isTouch) {
                val inPlayer = currentScreen is CurrentScreen.Player || currentScreen is CurrentScreen.VodPlayer
                LaunchedEffect(inPlayer) {
                    playerShown = inPlayer
                    showSystemBars(navigation = !inPlayer)
                }
            }
            // "Good morning/Good evening" welcome shown only once per app launch
            var showWelcome by rememberSaveable { mutableStateOf(true) }
            // Quick start guide: by itself after the first welcome, then from the settings
            val guideSeen by configRepository.guideSeen.collectAsState()
            var showGuide by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(showWelcome, guideSeen) {
                if (!showWelcome && !guideSeen) showGuide = true
            }

            // The welcome stays until the guide and the favorites' artwork are loaded (time-bounded)
            LaunchedEffect(Unit) {
                if (!showWelcome) return@LaunchedEffect
                val startedAt = SystemClock.elapsedRealtime()
                withTimeoutOrNull(WELCOME_MAX_MS) {
                    viewModel.initialSyncDone.first { it }
                    preloadPosters(context, viewModel.homeLists.value.favorites)
                }
                val remaining = WELCOME_MIN_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) delay(remaining)
                showWelcome = false
            }
            // Source of each channel (several sources can be enabled together)
            val configsById = remember(enabledConfigs) { enabledConfigs.associateBy { it.id } }

            // Meaning of the "Automatic" stream choice mode depending on the source of the channel
            val autoXtream = stringResource(R.string.main_stream_auto_xtream)
            val autoM3u = stringResource(R.string.main_stream_auto_m3u)
            val autoGrouped = stringResource(R.string.main_stream_auto_grouped)
            val autoDispatcharr = DISPATCHARR_AUTOMATIC
            val automaticStreamDescription: (Channel) -> String = { channel ->
                when {
                    // Grouped channel: its versions, by quality preference
                    channel.memberIds.size > 1 -> autoGrouped
                    configsById[channel.sourceId]?.type == SourceType.XTREAM -> autoXtream
                    configsById[channel.sourceId]?.type == SourceType.M3U -> autoM3u
                    else -> autoDispatcharr
                }
            }

            // Dispatcharr keys of every enabled server: a grouped channel may mix sources, and each key
            // only goes to its own server (origin check on every request)
            val streamAuths = remember(enabledConfigs) {
                enabledConfigs.filter { it.type == SourceType.DISPATCHARR }.mapNotNull { ApiKeyAuth.of(it) }
            }

            // Muted home preview: same stream as the one played when the channel opens, so it keeps going
            val previewScope = rememberCoroutineScope()
            var previewJob by remember { mutableStateOf<Job?>(null) }
            fun previewChannel(channel: Channel?) {
                previewJob?.cancel()
                if (channel == null) {
                    playerManager.stopPreview()
                    return
                }
                // Last free connection of the provider kept for the channel the user opens
                val slots = viewModel.streamSlots.value
                if (configsById[channel.sourceId]?.type == SourceType.DISPATCHARR && slots != null &&
                    !slots.allowsPreview(playerManager.isPreviewing)
                ) {
                    playerManager.stopPreview()
                    return
                }
                previewJob = previewScope.launch {
                    val url = viewModel.automaticStreamUrls(channel).firstOrNull() ?: channel.activeStreamUrl
                    // The stream choice can take a moment: a channel opened meanwhile must not be replaced
                    if (currentScreen !is CurrentScreen.Home) return@launch
                    playerManager.preview(url, configsById[channel.sourceId]?.customUserAgent, streamAuths)
                }
            }
            // Settings, search and guide: no preview behind them (the player takes it over, see playStream); any other
            // screen cancels a preview still choosing its stream, so it cannot replace the channel opened
            LaunchedEffect(currentScreen) {
                if (currentScreen is CurrentScreen.Settings || currentScreen is CurrentScreen.Search || currentScreen is CurrentScreen.Guide) previewChannel(null)
                else if (currentScreen !is CurrentScreen.Home) previewJob?.cancel()
            }

            // Hiding / favoriting a grouped channel applies to all its versions ("TF1 HD", "TF1 FHD")
            fun hideChannel(channelId: String) {
                val channel = displayedChannels.firstOrNull { it.id == channelId }
                (channel?.allIds ?: listOf(channelId)).forEach { configRepository.hideChannel(it) }
            }
            fun toggleFavorite(channelId: String) {
                val ids = displayedChannels.firstOrNull { it.id == channelId }?.allIds ?: listOf(channelId)
                val favorited = ids.filter { it in favoriteIds }
                if (favorited.isEmpty()) configRepository.toggleFavoriteChannel(channelId) else favorited.forEach { configRepository.toggleFavoriteChannel(it) }
            }

            // Keeps the home screen state (category, scroll, focus) during playback / settings
            val saveableStateHolder = rememberSaveableStateHolder()

            // Channels not individually hidden: favorites are taken from them, even if their category is hidden
            // Built off the main thread by the ViewModel (see HomeLists)
            val homeLists by viewModel.homeLists.collectAsState()
            val unhiddenChannels = homeLists.unhidden
            val visibleChannels = homeLists.visible
            val favoriteChannels = homeLists.favorites
            val categoryCounts = homeLists.categoryCounts
            // Lists of channels made by the user (with their channels found), and the channel menu actions on them
            val channelListViews by viewModel.channelLists.collectAsState()
            val savedChannelLists by configRepository.channelLists.collectAsState()
            fun toggleInList(listId: String, channel: Channel) {
                // A grouped channel may be in the list by one of its versions
                val id = savedChannelLists.firstOrNull { it.id == listId }?.channelIds?.firstOrNull { it in channel.allIds } ?: channel.id
                configRepository.toggleInChannelList(listId, id)
            }

            // Guide: recent channels (last watched first), reminders, replays and the menu of a channel
            val recentIds by viewModel.guideStore.recentChannels.collectAsState()
            val recentChannels = remember(recentIds, visibleChannels) {
                val byId = visibleChannels.associateBy { it.id }
                recentIds.mapNotNull { byId[it] }
            }
            val reminders by viewModel.guideStore.reminders.collectAsState()
            fun guideActions(returnTo: CurrentScreen) = GuideActions(
                reminders = reminders,
                toggleReminder = { channel, program ->
                    viewModel.guideStore.toggleReminder(Reminder(channel.id, channel.name, program.title, program.startMillis, program.endMillis))
                },
                replay = { channel, program ->
                    // Xtream timeshift of the program, played like a movie (seek, pause)
                    Catchup.xtreamReplayUrl(channel.streamUrl, program.startMillis, program.endMillis, channel.catchupZone)?.let { url ->
                        currentScreen = CurrentScreen.VodPlayer(
                            VodPlayRequest(
                                titleKey = "$REPLAY_KEY_PREFIX${channel.id}:${program.startMillis}",
                                episodeKey = null,
                                label = program.title,
                                sourceId = channel.sourceId,
                                url = url,
                                fallbacks = emptyList(),
                                startMs = 0L,
                                plot = program.description,
                                posterUrl = program.posterUrl,
                                imageUrl = program.posterUrl
                            ),
                            returnTo = returnTo
                        )
                    }
                },
                isFavorite = { channel -> channel.allIds.any { it in favoriteIds } },
                toggleFavorite = { channel -> toggleFavorite(channel.id) },
                hideChannel = { channel -> hideChannel(channel.id) },
                loadStreams = { channel -> viewModel.loadStreams(channel) },
                selectStream = { channel, stream -> viewModel.selectStream(channel, stream) },
                automaticStreamDescription = automaticStreamDescription,
                channelLists = channelListViews,
                savedLists = savedChannelLists,
                toggleInList = ::toggleInList,
                createList = { name, channel -> configRepository.createChannelList(name, channel.id) }
            )
            // A program with a reminder starts: shown once, on any screen (after the welcome)
            var dueReminder by remember { mutableStateOf<Reminder?>(null) }
            LaunchedEffect(reminders, showWelcome) {
                if (showWelcome) return@LaunchedEffect
                while (dueReminder == null) {
                    val due = Reminders.due(reminders, System.currentTimeMillis())
                    if (due != null) {
                        dueReminder = due
                        viewModel.guideStore.dismiss(due)
                        break
                    }
                    delay(REMINDER_CHECK_MS)
                }
            }

            // Zapping by id (not by object equality, which changes on every EPG update)
            fun zap(from: Channel, step: Int, returnTo: CurrentScreen) {
                if (visibleChannels.isEmpty()) return
                val index = visibleChannels.indexOfFirst { it.id == from.id }
                val nextIndex = if (index == -1) 0 else Math.floorMod(index + step, visibleChannels.size)
                currentScreen = CurrentScreen.Player(visibleChannels[nextIndex], returnTo)
            }

            TeleTheme(
                themeBackground = themeBackground,
                accentColor = accentColor,
                uiDensity = uiDensity
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(themeBackground.backgroundColorHex))
                        // Phone / tablet: clear of the navigation bar, the camera cutout and the keyboard
                        .then(if (Device.isTouch) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (!showWelcome) return@onPreviewKeyEvent false
                            val keyCode = event.nativeKeyEvent.keyCode
                            if (keyCode == KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                            // During the welcome, the hidden home screen must not react; OK skips it
                            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
                                event.type == KeyEventType.KeyUp
                            ) {
                                showWelcome = false
                            }
                            true
                        }
                ) {
                    when (val screen = currentScreen) {
                        is CurrentScreen.Home -> {
                            saveableStateHolder.SaveableStateProvider("home") {
                                HomeScreen(
                                    channels = visibleChannels,
                                    favorites = favoriteChannels,
                                    channelLists = channelListViews,
                                    savedLists = savedChannelLists,
                                    onToggleInList = ::toggleInList,
                                    onCreateList = { name, channel -> configRepository.createChannelList(name, channel.id) },
                                    hasSource = enabledConfigs.isNotEmpty(),
                                    favoriteIds = favoriteIds,
                                    isLoading = isLoadingChannels,
                                    loadError = loadError,
                                    streamSlots = streamSlots,
                                    updateAvailable = updateState is UpdateState.Available,
                                    onChannelSelected = { channel ->
                                        currentScreen = CurrentScreen.Player(channel)
                                    },
                                    onToggleFavorite = { chId ->
                                        toggleFavorite(chId)
                                    },
                                    onHideChannel = { chId ->
                                        hideChannel(chId)
                                    },
                                    loadStreams = { ch -> viewModel.loadStreams(ch) },
                                    onSelectStream = { ch, stream ->
                                        // Choosing a stream starts the channel directly with that stream
                                        viewModel.selectStream(ch, stream)
                                        currentScreen = CurrentScreen.Player(ch)
                                    },
                                    onMoveFavorite = { chId, toIndex ->
                                        configRepository.moveFavorite(chId, toIndex)
                                    },
                                    automaticStreamDescription = automaticStreamDescription,
                                    onHideCategory = { group ->
                                        configRepository.setGroupHidden(group, true)
                                    },
                                    categoryOrder = categoryOrder,
                                    onReorderCategories = { order -> configRepository.setCategoryOrder(order) },
                                    isCategoryRenamed = { category -> viewModel.isCategoryRenamed(category) },
                                    onRenameCategory = { category, name -> viewModel.renameCategory(category, name) },
                                    onOpenSettings = {
                                        currentScreen = CurrentScreen.Settings
                                    },
                                    onOpenSearch = { voice ->
                                        currentScreen = CurrentScreen.Search(startWithVoice = voice)
                                    },
                                    onRefresh = {
                                        viewModel.refresh()
                                    },
                                    onOpenVod = if (vodEnabled) ({ currentScreen = CurrentScreen.Vod }) else null,
                                    onOpenGuide = if (hasGuide) ({ currentScreen = CurrentScreen.Guide }) else null,
                                    // Not on a phone / tablet: the focus does not follow the eyes there, a tap opens the channel
                                    previewDelayMs = previewDelay.millis.takeIf { !showWelcome && !showGuide && !Device.isTouch },
                                    onPreviewChannel = { channel -> previewChannel(channel) },
                                    previewSurface = { PreviewVideo(playerManager) },
                                    castButton = { CastButton(playerManager) }
                                )
                            }
                        }
                        is CurrentScreen.Settings -> {
                            SettingsScreen(
                                repository = configRepository,
                                onTestConnection = { config -> viewModel.testConnection(config) },
                                categories = categoryCounts,
                                unmatchedChannels = unmatchedChannels,
                                onRefreshPlaylist = { viewModel.refresh() },
                                onRefreshEpg = { viewModel.refresh() },
                                onShowGuide = { showGuide = true },
                                onChooseVodCategories = { config -> currentScreen = CurrentScreen.VodCategories(config.id) },
                                installedVersion = updateViewModel.installedVersion,
                                updatesSupported = updateViewModel.isSupported,
                                updateState = updateState,
                                onCheckUpdate = { updateViewModel.check() },
                                onInstallUpdate = { release -> updateViewModel.install(release) },
                                onBack = {
                                    currentScreen = CurrentScreen.Home
                                }
                            )
                        }
                        is CurrentScreen.Search -> {
                            val searchSchedules by viewModel.schedules.collectAsState()
                            val searchLibrary by viewModel.vod.library.collectAsState()
                            // State kept (query, focused result) when coming back from the player
                            saveableStateHolder.SaveableStateProvider("search") {
                                SearchScreen(
                                    channels = unhiddenChannels,
                                    startWithVoice = screen.startWithVoice,
                                    onChannelSelected = { channel ->
                                        currentScreen = CurrentScreen.Player(channel, returnTo = CurrentScreen.Search(startWithVoice = false))
                                    },
                                    onBack = {
                                        saveableStateHolder.removeState("search")
                                        currentScreen = CurrentScreen.Home
                                    },
                                    schedules = searchSchedules,
                                    actions = guideActions(returnTo = CurrentScreen.Search(startWithVoice = false)),
                                    vodTitles = if (vodEnabled) searchLibrary.titles else emptyList(),
                                    onTitleSelected = { title ->
                                        currentScreen = CurrentScreen.VodDetail(title.key, returnTo = CurrentScreen.Search(startWithVoice = false))
                                    },
                                    findPoster = { title -> viewModel.vod.posterFor(title) }
                                )
                            }
                        }
                        is CurrentScreen.Guide -> {
                            val schedules by viewModel.schedules.collectAsState()
                            // Position kept (category, channel, hours) when coming back from the player
                            saveableStateHolder.SaveableStateProvider("guide") {
                                GuideScreen(
                                    channels = visibleChannels,
                                    favorites = favoriteChannels,
                                    schedules = schedules,
                                    playingChannelId = lastWatchedId ?: recentIds.firstOrNull(),
                                    categoryOrder = categoryOrder,
                                    recents = recentChannels,
                                    actions = guideActions(returnTo = CurrentScreen.Guide),
                                    onWatch = { channel -> currentScreen = CurrentScreen.Player(channel, returnTo = CurrentScreen.Guide) },
                                    onBack = {
                                        saveableStateHolder.removeState("guide")
                                        currentScreen = CurrentScreen.Home
                                    }
                                )
                            }
                        }
                        is CurrentScreen.Vod -> {
                            LaunchedEffect(Unit) { viewModel.vod.open() }
                            val vodLibrary by viewModel.vod.library.collectAsState()
                            val vodProgress by configRepository.vodStore.progress.collectAsState()
                            val vodMyList by configRepository.vodStore.myList.collectAsState()
                            val vodCollections by configRepository.vodStore.collections.collectAsState()
                            val vodRankings by viewModel.vod.rankings.collectAsState()
                            val indexVersion by viewModel.vod.index.version.collectAsState()
                            val indexProgress by viewModel.vod.index.progress.collectAsState()
                            val tmdbKey by configRepository.tmdbKey.collectAsState()
                            // First opening of a source: its categories are chosen before anything is loaded
                            val toChoose = vodLibrary.toChoose.firstOrNull()
                            if (toChoose != null) {
                                VodCategoryPicker(
                                    source = toChoose,
                                    current = null,
                                    loadCategories = { viewModel.vod.categories(toChoose) },
                                    onChoose = { selection -> viewModel.vod.choose(toChoose, selection) },
                                    onBack = { currentScreen = CurrentScreen.Home }
                                )
                            } else saveableStateHolder.SaveableStateProvider("vod") {
                                CompositionLocalProvider(LocalPosterWidth provides vodDisplay.posterSize.widthDp.dp) {
                                VodHomeScreen(
                                    library = vodLibrary,
                                    progress = vodProgress,
                                    myList = vodMyList,
                                    collections = vodCollections,
                                    rankings = vodRankings,
                                    indexVersion = indexVersion,
                                    indexProgress = indexProgress,
                                    facts = { viewModel.vod.index.all() },
                                    hasTmdbKey = tmdbKey.isNotBlank(),
                                    filters = vodFilters,
                                    onFiltersChange = { kind, filter -> vodFilters = vodFilters + (kind to filter) },
                                    onOpenTitle = { title -> currentScreen = CurrentScreen.VodDetail(title.key) },
                                    onOpenSearch = { currentScreen = CurrentScreen.VodSearch },
                                    onCreateCollection = { name -> vodStore { createCollection(name) } },
                                    onRenameCollection = { id, name -> vodStore { renameCollection(id, name) } },
                                    onDeleteCollection = { id -> vodStore { deleteCollection(id) } },
                                    onRefresh = { viewModel.vod.refresh() },
                                    onBack = { currentScreen = CurrentScreen.Home },
                                    findPoster = { title -> viewModel.vod.posterFor(title) },
                                    hiddenRows = vodDisplay.hiddenRows,
                                    actions = vodActions,
                                    initialRows = viewModel.vod.lastRows,
                                    matcherFor = { titles, ranked -> viewModel.vod.matcher(titles, ranked) },
                                    onRows = { built -> viewModel.vod.lastRows = built },
                                    newCategories = vodLibrary.newCategories.entries.firstOrNull()?.let { (id, added) ->
                                        enabledConfigs.firstOrNull { it.id == id }?.let { it.name to added.size }
                                    },
                                    onChooseNewCategories = {
                                        vodLibrary.newCategories.keys.firstOrNull()?.let { currentScreen = CurrentScreen.VodCategories(it, returnTo = CurrentScreen.Vod) }
                                    },
                                    onDismissNewCategories = {
                                        vodLibrary.newCategories.keys.firstOrNull()?.let { viewModel.vod.dismissNewCategories(it) }
                                    }
                                )
                                }
                            }
                        }
                        is CurrentScreen.VodCategories -> {
                            val config = enabledConfigs.firstOrNull { it.id == screen.configId }
                            val selections by configRepository.vodSelections.collectAsState()
                            val newCategories by viewModel.vod.library.collectAsState()
                            if (config == null) {
                                LaunchedEffect(Unit) { currentScreen = screen.returnTo }
                            } else {
                                VodCategoryPicker(
                                    source = config,
                                    current = selections[config.id],
                                    suggested = newCategories.newCategories[config.id].orEmpty().mapTo(HashSet()) { it.key },
                                    loadCategories = { viewModel.vod.categories(config) },
                                    onChoose = { selection ->
                                        viewModel.vod.choose(config, selection)
                                        currentScreen = screen.returnTo
                                    },
                                    onBack = { currentScreen = screen.returnTo }
                                )
                            }
                        }
                        is CurrentScreen.VodSearch -> {
                            val vodLibrary by viewModel.vod.library.collectAsState()
                            val tmdbKey by configRepository.tmdbKey.collectAsState()
                            saveableStateHolder.SaveableStateProvider("vod-search") {
                                CompositionLocalProvider(LocalPosterWidth provides vodDisplay.posterSize.widthDp.dp) {
                                VodSearchScreen(
                                    titles = vodLibrary.titles,
                                    facts = { viewModel.vod.index.all() },
                                    tmdbKey = tmdbKey.takeIf { it.isNotBlank() },
                                    onOpenTitle = { title -> currentScreen = CurrentScreen.VodDetail(title.key, returnTo = CurrentScreen.VodSearch) },
                                    onBack = {
                                        saveableStateHolder.removeState("vod-search")
                                        currentScreen = CurrentScreen.Vod
                                    },
                                    findPoster = { title -> viewModel.vod.posterFor(title) }
                                )
                                }
                            }
                        }
                        is CurrentScreen.VodDetail -> {
                            val vodLibrary by viewModel.vod.library.collectAsState()
                            val vodProgress by configRepository.vodStore.progress.collectAsState()
                            val vodMyList by configRepository.vodStore.myList.collectAsState()
                            val vodCollections by configRepository.vodStore.collections.collectAsState()
                            val title = vodLibrary.byKey[screen.titleKey]
                            if (title == null) {
                                LaunchedEffect(Unit) { currentScreen = CurrentScreen.Vod }
                            } else {
                                var details by remember(screen.titleKey) { mutableStateOf(viewModel.vod.cachedDetails(screen.titleKey)) }
                                var failed by remember(screen.titleKey) { mutableStateOf(false) }
                                var attempt by remember(screen.titleKey) { mutableIntStateOf(0) }
                                LaunchedEffect(screen.titleKey, attempt) {
                                    if (details != null) return@LaunchedEffect
                                    failed = false
                                    try {
                                        details = viewModel.vod.details(title)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        failed = true
                                    }
                                }
                                VodDetailScreen(
                                    title = title,
                                    details = details,
                                    loadFailed = failed,
                                    progress = vodProgress,
                                    inMyList = title.key in vodMyList,
                                    collections = vodCollections,
                                    onPlay = { request -> currentScreen = CurrentScreen.VodPlayer(request, returnTo = screen) },
                                    onToggleMyList = { vodStore { toggleMyList(title.key) } },
                                    onToggleCollection = { id -> vodStore { toggleInCollection(id, title.key) } },
                                    onCreateCollection = { name ->
                                        vodStore {
                                            val id = createCollection(name)
                                            toggleInCollection(id, title.key)
                                        }
                                    },
                                    onRetry = { attempt++ },
                                    onBack = { currentScreen = screen.returnTo },
                                    onSetEpisodeWatched = { key, watched ->
                                        vodStore { if (watched) markWatched(key, title.key) else clearProgress(key) }
                                    },
                                    subtitles = vodSubtitles
                                )
                            }
                        }
                        is CurrentScreen.VodPlayer -> {
                            val request = screen.request
                            // Replay of a TV program: no position, track choice nor subtitles kept for it
                            val isReplay = request.titleKey.startsWith(REPLAY_KEY_PREFIX)
                            val vodTrackPrefs by configRepository.vodStore.trackPrefs.collectAsState()
                            // Next episode from the sheet's details (with the positions known when it starts)
                            val next = remember(request) {
                                nextPlayRequest(request, viewModel.vod.cachedDetails(request.titleKey), configRepository.vodStore.progress.value)
                            }
                            VodPlayerScreen(
                                request = request,
                                playerManager = playerManager,
                                streamAuths = streamAuths,
                                userAgent = configsById[request.sourceId]?.customUserAgent,
                                onProgress = { played, position, duration ->
                                    if (isReplay) return@VodPlayerScreen
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        configRepository.vodStore.saveProgress(
                                            key = VodRows.progressKey(played.titleKey, played.episodeKey),
                                            titleKey = played.titleKey,
                                            positionMs = position,
                                            durationMs = duration
                                        )
                                    }
                                },
                                onBack = { currentScreen = screen.returnTo },
                                next = next,
                                onPlayNext = { upcoming -> currentScreen = CurrentScreen.VodPlayer(upcoming, screen.returnTo) },
                                trackPrefs = if (isReplay) null else vodTrackPrefs[request.titleKey],
                                onTrackPrefsChange = { prefs -> if (!isReplay) vodStore { saveTrackPrefs(request.titleKey, prefs.copy(introEndMs = trackPrefs.value[request.titleKey]?.introEndMs)) } },
                                onIntroLearned = { end ->
                                    if (!isReplay) vodStore { saveTrackPrefs(request.titleKey, (trackPrefs.value[request.titleKey] ?: VodTrackPrefs()).copy(introEndMs = end)) }
                                },
                                pictureFormat = pictureFormat,
                                onPictureFormatChange = { configRepository.setPictureFormat(it) },
                                subtitles = vodSubtitles.takeIf { !isReplay }
                            )
                        }
                        is CurrentScreen.Player -> {
                            // Up-to-date version of the channel (current EPG program, chosen stream)
                            val liveChannel = displayedChannels.firstOrNull { it.id == screen.channel.id } ?: screen.channel
                            val schedules by viewModel.schedules.collectAsState()
                            LaunchedEffect(liveChannel.id) {
                                lastWatchedId = liveChannel.id
                                viewModel.guideStore.addRecent(liveChannel.id)
                            }
                            val channelConfig = configsById[liveChannel.sourceId]
                            PlayerScreen(
                                channel = liveChannel,
                                channels = visibleChannels,
                                playerManager = playerManager,
                                categoryOrder = categoryOrder,
                                streamAuths = streamAuths,
                                userAgent = channelConfig?.customUserAgent,
                                loadStreams = { viewModel.loadStreams(liveChannel) },
                                resolveStreams = { ch -> viewModel.automaticStreamUrls(ch) },
                                onSelectStream = { stream -> viewModel.selectStream(liveChannel, stream) },
                                onSelectChannel = { selected -> currentScreen = CurrentScreen.Player(selected, screen.returnTo) },
                                automaticStreamDescription = automaticStreamDescription(liveChannel),
                                onBack = {
                                    currentScreen = screen.returnTo
                                },
                                onNextChannel = { zap(liveChannel, +1, screen.returnTo) },
                                onPreviousChannel = { zap(liveChannel, -1, screen.returnTo) },
                                pictureFormat = pictureFormat,
                                onPictureFormatChange = { configRepository.setPictureFormat(it) },
                                onAudioLanguageChange = { configRepository.setAudioLanguage(it) },
                                programsOf = { ch -> GuideTimeline.programsOf(ch, schedules) },
                                favorites = favoriteChannels,
                                recents = recentChannels,
                                guideActions = guideActions(returnTo = screen)
                            )
                        }
                    }

                    dueReminder?.let { reminder ->
                        val channel = unhiddenChannels.firstOrNull { it.id == reminder.channelId }
                        ReminderDialog(
                            reminder = reminder,
                            channel = channel,
                            onWatch = {
                                if (channel != null) {
                                    val returnTo = (currentScreen as? CurrentScreen.Player)?.returnTo ?: CurrentScreen.Home
                                    currentScreen = CurrentScreen.Player(channel, returnTo)
                                }
                            },
                            onDismiss = { dueReminder = null }
                        )
                    }

                    if (showGuide) {
                        QuickGuide(onClose = {
                            showGuide = false
                            configRepository.setGuideSeen()
                        })
                    }

                    AnimatedVisibility(
                        visible = showWelcome,
                        enter = EnterTransition.None,
                        exit = fadeOut(tween(durationMillis = 700))
                    ) {
                        // A tap skips it, like OK (and the hidden home screen does not get it)
                        WelcomeScreen(name = userName, modifier = Modifier.touchTap { showWelcome = false })
                    }
                }
            }
        }
    }

    /** Downloads the artwork of the favorite tiles so they are ready when the welcome ends. */
    private suspend fun preloadPosters(context: Context, channels: List<Channel>) = coroutineScope {
        channels.map { channel ->
            async {
                val program = channel.currentProgram ?: return@async
                val url = program.posterUrl ?: ProgramPosterRepository.getPosterUrl(program.title) ?: return@async
                context.imageLoader.execute(ImageRequest.Builder(context).data(url).build())
            }
        }.awaitAll()
    }

    // Back from another app (voice search, installer): the bars as the screen wants them
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Device.isTouch) showSystemBars(navigation = !playerShown)
    }

    /** Phone / tablet: no status bar, as on the TV; the navigation bar comes back with a swipe when hidden. */
    private fun showSystemBars(navigation: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        if (navigation) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onStop() {
        super.onStop()
        // App in the background (Home key, standby): no preview left playing on the home screen
        playerManager.stopPreview()
    }

    // The system lacks memory: the movies and series library (tens of MB) goes first, read again from the
    // device at the next opening; never while it is on screen
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !vodShown) viewModel.vod.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerManager.release()
    }
}
