package io.github.sim7b.tele

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
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.sim7b.tele.data.model.Categories
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.remote.ProgramPosterRepository
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.playback.TvPlayerManager
import io.github.sim7b.tele.ui.components.DISPATCHARR_AUTOMATIC
import io.github.sim7b.tele.ui.components.PreviewVideo
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
import io.github.sim7b.tele.ui.vod.VodPlayerScreen
import io.github.sim7b.tele.ui.vod.VodSearchScreen
import io.github.sim7b.tele.ui.vod.VodTitleActions
import io.github.sim7b.tele.ui.vod.LocalPosterWidth
import androidx.compose.ui.unit.dp
import io.github.sim7b.tele.data.storage.VodStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Displayed screen (simple navigation, without a navigation library). */
sealed class CurrentScreen {
    object Home : CurrentScreen()
    object Settings : CurrentScreen()
    data class Search(val startWithVoice: Boolean) : CurrentScreen()

    /** Playback; Back returns to [returnTo] (the home screen, or the search the channel was opened from). */
    data class Player(val channel: Channel, val returnTo: CurrentScreen = Home) : CurrentScreen()

    /** Movies and series: library, sheet of a title, playback of a movie or an episode. */
    object Vod : CurrentScreen()
    object VodSearch : CurrentScreen()
    /** Sheet of a title; Back returns to [returnTo] (the library, or the search it was opened from). */
    data class VodDetail(val titleKey: String, val returnTo: CurrentScreen = Vod) : CurrentScreen()
    data class VodPlayer(val request: VodPlayRequest, val returnTo: CurrentScreen) : CurrentScreen()
}

private const val WELCOME_MIN_MS = 1_800L
private const val WELCOME_MAX_MS = 15_000L
private const val VOD_RELEASE_DELAY_MS = 5 * 60 * 1000L

/**
 * The app's single activity: hosts the Compose UI (home, player, settings),
 * the startup "Good morning/Good evening" welcome screen and the ExoPlayer instance shared across channels.
 */
class MainActivity : ComponentActivity() {

    private lateinit var playerManager: TvPlayerManager
    private val viewModel: MainViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerManager = TvPlayerManager(this)
        val configRepository = viewModel.configRepository

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
            val context = LocalContext.current

            var currentScreen by remember { mutableStateOf<CurrentScreen>(CurrentScreen.Home) }
            // Filters of the Movies and Series tabs, kept while browsing sheets; the defaults of the settings first
            val vodDisplay by configRepository.vodDisplay.collectAsState()
            var vodFilters by remember(vodDisplay.defaults) { mutableStateOf(vodDisplay.defaults) }
            LaunchedEffect(vodDisplay.hideTheater) { viewModel.vod.reapply() }
            // Collections are saved off the main thread
            fun vodStore(change: VodStore.() -> Unit) {
                lifecycleScope.launch(Dispatchers.IO) { configRepository.vodStore.change() }
            }
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
            // The movies and series library is freed after a while on the live TV screens
            val inVod = currentScreen.let {
                it is CurrentScreen.Vod || it is CurrentScreen.VodSearch || it is CurrentScreen.VodDetail || it is CurrentScreen.VodPlayer
            }
            LaunchedEffect(inVod) {
                if (!inVod) {
                    delay(VOD_RELEASE_DELAY_MS)
                    viewModel.vod.release()
                }
            }
            // Back on the home screen: refresh the slots right away (leaving the player frees one)
            LaunchedEffect(currentScreen is CurrentScreen.Home) {
                if (currentScreen is CurrentScreen.Home) viewModel.refreshStreamSlots()
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
                previewJob = previewScope.launch {
                    val url = viewModel.automaticStreamUrls(channel).firstOrNull() ?: channel.activeStreamUrl
                    playerManager.preview(url, configsById[channel.sourceId]?.customUserAgent, streamAuths)
                }
            }
            // Settings and search: no preview behind them (the player takes it over, see playStream)
            LaunchedEffect(currentScreen) {
                if (currentScreen is CurrentScreen.Settings || currentScreen is CurrentScreen.Search) previewChannel(null)
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
                                    previewDelayMs = previewDelay.millis.takeIf { !showWelcome && !showGuide },
                                    onPreviewChannel = { channel -> previewChannel(channel) },
                                    previewSurface = { PreviewVideo(playerManager) }
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
                            saveableStateHolder.SaveableStateProvider("vod") {
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
                                    actions = vodActions
                                )
                                }
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
                                    }
                                )
                            }
                        }
                        is CurrentScreen.VodPlayer -> {
                            val request = screen.request
                            VodPlayerScreen(
                                request = request,
                                playerManager = playerManager,
                                streamAuths = streamAuths,
                                userAgent = configsById[request.sourceId]?.customUserAgent,
                                onProgress = { position, duration ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        configRepository.vodStore.saveProgress(
                                            key = VodRows.progressKey(request.titleKey, request.episodeKey),
                                            titleKey = request.titleKey,
                                            positionMs = position,
                                            durationMs = duration
                                        )
                                    }
                                },
                                onBack = { currentScreen = screen.returnTo }
                            )
                        }
                        is CurrentScreen.Player -> {
                            // Up-to-date version of the channel (current EPG program, chosen stream)
                            val liveChannel = displayedChannels.firstOrNull { it.id == screen.channel.id } ?: screen.channel
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
                                onAudioLanguageChange = { configRepository.setAudioLanguage(it) }
                            )
                        }
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
                        WelcomeScreen(name = userName)
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

    override fun onStop() {
        super.onStop()
        // App in the background (Home key, standby): no preview left playing on the home screen
        playerManager.stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerManager.release()
    }
}
