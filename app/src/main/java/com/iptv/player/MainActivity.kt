package com.iptv.player

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
import coil.imageLoader
import coil.request.ImageRequest
import com.iptv.player.data.model.Channel
import com.iptv.player.data.remote.ProgramPosterRepository
import com.iptv.player.data.model.SourceType
import com.iptv.player.playback.StreamAuth
import com.iptv.player.playback.TvPlayerManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.iptv.player.ui.components.DISPATCHARR_AUTOMATIC
import com.iptv.player.ui.screens.HomeScreen
import com.iptv.player.ui.screens.PlayerScreen
import com.iptv.player.ui.screens.SettingsScreen
import com.iptv.player.ui.screens.WelcomeScreen
import com.iptv.player.ui.screens.pickFavoriteChannels
import com.iptv.player.ui.theme.IPTVPlayerTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Écran affiché (navigation simple, sans bibliothèque de navigation). */
sealed class CurrentScreen {
    object Home : CurrentScreen()
    object Settings : CurrentScreen()
    data class Player(val channel: Channel) : CurrentScreen()
}

private const val WELCOME_MIN_MS = 1_800L
private const val WELCOME_MAX_MS = 15_000L

/**
 * Activité unique de l'application : héberge l'interface Compose (accueil, lecteur, paramètres),
 * l'écran d'accueil "Bonjour/Bonsoir" du démarrage et le lecteur ExoPlayer partagé entre les chaînes.
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

            val userName by viewModel.userName.collectAsState()
            val context = LocalContext.current

            var currentScreen by remember { mutableStateOf<CurrentScreen>(CurrentScreen.Home) }
            // Accueil "Bonjour/Bonsoir" affiché une seule fois par lancement de l'application
            var showWelcome by rememberSaveable { mutableStateOf(true) }

            // L'accueil reste jusqu'au chargement du guide et des visuels des favoris (borné dans le temps)
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
            // Clé API Dispatcharr réservée au serveur Dispatcharr (jamais transmise aux fournisseurs de flux)
            val streamAuth = remember(activeConfig) {
                activeConfig?.takeIf { it.type == SourceType.DISPATCHARR && it.apiKey.isNotBlank() }?.let { config ->
                    config.normalizedUrl.toHttpUrlOrNull()?.host?.let { host -> StreamAuth(host, config.apiKey) }
                }
            }

            // Signification du mode "Automatique" du choix de flux selon la source active
            val automaticStreamDescription = when (activeConfig?.type) {
                SourceType.XTREAM -> "Format par défaut du fournisseur"
                SourceType.M3U -> "Lien indiqué dans la playlist"
                else -> DISPATCHARR_AUTOMATIC
            }

            // Conserve l'état de l'accueil (catégorie, défilement, focus) pendant la lecture / les paramètres
            val saveableStateHolder = rememberSaveableStateHolder()

            // Chaînes non masquées individuellement : les favoris y sont pris, même si leur catégorie est masquée
            val unhiddenChannels = remember(channels, hiddenIds) {
                channels.filterNot { hiddenIds.contains(it.id) }
            }
            // Chaînes de la grille, du volet et du zapping : sans les catégories masquées
            val visibleChannels = remember(unhiddenChannels, hiddenGroups) {
                unhiddenChannels.filterNot { hiddenGroups.contains(it.group) }
            }
            val favoriteChannels = remember(unhiddenChannels, visibleChannels, favoriteIds) {
                pickFavoriteChannels(unhiddenChannels, visibleChannels, favoriteIds)
            }
            // Catégories et nombre de chaînes, dans l'ordre des chaînes (pour les paramètres)
            val categoryCounts = remember(unhiddenChannels) {
                unhiddenChannels.groupingBy { it.group }.eachCount().toList()
            }

            // Zapping par identifiant (et non par égalité d'objet, qui change à chaque mise à jour EPG)
            fun zap(from: Channel, step: Int) {
                if (visibleChannels.isEmpty()) return
                val index = visibleChannels.indexOfFirst { it.id == from.id }
                val nextIndex = if (index == -1) 0 else Math.floorMod(index + step, visibleChannels.size)
                currentScreen = CurrentScreen.Player(visibleChannels[nextIndex])
            }

            IPTVPlayerTheme(
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
                            // Pendant l'accueil, l'écran d'accueil caché ne doit pas réagir ; OK permet de passer
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
                                        // Choisir un flux lance directement la chaîne avec ce flux
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
                            // Version à jour de la chaîne (programme EPG courant, flux choisi)
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

    /** Télécharge les visuels des vignettes favorites pour qu'elles soient prêtes à la fin de l'accueil. */
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
