package io.github.sim7b.tele

import android.content.ComponentCallbacks2
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
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
import io.github.sim7b.tele.data.storage.ReminderAlarms
import io.github.sim7b.tele.data.sport.SportEvent
import io.github.sim7b.tele.data.sport.SportEvents
import io.github.sim7b.tele.data.trakt.Trakt
import io.github.sim7b.tele.data.trakt.TraktItem
import io.github.sim7b.tele.data.trakt.TraktMatch
import io.github.sim7b.tele.data.trakt.TraktWatched
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.model.HomeStyle
import io.github.sim7b.tele.ui.screens.ImmersiveHomeScreen
import io.github.sim7b.tele.data.record.Recording
import io.github.sim7b.tele.data.record.RecordingRules
import io.github.sim7b.tele.data.record.RecordingScheduler
import io.github.sim7b.tele.data.record.RecordingService
import io.github.sim7b.tele.data.record.RecordingState
import io.github.sim7b.tele.data.record.RecordingStore
import io.github.sim7b.tele.ui.screens.RecordingsScreen
import io.github.sim7b.tele.ui.screens.ExactAlarmsDialog
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.sim7b.tele.ui.components.ScreenNav
import io.github.sim7b.tele.ui.components.LocalImmersive
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
import io.github.sim7b.tele.ui.vod.LocalRatingOf
import io.github.sim7b.tele.data.vod.VodBrowse
import io.github.sim7b.tele.data.vod.VodTitle
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
import io.github.sim7b.tele.update.release
import io.github.sim7b.tele.update.UpdateViewModel
import io.github.sim7b.tele.data.update.UpdateFiles
import io.github.sim7b.tele.ui.components.UpdateReadyDialog
import io.github.sim7b.tele.ui.components.PinDialog
import io.github.sim7b.tele.ui.components.withInset
import io.github.sim7b.tele.playback.InsetPlayer
import io.github.sim7b.tele.playback.DisplayModes
import io.github.sim7b.tele.data.search.AppIntents
import io.github.sim7b.tele.data.search.AppRequest
import io.github.sim7b.tele.data.vod.WatchNext
import android.app.SearchManager
import kotlinx.coroutines.flow.MutableStateFlow
import android.os.Build
import androidx.compose.ui.platform.LocalResources
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
import kotlinx.coroutines.withContext

/** Displayed screen (simple navigation, without a navigation library). */
sealed class CurrentScreen {
    object Home : CurrentScreen()
    /** Settings; [vod]: opened from the movies and series, on their section (Back returns to [returnTo]). */
    data class Settings(val vod: Boolean = false, val returnTo: CurrentScreen = Home) : CurrentScreen()
    // [query]: asked by voice from outside the app (the Assistant)
    data class Search(val startWithVoice: Boolean, val query: String = "") : CurrentScreen()

    /** Guide grid of the channels, opened from the home screen. */
    object Guide : CurrentScreen()

    /** Programs recorded on the device and the ones planned. */
    object Recordings : CurrentScreen()

    /** Playback; Back returns to [returnTo] (the home screen, or the search the channel was opened from). */
    data class Player(val channel: Channel, val returnTo: CurrentScreen = Home) : CurrentScreen()

    /** Movies and series: library, sheet of a title, playback of a movie or an episode. */
    object Vod : CurrentScreen()
    object VodSearch : CurrentScreen()
    /** Sheet of a title; Back returns to [returnTo] (the library, or the search it was opened from). */
    data class VodDetail(val titleKey: String, val returnTo: CurrentScreen = Vod) : CurrentScreen()
    /** Choice of the categories of movies and series loaded from a source (from the settings). */
    data class VodCategories(val configId: String, val returnTo: CurrentScreen = Settings()) : CurrentScreen()
    data class VodPlayer(val request: VodPlayRequest, val returnTo: CurrentScreen) : CurrentScreen()
}

private const val WELCOME_MIN_MS = 1_800L
private const val HOME_RESUME_SIZE = 12
// Followed sport looked up again in the guide (matches start, end)
private const val SPORT_REFRESH_MS = 5 * 60_000L
// Trakt watchlist read again
private const val TRAKT_REFRESH_MS = 30 * 60_000L
private const val FRAME_RATE_SETTLE_MS = 1_500L
private const val WATCH_NEXT_SIZE = 10
private const val WATCH_NEXT_DELAY_MS = 3_000L
private const val WELCOME_MAX_MS = 15_000L
// After the live TV is loaded: the movies and series library is prepared without slowing the startup
private const val VOD_PRELOAD_DELAY_MS = 15_000L
// Replays of TV programs go through the movie player, with this prefix on their key
private const val REPLAY_KEY_PREFIX = "replay:"
// After the replay one: a recording is played the same way (no position, track choice nor subtitles kept)
private const val RECORDING_KEY_PREFIX = "recording:"
private const val REMINDER_CHECK_MS = 15_000L

