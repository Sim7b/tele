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
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.remote.ProgramPosterRepository
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.playback.TvPlayerManager
import io.github.sim7b.tele.ui.components.DISPATCHARR_AUTOMATIC
import io.github.sim7b.tele.ui.screens.HomeScreen
import io.github.sim7b.tele.ui.screens.PlayerScreen
import io.github.sim7b.tele.ui.screens.SettingsScreen
import io.github.sim7b.tele.ui.screens.WelcomeScreen
import io.github.sim7b.tele.ui.screens.pickFavoriteChannels
import io.github.sim7b.tele.ui.theme.TeleTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Displayed screen (simple navigation, without a navigation library). */
sealed class CurrentScreen {
    object Home : CurrentScreen()
    object Settings : CurrentScreen()
    data class Player(val channel: Channel) : CurrentScreen()
}

private const val WELCOME_MIN_MS = 1_800L
private const val WELCOME_MAX_MS = 15_000L

/**
 * The app's single activity: hosts the Compose UI (home, player, settings),
 * the startup "Good morning/Good evening" welcome screen and the ExoPlayer instance shared across channels.
 */
class MainActivity : ComponentActivity() {

    private lateinit var playerManager: TvPlayerManager
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerManager = TvPlayerManager(this)
        val configRepository = viewModel.configRepository

        setContent {
            val activeConfig by configRepository.activeConfig.collectAsState()
            val uiDensity by configRepository.uiDensity.collectAsState()
            val themeBackground by configRepository.themeBackground.collectAsState()
            val accentColor by configRepository.accentColor.collectAsState()
            val favoriteIds by configRepository.favoriteIds.collectAsState()
            val hiddenIds by configRepository.hiddenIds.collectAsState()
            val hiddenGroups by configRepository.hiddenGroups.collectAsState()

            val channels by viewModel.channels.collectAsState()
            val isLoadingChannels by viewModel.isLoading.collectAsState()
            val loadError by viewModel.loadError.collectAsState()
            val streamSlots by viewModel.streamSlots.collectAsState()

            val userName by viewModel.userName.collectAsState()
            val context = LocalContext.current

            var currentScreen by remember { mutableStateOf<CurrentScreen>(CurrentScreen.Home) }
            // Back on the home screen: refresh the slots right away (leaving the player frees one)
            LaunchedEffect(currentScreen is CurrentScreen.Home) {
                if (currentScreen is CurrentScreen.Home) viewModel.refreshStreamSlots()
            }
            // "Good morning/Good evening" welcome shown only once per app launch
            var showWelcome by rememberSaveable { mutableStateOf(true) }

            // The welcome stays until the guide and the favorites' artwork are loaded (time-bounded)
            LaunchedEffect(Unit) {
                if (!showWelcome) return@LaunchedEffect
                val startedAt = SystemClock.elapsedRealtime()
                withTimeoutOrNull(WELCOME_MAX_MS) {
                    viewModel.initialSyncDone.first { it }
                    val hidden = configRepository.hiddenIds.value
                    val hiddenCategories = configRepository.hiddenGroups.value
                    val pool = viewModel.channels.value.filterNot { hidden.contains(it.id) }
                    val favorites = pickFavoriteChannels(
                        pool,
                        pool.filterNot { hiddenCategories.contains(it.group) },
                        configRepository.favoriteIds.value
                    )
                    preloadPosters(context, favorites)
                }
                val remaining = WELCOME_MIN_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) delay(remaining)
                showWelcome = false
            }
            // Dispatcharr API key reserved for the Dispatcharr server (never sent to stream providers)
            val streamAuth = remember(activeConfig) {
                activeConfig?.takeIf { it.type == SourceType.DISPATCHARR }?.let { ApiKeyAuth.of(it) }
            }

            // Meaning of the "Automatic" stream choice mode depending on the active source
            val automaticStreamDescription = when (activeConfig?.type) {
                SourceType.XTREAM -> stringResource(R.string.main_stream_auto_xtream)
                SourceType.M3U -> stringResource(R.string.main_stream_auto_m3u)
                else -> DISPATCHARR_AUTOMATIC
            }

            // Keeps the home screen state (category, scroll, focus) during playback / settings
            val saveableStateHolder = rememberSaveableStateHolder()

            // Channels not individually hidden: favorites are taken from them, even if their category is hidden
            val unhiddenChannels = remember(channels, hiddenIds) {
                channels.filterNot { hiddenIds.contains(it.id) }
            }
            // Channels of the grid, the panel and zapping: without the hidden categories
            val visibleChannels = remember(unhiddenChannels, hiddenGroups) {
                unhiddenChannels.filterNot { hiddenGroups.contains(it.group) }
            }
            val favoriteChannels = remember(unhiddenChannels, visibleChannels, favoriteIds) {
                pickFavoriteChannels(unhiddenChannels, visibleChannels, favoriteIds)
            }
            // Categories and channel counts, in channel order (for the settings)
            val categoryCounts = remember(unhiddenChannels) {
                unhiddenChannels.groupingBy { it.group }.eachCount().toList()
            }

            // Zapping by id (not by object equality, which changes on every EPG update)
            fun zap(from: Channel, step: Int) {
                if (visibleChannels.isEmpty()) return
                val index = visibleChannels.indexOfFirst { it.id == from.id }
                val nextIndex = if (index == -1) 0 else Math.floorMod(index + step, visibleChannels.size)
                currentScreen = CurrentScreen.Player(visibleChannels[nextIndex])
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
                                    activeConfig = activeConfig,
                                    favoriteIds = favoriteIds,
                                    isLoading = isLoadingChannels,
                                    loadError = loadError,
                                    streamSlots = streamSlots,
                                    onChannelSelected = { channel ->
                                        currentScreen = CurrentScreen.Player(channel)
                                    },
                                    onToggleFavorite = { chId ->
                                        configRepository.toggleFavoriteChannel(chId)
                                    },
                                    onHideChannel = { chId ->
                                        configRepository.hideChannel(chId)
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
                                    onOpenSettings = {
                                        currentScreen = CurrentScreen.Settings
                                    },
                                    onRefresh = {
                                        viewModel.refresh()
                                    }
                                )
                            }
                        }
                        is CurrentScreen.Settings -> {
                            SettingsScreen(
                                repository = configRepository,
                                onTestConnection = { config -> viewModel.testConnection(config) },
                                categories = categoryCounts,
                                onRefreshPlaylist = { viewModel.refresh() },
                                onRefreshEpg = { viewModel.refresh() },
                                onBack = {
                                    currentScreen = CurrentScreen.Home
                                }
                            )
                        }
                        is CurrentScreen.Player -> {
                            // Up-to-date version of the channel (current EPG program, chosen stream)
                            val liveChannel = channels.firstOrNull { it.id == screen.channel.id } ?: screen.channel
                            PlayerScreen(
                                channel = liveChannel,
                                channels = visibleChannels,
                                playerManager = playerManager,
                                streamAuth = streamAuth,
                                userAgent = activeConfig?.customUserAgent,
                                loadStreams = { viewModel.loadStreams(liveChannel) },
                                onSelectStream = { stream -> viewModel.selectStream(liveChannel, stream) },
                                onSelectChannel = { selected -> currentScreen = CurrentScreen.Player(selected) },
                                automaticStreamDescription = automaticStreamDescription,
                                onBack = {
                                    currentScreen = CurrentScreen.Home
                                },
                                onNextChannel = { zap(liveChannel, +1) },
                                onPreviousChannel = { zap(liveChannel, -1) }
                            )
                        }
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

    override fun onDestroy() {
        super.onDestroy()
        playerManager.release()
    }
}