/**
 * The app's single activity: hosts the Compose UI (home, player, settings),
 * the startup "Good morning/Good evening" welcome screen and the ExoPlayer instance shared across channels.
 */
class MainActivity : ComponentActivity() {

    private lateinit var playerManager: TvPlayerManager
    // Second channel in a corner of the player
    private val insetPlayer by lazy { InsetPlayer(applicationContext) }
    // Screen mode before one was chosen for a video (0: none chosen)
    private var usualDisplayMode = 0
    private val viewModel: MainViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()
    // Phone / tablet: a player is shown (system bars hidden)
    private var playerShown = false
    // A movies and series screen is displayed: its library must not be freed
    private var vodShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
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
            // Movies and series locked by the parental code: asked to open them, left out of the search and the home
            val vodHidden by remember { configRepository.parental.vodHiddenFlow() }.collectAsState(initial = configRepository.parental.vodHiddenNow())
            // Action waiting for the parental code (opening the movies and series)
            var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
            // Movies or series section opened from the live TV home (again on each opening)
            var vodSection by rememberSaveable { mutableStateOf(VodKind.MOVIE) }
            var vodSectionToken by remember { mutableIntStateOf(0) }
            var pinError by remember { mutableStateOf<String?>(null) }
            // Channel shown in a corner of the player (muted)
            var insetChannel by remember { mutableStateOf<Channel?>(null) }
            val pictureFormat by configRepository.pictureFormat.collectAsState()
            val categoryOrder by configRepository.categoryOrder.collectAsState()
            // Audio language chosen in the playback options: preferred for every channel
            val audioLanguage by configRepository.audioLanguage.collectAsState()
            LaunchedEffect(audioLanguage) { playerManager.setPreferredAudioLanguage(audioLanguage) }
            val audioLeveling by configRepository.audioLeveling.collectAsState()
            LaunchedEffect(audioLeveling) { playerManager.setAudioLeveling(audioLeveling) }
            // Program artwork: the large TMDB backdrops need the TMDB key
            val artworkTmdbKey by configRepository.tmdbKey.collectAsState()
            LaunchedEffect(artworkTmdbKey) { ProgramPosterRepository.tmdbKey = artworkTmdbKey.takeIf { it.isNotBlank() } }
            val audioPassthrough by configRepository.audioPassthrough.collectAsState()
            LaunchedEffect(audioPassthrough) { playerManager.audioPassthrough = audioPassthrough }
            val timeshiftOn by configRepository.timeshift.collectAsState()
            LaunchedEffect(timeshiftOn) { playerManager.timeshiftEnabled = timeshiftOn }

            val userName by viewModel.userName.collectAsState()
            // The guide grid is offered once a guide is loaded (only this flag: the programs are read in its screen)
            val hasGuide by remember { viewModel.schedules.map { schedules -> schedules.values.any { it.isNotEmpty() } }.distinctUntilChanged() }
                .collectAsState(initial = false)
            val context = LocalContext.current

            var currentScreen by remember { mutableStateOf<CurrentScreen>(CurrentScreen.Home) }
            fun openVodSection(kind: VodKind) {
                val open = {
                    vodSection = kind
                    vodSectionToken++
                    currentScreen = CurrentScreen.Vod
                }
                if (vodHidden) pinFor = open else open()
            }
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
                    if (currentScreen !is CurrentScreen.Home && currentScreen !is CurrentScreen.Guide) return@launch
                    playerManager.preview(url, configsById[channel.sourceId]?.customUserAgent, streamAuths)
                }
            }
            // Settings and search: no preview behind them (the player takes it over, see playStream); the home and the
            // guide start their own (the one of the other screen goes); any other screen cancels a preview still
            // choosing its stream, so it cannot replace the channel opened
            var previewScreen by remember { mutableStateOf<CurrentScreen?>(null) }
            LaunchedEffect(currentScreen) {
                val screen = currentScreen
                when {
                    screen is CurrentScreen.Settings || screen is CurrentScreen.Search -> previewChannel(null)
                    screen is CurrentScreen.Home || screen is CurrentScreen.Guide -> if (previewScreen != null && previewScreen != screen) previewChannel(null)
                    else -> previewJob?.cancel()
                }
                previewScreen = screen
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
            // Immersive home: every favorite (the usual home fills 8 tiles), the followed sport of the guide
            val homeLayout by configRepository.homeLayout.collectAsState()
            val allFavorites = remember(favoriteIds, unhiddenChannels) {
                val byId = HashMap<String, Channel>()
                unhiddenChannels.forEach { channel -> channel.allIds.forEach { byId.putIfAbsent(it, channel) } }
                favoriteIds.mapNotNull { byId[it] }.distinctBy { it.id }
            }
            val followedSport by configRepository.followedSport.collectAsState()
            val sportSchedules by viewModel.schedules.collectAsState()
            val sportEvents by produceState(emptyList<SportEvent>(), followedSport, unhiddenChannels, sportSchedules) {
                while (true) {
                    value = withContext(Dispatchers.Default) {
                        val now = System.currentTimeMillis()
                        SportEvents.find(followedSport, unhiddenChannels, sportSchedules, now, replayable = { channel, program -> Catchup.isReplayable(channel, program, now) })
                    }
                    delay(SPORT_REFRESH_MS)
                }
            }
            // Trakt: the watchlist (immersive home), read again every half hour; the access refreshed when needed
            val traktApp by configRepository.traktApp.collectAsState()
            val traktSession by configRepository.traktSession.collectAsState()
            val traktLibrary by viewModel.vod.library.collectAsState()
            val traktItems by produceState(emptyList<TraktItem>(), traktApp, traktSession, vodEnabled) {
                val session = traktSession
                if (session == null || !traktApp.isComplete || !vodEnabled) {
                    value = emptyList()
                    return@produceState
                }
                while (true) {
                    try {
                        val fresh = Trakt.fresh(traktApp, session)
                        if (fresh != session) {
                            // Saved, this runs again with it (none: the account is no longer linked)
                            configRepository.setTraktSession(fresh)
                            return@produceState
                        }
                        value = Trakt.watchlist(traktApp, fresh)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Trakt", "Watchlist unavailable: ${e.javaClass.simpleName}")
                    }
                    delay(TRAKT_REFRESH_MS)
                }
            }
            val traktWatchlist = remember(traktItems, traktLibrary.titles) { TraktMatch.titles(traktItems, traktLibrary.titles) }
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
            // Recordings on the device: planned from the guide or the player, written by RecordingService
            val recordings by recordingStore.recordings.collectAsState()
            LaunchedEffect(recordings) {
                withContext(Dispatchers.IO) { RecordingScheduler.schedule(applicationContext, recordings) }
                // One on air not being written (asked for now, or its alarm refused while the app was closed)
                if (RecordingRules.due(recordings, System.currentTimeMillis()).any { it.state == RecordingState.SCHEDULED }) {
                    RecordingService.start(applicationContext)
                }
            }
            var askExactAlarms by remember { mutableStateOf(false) }
            val recordingIds = remember(recordings) { recordings.mapTo(HashSet()) { it.id } }
            val reminders by viewModel.guideStore.reminders.collectAsState()
            // The alarm of the next one, for when the app is not on screen
            LaunchedEffect(reminders) { withContext(Dispatchers.IO) { ReminderAlarms.schedule(applicationContext, reminders) } }
            fun guideActions(returnTo: CurrentScreen) = GuideActions(
                reminders = reminders,
                toggleReminder = { channel, program ->
                    if (Reminders.pending(reminders, System.currentTimeMillis()).none { Reminders.matches(it, channel.id, program.startMillis) }) askNotificationsIfNeeded()
                    viewModel.guideStore.toggleReminder(Reminder(channel.id, channel.name, program.title, program.startMillis, program.endMillis))
                },
                replay = { channel, program ->
                    // Replay of the program (Xtream timeshift, M3U catch-up), played like a movie (seek, pause)
                    Catchup.replayUrl(channel, program.startMillis, program.endMillis, System.currentTimeMillis())?.let { url ->
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
                createList = { name, channel -> configRepository.createChannelList(name, channel.id) },
                recordings = recordingIds,
                toggleRecording = { channel, program ->
                    val recording = Recording(
                        id = RecordingRules.idOf(channel.id, program.startMillis),
                        channelId = channel.id,
                        channelName = channel.name,
                        sourceId = channel.sourceId,
                        title = program.title,
                        startMillis = program.startMillis,
                        endMillis = program.endMillis,
                        url = channel.selectedStreamUrl ?: channel.streamUrl,
                        imageUrl = program.posterUrl ?: channel.logoUrl,
                        description = program.description
                    )
                    val adding = recordings.none { it.id == recording.id }
                    lifecycleScope.launch(Dispatchers.IO) { recordingStore.toggle(recording) }
                    // Planned for later: an exact alarm is needed to start on time while the app is closed
                    if (adding && program.startMillis > System.currentTimeMillis() && !RecordingScheduler.exactAllowed(applicationContext)) askExactAlarms = true
                    if (adding) askNotificationsIfNeeded()
                }
            )
            // A program with a reminder starts: shown once, on any screen (after the welcome)
            var dueReminder by remember { mutableStateOf<Reminder?>(null) }
            LaunchedEffect(reminders, showWelcome) {
                if (showWelcome) return@LaunchedEffect
                while (dueReminder == null) {
                    val due = Reminders.due(reminders, System.currentTimeMillis())
                    if (due != null) {
                        dueReminder = due
                        ReminderAlarms.cancel(applicationContext, due)
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
                // Rating on the posters: TMDB vote when known, else the provider one
                CompositionLocalProvider(LocalRatingOf provides remember { { title: VodTitle -> VodBrowse.rating(title, viewModel.vod.index.facts(title.key)) } }) {
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
                    // Immersive home chosen (with a remote): the guide, the movies and the series in the same style,
                    // the sections in a glass bar at the top of each
                    val screenNav = if (homeLayout.style == HomeStyle.IMMERSIVE && !Device.isTouch) {
                        ScreenNav(
                            onHome = { currentScreen = CurrentScreen.Home },
                            onGuide = if (hasGuide) ({ currentScreen = CurrentScreen.Guide }) else null,
                            onVod = if (vodEnabled) ({ kind -> openVodSection(kind) }) else null,
                            onRecordings = if (recordings.isNotEmpty()) ({ currentScreen = CurrentScreen.Recordings }) else null,
                            onSearch = { currentScreen = CurrentScreen.Search(startWithVoice = false) },
                            onSettings = { currentScreen = CurrentScreen.Settings() },
                            updateAvailable = updateState.release != null && updateState !is UpdateState.Failed
                        )
                    } else {
                        null
                    }
                    // Immersive style chosen: the shared pieces (chips, titles of rows, buttons) in glass on every screen
                    CompositionLocalProvider(LocalImmersive provides (screenNav != null)) {
                    when (val screen = currentScreen) {
                        is CurrentScreen.Home -> {
                            // Movies and series started, above the favorites (when the module is enabled)
                            val homeProgress by configRepository.vodStore.progress.collectAsState()
                            val homeLibrary by viewModel.vod.library.collectAsState()
                            val homeMyList by configRepository.vodStore.myList.collectAsState()
                            val homeCollections by configRepository.vodStore.collections.collectAsState()
                            val homeResume = remember(homeProgress, homeLibrary.byKey, vodEnabled, vodHidden) {
                                if (vodEnabled && !vodHidden) VodRows.continuing(homeLibrary.byKey, homeProgress, limit = HOME_RESUME_SIZE) else emptyList()
                            }
                            // Immersive home (chosen in the settings): with a remote and channels only, the usual home
                            // shows the loading, the errors and the first steps
                            val immersive = homeLayout.style == HomeStyle.IMMERSIVE && !Device.isTouch && visibleChannels.isNotEmpty()
                            if (immersive) saveableStateHolder.SaveableStateProvider("home-immersive") {
                                val homeActions = guideActions(returnTo = CurrentScreen.Home)
                                ImmersiveHomeScreen(
                                    channels = visibleChannels,
                                    favorites = allFavorites,
                                    rows = homeLayout.rows,
                                    favoriteIds = favoriteIds,
                                    categoryOrder = categoryOrder,
                                    channelLists = channelListViews,
                                    savedLists = savedChannelLists,
                                    onToggleInList = ::toggleInList,
                                    onCreateList = { name, channel -> configRepository.createChannelList(name, channel.id) },
                                    resume = homeResume,
                                    onOpenResume = { title -> currentScreen = CurrentScreen.VodDetail(title.key, returnTo = CurrentScreen.Home) },
                                    resumeActions = vodActions,
                                    vodMyList = homeMyList,
                                    vodCollections = homeCollections,
                                    sport = sportEvents,
                                    onOpenSport = { event ->
                                        val now = System.currentTimeMillis()
                                        when {
                                            event.program.endMillis <= now -> homeActions.replay?.invoke(event.channel, event.program)
                                            event.program.startMillis > now -> homeActions.toggleReminder(event.channel, event.program)
                                            else -> currentScreen = CurrentScreen.Player(event.channel)
                                        }
                                    },
                                    watchlist = if (vodEnabled && !vodHidden) traktWatchlist else emptyList(),
                                    guideActions = homeActions,
                                    onOpenTitle = { title -> currentScreen = CurrentScreen.VodDetail(title.key, returnTo = CurrentScreen.Home) },
                                    updateAvailable = updateState.release != null && updateState !is UpdateState.Failed,
                                    onChannelSelected = { channel -> currentScreen = CurrentScreen.Player(channel) },
                                    onToggleFavorite = { chId -> toggleFavorite(chId) },
                                    onHideChannel = { chId -> hideChannel(chId) },
                                    loadStreams = { ch -> viewModel.loadStreams(ch) },
                                    onSelectStream = { ch, stream ->
                                        viewModel.selectStream(ch, stream)
                                        currentScreen = CurrentScreen.Player(ch)
                                    },
                                    automaticStreamDescription = automaticStreamDescription,
                                    onOpenSettings = { currentScreen = CurrentScreen.Settings() },
                                    onOpenSearch = { voice -> currentScreen = CurrentScreen.Search(startWithVoice = voice) },
                                    onOpenVod = if (vodEnabled) ({ kind -> openVodSection(kind) }) else null,
                                    onOpenGuide = if (hasGuide) ({ currentScreen = CurrentScreen.Guide }) else null,
                                    onOpenRecordings = if (recordings.isNotEmpty()) ({ currentScreen = CurrentScreen.Recordings }) else null,
                                    previewDelayMs = previewDelay.millis.takeIf { !showWelcome && !showGuide },
                                    onPreviewChannel = { channel -> previewChannel(channel) },
                                    previewSurface = { PreviewVideo(playerManager) }
                                )
                            } else saveableStateHolder.SaveableStateProvider("home") {
                                HomeScreen(
                                    channels = visibleChannels,
                                    favorites = favoriteChannels,
                                    channelLists = channelListViews,
                                    savedLists = savedChannelLists,
                                    onToggleInList = ::toggleInList,
                                    onCreateList = { name, channel -> configRepository.createChannelList(name, channel.id) },
                                    resume = homeResume,
                                    onOpenResume = { title -> currentScreen = CurrentScreen.VodDetail(title.key, returnTo = CurrentScreen.Home) },
                                    resumeActions = vodActions,
                                    vodMyList = homeMyList,
                                    vodCollections = homeCollections,
                                    hasSource = enabledConfigs.isNotEmpty(),
                                    favoriteIds = favoriteIds,
                                    isLoading = isLoadingChannels,
                                    loadError = loadError,
                                    streamSlots = streamSlots,
                                    updateAvailable = updateState.release != null && updateState !is UpdateState.Failed,
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
                                        currentScreen = CurrentScreen.Settings()
                                    },
                                    onOpenSearch = { voice ->
                                        currentScreen = CurrentScreen.Search(startWithVoice = voice)
                                    },
                                    onRefresh = {
                                        viewModel.refresh()
                                    },
                                    onOpenVod = if (vodEnabled) ({ kind -> openVodSection(kind) }) else null,
                                    onOpenGuide = if (hasGuide) ({ currentScreen = CurrentScreen.Guide }) else null,
                                    onOpenRecordings = if (recordings.isNotEmpty()) ({ currentScreen = CurrentScreen.Recordings }) else null,
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
                                onChooseVodCategories = { config -> currentScreen = CurrentScreen.VodCategories(config.id, returnTo = screen) },
                                startOnVod = screen.vod,
                                installedVersion = updateViewModel.installedVersion,
                                updatesSupported = updateViewModel.isSupported,
                                updateState = updateState,
                                onCheckUpdate = { updateViewModel.check() },
                                onInstallUpdate = { release -> updateViewModel.install(release) },
                                onBack = {
                                    // Movies and series turned off there: the live TV home
                                    currentScreen = if (screen.returnTo == CurrentScreen.Vod && !vodEnabled) CurrentScreen.Home else screen.returnTo
                                }
                            )
                        }
                        is CurrentScreen.Recordings -> {
                            RecordingsScreen(
                                recordings = recordings,
                                onPlay = { recording ->
                                    currentScreen = CurrentScreen.VodPlayer(
                                        VodPlayRequest(
                                            titleKey = "$REPLAY_KEY_PREFIX$RECORDING_KEY_PREFIX${recording.id}",
                                            episodeKey = null,
                                            label = recording.title,
                                            sourceId = recording.sourceId,
                                            url = recordingStore.file(recording).toUri().toString(),
                                            fallbacks = emptyList(),
                                            startMs = 0L,
                                            plot = recording.description,
                                            posterUrl = recording.imageUrl,
                                            imageUrl = recording.imageUrl
                                        ),
                                        returnTo = CurrentScreen.Recordings
                                    )
                                },
                                onDelete = { recording -> lifecycleScope.launch(Dispatchers.IO) { recordingStore.delete(recording.id) } },
                                onBack = { currentScreen = CurrentScreen.Home }
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
                                    initialQuery = screen.query,
                                    onChannelSelected = { channel ->
                                        currentScreen = CurrentScreen.Player(channel, returnTo = CurrentScreen.Search(startWithVoice = false))
                                    },
                                    onBack = {
                                        saveableStateHolder.removeState("search")
                                        currentScreen = CurrentScreen.Home
                                    },
                                    schedules = searchSchedules,
                                    actions = guideActions(returnTo = CurrentScreen.Search(startWithVoice = false)),
                                    vodTitles = if (vodEnabled && !vodHidden) searchLibrary.titles else emptyList(),
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
                                    sportChannels = remember(sportEvents) { sportEvents.map { it.channel }.distinctBy { it.id } },
                                    previewDelayMs = previewDelay.millis.takeIf { !Device.isTouch },
                                    onPreviewChannel = { channel -> previewChannel(channel) },
                                    previewSurface = { PreviewVideo(playerManager) },
                                    onWatch = { channel -> currentScreen = CurrentScreen.Player(channel, returnTo = CurrentScreen.Guide) },
                                    nav = screenNav,
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
                                    section = vodSection,
                                    sectionToken = vodSectionToken,
                                    nav = screenNav,
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
                                    onOpenSettings = { currentScreen = CurrentScreen.Settings(vod = true, returnTo = CurrentScreen.Vod) },
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
                                    subtitles = vodSubtitles,
                                    onPlayTrailer = { key -> openTrailer(this@MainActivity, key) }
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
                                    // Watched to the end: added once to the Trakt history (by its ids only)
                                    val session = traktSession
                                    if (session != null && traktApp.isComplete && duration > 0 && position >= duration * VodProgress.FINISHED_FRACTION &&
                                        (played.tmdbId != null || played.imdbId != null) && traktSent.add(played.itemKey)
                                    ) {
                                        lifecycleScope.launch {
                                            try {
                                                val fresh = Trakt.fresh(traktApp, session) ?: return@launch
                                                Trakt.addToHistory(
                                                    traktApp,
                                                    fresh,
                                                    TraktWatched(played.tmdbId, played.imdbId, played.season, played.episode, System.currentTimeMillis())
                                                )
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                Log.w("Trakt", "History not sent: ${e.javaClass.simpleName}")
                                            }
                                        }
                                    }
                                },
                                onBack = { currentScreen = screen.returnTo },
                                next = next,
                                onPlayNext = { upcoming -> currentScreen = CurrentScreen.VodPlayer(upcoming, screen.returnTo) },
                                onSwitchFile = { other -> currentScreen = CurrentScreen.VodPlayer(other, screen.returnTo) },
                                trackPrefs = if (isReplay) null else vodTrackPrefs[request.titleKey],
                                onTrackPrefsChange = { prefs -> if (!isReplay) vodStore { saveTrackPrefs(request.titleKey, prefs.copy(introEndMs = trackPrefs.value[request.titleKey]?.introEndMs, creditsMs = trackPrefs.value[request.titleKey]?.creditsMs)) } },
                                onIntroLearned = { end ->
                                    if (!isReplay) vodStore { saveTrackPrefs(request.titleKey, (trackPrefs.value[request.titleKey] ?: VodTrackPrefs()).copy(introEndMs = end)) }
                                },
                                onCreditsLearned = { credits ->
                                    if (!isReplay) vodStore { saveTrackPrefs(request.titleKey, (trackPrefs.value[request.titleKey] ?: VodTrackPrefs()).copy(creditsMs = credits)) }
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
                                guideActions = guideActions(returnTo = screen).withInset { picked -> if (picked.id != liveChannel.id) insetChannel = picked },
                                inset = insetChannel,
                                insetPlayer = insetPlayer,
                                onSwapInset = {
                                    insetChannel?.let { other ->
                                        insetChannel = liveChannel
                                        currentScreen = CurrentScreen.Player(other, screen.returnTo)
                                    }
                                },
                                onCloseInset = { insetChannel = null }
                            )
                            // The corner channel plays while it is set; leaving the player closes it
                            LaunchedEffect(insetChannel) {
                                val inset = insetChannel ?: return@LaunchedEffect insetPlayer.stop()
                                val url = viewModel.automaticStreamUrls(inset).firstOrNull() ?: inset.activeStreamUrl
                                insetPlayer.play(url, configsById[inset.sourceId]?.customUserAgent, streamAuths)
                            }
                            DisposableEffect(Unit) {
                                onDispose {
                                    insetChannel = null
                                    insetPlayer.stop()
                                }
                            }
                        }
                    }
                    }

                    if (askExactAlarms) {
                        ExactAlarmsDialog(
                            onOpen = {
                                askExactAlarms = false
                                openExactAlarmsSettings()
                            },
                            onDismiss = { askExactAlarms = false }
                        )
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

                    // Screen switched to the frame rate of the video while a channel, a movie or an episode plays;
                    // back to the usual mode afterwards. The rate must hold a moment: zapping does not make it blink
                    val matchFrameRate by configRepository.matchFrameRate.collectAsState()
                    val playbackFps = playerManager.streamInfo.collectAsState().value.fps
                    val playing = currentScreen is CurrentScreen.Player || currentScreen is CurrentScreen.VodPlayer
                    LaunchedEffect(playing && matchFrameRate, playbackFps) {
                        if (!(playing && matchFrameRate)) {
                            setDisplayMode(null)
                            return@LaunchedEffect
                        }
                        delay(FRAME_RATE_SETTLE_MS)
                        setDisplayMode(playbackFps)
                    }
                    // Opened from outside: a title of the Play next row, or a channel / title asked by voice. Locked
                    // channels are not in the lists; locked movies and series ask the parental code first
                    val appRequest by appRequests.collectAsState()
                    val requestLibrary by viewModel.vod.library.collectAsState()
                    LaunchedEffect(appRequest, unhiddenChannels.isEmpty(), requestLibrary.titles.isEmpty()) {
                        val request = appRequest ?: return@LaunchedEffect
                        fun openVod(action: () -> Unit) = if (vodHidden) pinFor = action else action()
                        when (request) {
                            is AppRequest.OpenTitle -> {
                                if (!vodEnabled) {
                                    appRequests.value = null
                                    return@LaunchedEffect
                                }
                                // The library is read in the background at startup
                                if (requestLibrary.byKey[request.titleKey] == null && requestLibrary.titles.isEmpty()) return@LaunchedEffect
                                showWelcome = false
                                openVod { currentScreen = CurrentScreen.VodDetail(request.titleKey, returnTo = CurrentScreen.Home) }
                            }
                            is AppRequest.OpenChannel -> {
                                if (unhiddenChannels.isEmpty()) return@LaunchedEffect
                                showWelcome = false
                                unhiddenChannels.firstOrNull { request.channelId in it.allIds }?.let { channel ->
                                    currentScreen = CurrentScreen.Player(channel, returnTo = CurrentScreen.Home)
                                }
                            }
                            is AppRequest.Play -> {
                                if (unhiddenChannels.isEmpty()) return@LaunchedEffect
                                showWelcome = false
                                val channel = AppIntents.channelFor(request.query, unhiddenChannels)
                                val title = if (channel == null && vodEnabled) AppIntents.titleFor(request.query, requestLibrary.titles) else null
                                when {
                                    channel != null -> currentScreen = CurrentScreen.Player(channel, returnTo = CurrentScreen.Home)
                                    title != null -> openVod { currentScreen = CurrentScreen.VodDetail(title.key, returnTo = CurrentScreen.Home) }
                                    else -> currentScreen = CurrentScreen.Search(startWithVoice = false, query = request.query)
                                }
                            }
                        }
                        appRequests.value = null
                    }

                    // Movies and series started, in the Play next row of the TV home (not when locked by the parental
                    // code: that row is outside the app); left as it is while the library is not read
                    if (!Device.isTouch) {
                        val watchNext = remember { WatchNext(applicationContext) }
                        val nextProgress by configRepository.vodStore.progress.collectAsState()
                        val vodLockedSetting by configRepository.parental.vodLocked.collectAsState()
                        val parentalCode by configRepository.parental.hasPin.collectAsState()
                        LaunchedEffect(nextProgress, requestLibrary.byKey, vodEnabled, vodLockedSetting, parentalCode) {
                            val shown = vodEnabled && !(vodLockedSetting && parentalCode)
                            if (shown && requestLibrary.titles.isEmpty()) return@LaunchedEffect
                            delay(WATCH_NEXT_DELAY_MS)
                            watchNext.publish(if (shown) VodRows.continuing(requestLibrary.byKey, nextProgress, WATCH_NEXT_SIZE) else emptyList())
                        }
                    }

                    // Locked again (the app was left) while on the movies and series: back to the live TV home
                    LaunchedEffect(vodHidden) {
                        val onVod = currentScreen.let {
                            it is CurrentScreen.Vod || it is CurrentScreen.VodSearch || it is CurrentScreen.VodDetail ||
                                it is CurrentScreen.VodPlayer || it is CurrentScreen.VodCategories
                        }
                        if (vodHidden && onVod) currentScreen = CurrentScreen.Home
                    }
                    pinFor?.let { action ->
                        val wrongPin = stringResource(R.string.parental_wrong_pin)
                        val resources = LocalResources.current
                        PinDialog(
                            title = stringResource(R.string.parental_enter_pin),
                            error = pinError,
                            onSubmit = { pin ->
                                val parental = configRepository.parental
                                if (parental.unlock(pin)) {
                                    pinFor = null
                                    pinError = null
                                    action()
                                } else {
                                    val wait = parental.waitMs()
                                    pinError = if (wait > 0) resources.getString(R.string.parental_wait, (wait + 999) / 1000) else wrongPin
                                }
                            },
                            onDismiss = {
                                pinFor = null
                                pinError = null
                            }
                        )
                    }

                    // Newer version on GitHub: offered on the home screen (never over playback, the welcome or
                    // the quick guide), once per start unless ignored for good. Installing downloads it with
                    // the progress shown here, then the system installer asks for confirmation
                    val ignoredVersion by updateViewModel.ignoredVersion.collectAsState()
                    var updatePromptClosed by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(updateState) {
                        // The system installer takes over, or the error shows in the settings
                        if (updateState is UpdateState.Installing || updateState is UpdateState.Failed) updatePromptClosed = true
                    }
                    val offeredRelease = when (updateState) {
                        is UpdateState.Available, is UpdateState.Ready, is UpdateState.Downloading -> updateState.release
                        else -> null
                    }
                    if (offeredRelease != null && UpdateFiles.shouldPrompt(offeredRelease.version, ignoredVersion) && !updatePromptClosed &&
                        currentScreen == CurrentScreen.Home && !showWelcome && !showGuide
                    ) {
                        UpdateReadyDialog(
                            release = offeredRelease,
                            progress = (updateState as? UpdateState.Downloading)?.progress,
                            onInstall = { updateViewModel.install(offeredRelease) },
                            onIgnore = {
                                updatePromptClosed = true
                                updateViewModel.ignore(offeredRelease)
                            },
                            onDismiss = { updatePromptClosed = true }
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

    /**
     * Screen mode for a video of [fps] frames per second ([DisplayModes.pick]); null (or no better mode) gives
     * the choice back to the system.
     */
    private fun setDisplayMode(fps: Float?) {
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay) ?: return
        val wanted = if (fps == null) {
            0
        } else {
            val modes = display.supportedModes.map { DisplayModes.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
            // Compared with the usual mode (not one chosen for the previous video)
            val usual = display.supportedModes.firstOrNull { it.modeId == usualDisplayMode } ?: display.mode
            val current = DisplayModes.Mode(usual.modeId, usual.physicalWidth, usual.physicalHeight, usual.refreshRate)
            DisplayModes.pick(modes, current, fps)?.id ?: 0
        }
        if (wanted != 0 && usualDisplayMode == 0) usualDisplayMode = display.mode.modeId
        if (wanted == 0) usualDisplayMode = 0
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId == wanted) return
        attributes.preferredDisplayModeId = wanted
        window.attributes = attributes
    }

    // Request from outside the app (Play next row of the TV home, Assistant), handled once the lists are there
    private val appRequests = MutableStateFlow<AppRequest?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private val recordingStore by lazy { RecordingStore.get(this) }

    // Movies and episodes already sent to the Trakt history in this run (the position is reported every few seconds)
    private val traktSent = java.util.Collections.synchronizedSet(HashSet<String>())

    // Android 13+: the reminders need the notifications, asked for when the first one is set
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** The system page where the user allows Télé the exact alarms (Android 12+), when the TV has it. */
    private fun openExactAlarmsSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
        } catch (e: ActivityNotFoundException) {
            Log.w("MainActivity", "No exact alarms settings on this device")
        }
    }

    private fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        AppIntents.parse(intent.action, intent.dataString, intent.getStringExtra(SearchManager.QUERY))?.let { appRequests.value = it }
    }

    override fun onStop() {
        super.onStop()
        // App in the background (Home key, standby): no preview left playing on the home screen
        playerManager.stopPreview()
        // The parental control locks again: the code was only entered for this sitting
        viewModel.configRepository.parental.lock()
        // No corner channel holding a provider connection in the background
        insetPlayer.pause()
        // Off screen: the reminders are notified by the system
        ReminderAlarms.appVisible = false
    }

    override fun onStart() {
        super.onStart()
        ReminderAlarms.appVisible = true
        insetPlayer.resume()
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
        insetPlayer.stop()
    }
}

/**
 * Trailer of a title in the YouTube app (the TV one handles vnd.youtube:), else in a browser. Nothing
 * happens without either.
 */
private fun openTrailer(context: Context, youtubeKey: String) {
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, "vnd.youtube:$youtubeKey".toUri()),
        Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$youtubeKey".toUri())
    )
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Next way
        }
    }
}
