package io.github.sim7b.tele.ui.screens

import io.github.sim7b.tele.ui.components.TIMESHIFT_LIVE_MS
import io.github.sim7b.tele.ui.components.clockLabel
import io.github.sim7b.tele.playback.TimeshiftState
import io.github.sim7b.tele.playback.InsetPlayer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import android.view.TextureView
import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.R
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import kotlin.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.playback.CastInfo
import io.github.sim7b.tele.playback.PictureFormat
import io.github.sim7b.tele.playback.StreamInfo
import io.github.sim7b.tele.playback.TvPlayerManager
import io.github.sim7b.tele.ui.components.CastButton
import io.github.sim7b.tele.ui.components.CastRemoteScreen
import io.github.sim7b.tele.ui.components.ChannelListPanel
import io.github.sim7b.tele.ui.components.Device
import io.github.sim7b.tele.ui.components.GuideActions
import io.github.sim7b.tele.ui.components.LocalScreen
import io.github.sim7b.tele.ui.components.RoundIconButton
import io.github.sim7b.tele.ui.components.applyTeleStyle
import io.github.sim7b.tele.ui.components.playerGestures
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.vod.VodChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import io.github.sim7b.tele.ui.components.PlaybackOptionsPanel
import io.github.sim7b.tele.ui.components.PlaybackStatsOverlay
import io.github.sim7b.tele.ui.components.ChannelNumberBadge
import io.github.sim7b.tele.ui.components.rememberChannelNumberEntry
import io.github.sim7b.tele.ui.components.LogoPlate
import io.github.sim7b.tele.ui.components.ProgressLine
import io.github.sim7b.tele.ui.components.StreamPickerDialog
import io.github.sim7b.tele.ui.components.Tag
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ZAP_DEBOUNCE_MS = 300L

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    channel: Channel,
    channels: List<Channel>,
    playerManager: TvPlayerManager,
    // Category order of the channel panel, as on the home screen
    categoryOrder: List<String> = emptyList(),
    // Dispatcharr keys of the enabled servers: each one only goes to its own server (origin check)
    streamAuths: List<ApiKeyAuth> = emptyList(),
    userAgent: String? = null,
    loadStreams: suspend () -> Result<List<StreamSource>>,
    // Streams to try in automatic mode, best quality first (empty: the channel URL as is)
    resolveStreams: suspend (Channel) -> List<String> = { emptyList() },
    onSelectStream: (StreamSource?) -> Unit,
    onSelectChannel: (Channel) -> Unit,
    automaticStreamDescription: String,
    onBack: () -> Unit,
    onNextChannel: () -> Unit,
    onPreviousChannel: () -> Unit,
    // Picture format and audio language of the playback options, kept in the settings
    pictureFormat: PictureFormat = PictureFormat.FIT,
    onPictureFormatChange: (PictureFormat) -> Unit = {},
    onAudioLanguageChange: (String?) -> Unit = {},
    // Programs of a channel sorted by start time, for the coming programs of the channel panel
    programsOf: (Channel) -> List<GuideProgram> = { emptyList() },
    // Favorites and recent channels (first categories of the channel panel), reminders, replays and channel menu
    favorites: List<Channel> = emptyList(),
    recents: List<Channel> = emptyList(),
    guideActions: GuideActions = GuideActions(),
    // Second channel in a corner of the picture (muted), swapped with the main one or closed
    inset: Channel? = null,
    insetPlayer: InsetPlayer? = null,
    onSwapInset: () -> Unit = {},
    onCloseInset: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isLoading by playerManager.isLoading.collectAsState()
    val tracks by playerManager.tracks.collectAsState()
    var showOptionsPanel by remember { mutableStateOf(false) }
    val errorMessage by playerManager.error.collectAsState()
    val streamInfo by playerManager.streamInfo.collectAsState()
    val reconnection by playerManager.reconnection.collectAsState()
    // The channel went over to another of its streams by itself: said a moment on the picture
    val streamSwitches by playerManager.streamSwitches.collectAsState()
    var switchNotice by remember { mutableStateOf(false) }
    LaunchedEffect(streamSwitches) {
        if (streamSwitches == 0) return@LaunchedEffect
        switchNotice = true
        delay(STREAM_SWITCH_NOTICE_MS)
        switchNotice = false
    }
    val showStats by playerManager.showStats.collectAsState()
    // Live channel kept on the device: paused, how far behind live (read every second)
    var timeshift by remember { mutableStateOf<TimeshiftState?>(null) }
    // The current OK press paused or resumed the live channel (undone if it turns into a long press)
    var toggledByPress by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            timeshift = playerManager.timeshiftState()
            delay(TIMESHIFT_POLL_MS)
        }
    }
    // Phone / tablet casting: the stream plays on the Chromecast, this screen is the remote
    val castDevice by playerManager.castDevice.collectAsState()

    var showOverlay by remember { mutableStateOf(true) }
    // Incremented on every requested display to restart the hide delay
    var overlayToken by remember { mutableIntStateOf(0) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var showChannelPanel by remember { mutableStateOf(false) }
    var refocusToken by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val streamUrl = channel.activeStreamUrl
    val currentStreamUrl by rememberUpdatedState(streamUrl)
    val currentStreamAuths by rememberUpdatedState(streamAuths)
    val currentUserAgent by rememberUpdatedState(userAgent)
    val currentChannel by rememberUpdatedState(channel)
    val scope = rememberCoroutineScope()

    fun revealOverlay() {
        showOverlay = true
        overlayToken++
    }

    // Channel start (and on every channel switch / stream change)
    val lastStartRequestAt = remember { mutableLongStateOf(0L) }
    LaunchedEffect(streamUrl, streamAuths, userAgent) {
        revealOverlay()
        // Zapping through several channels: only the one the user stops on is opened, providers often
        // limit simultaneous connections (the banner already shows the new channel)
        val now = SystemClock.elapsedRealtime()
        val isQuickZap = now - lastStartRequestAt.longValue < ZAP_DEBOUNCE_MS
        lastStartRequestAt.longValue = now
        if (isQuickZap) delay(ZAP_DEBOUNCE_MS)
        val urls = resolveStreams(channel)
        playerManager.playStream(
            urls.firstOrNull() ?: streamUrl,
            customUserAgent = userAgent,
            auths = streamAuths,
            fallbacks = urls.drop(1),
            info = channel.castInfo()
        )
    }

    // Automatically hide the overlay after 5 seconds
    LaunchedEffect(showOverlay, overlayToken) {
        if (showOverlay) {
            delay(5000)
            showOverlay = false
        }
    }

    // Stop the stream when leaving the player: otherwise the sound keeps playing behind the home screen
    DisposableEffect(Unit) {
        onDispose { playerManager.stop() }
    }

    // App in the background (Home key, standby): stop the stream, resume it on return
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var stoppedInBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Casting: the Chromecast keeps playing while the phone is locked
                Lifecycle.Event.ON_STOP -> if (playerManager.castDevice.value == null) {
                    stoppedInBackground = true
                    playerManager.stop()
                }
                Lifecycle.Event.ON_START -> if (stoppedInBackground) {
                    stoppedInBackground = false
                    // Same automatic choice as at the channel start
                    scope.launch {
                        val urls = resolveStreams(currentChannel)
                        playerManager.playStream(
                            urls.firstOrNull() ?: currentStreamUrl,
                            customUserAgent = currentUserAgent,
                            auths = currentStreamAuths,
                            fallbacks = urls.drop(1),
                            info = currentChannel.castInfo()
                        )
                    }
                    revealOverlay()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The player captures the remote keys (channel switching, OSD, play/pause, stream choice)
    LaunchedEffect(refocusToken) {
        focusRequester.requestFocusWithRetry()
    }

    BackHandler {
        onBack()
    }

    // Remote digits: switch to the typed channel number
    val numberEntry = rememberChannelNumberEntry(channels) { selected ->
        if (selected.id != channel.id) onSelectChannel(selected)
    }

    // Declared after: takes priority while the channel panel is open
    BackHandler(enabled = showChannelPanel) {
        showChannelPanel = false
        refocusToken++
    }

    BackHandler(enabled = showOptionsPanel) {
        showOptionsPanel = false
        refocusToken++
    }

    // Back while typing a number only cancels it
    BackHandler(enabled = numberEntry.isTyping) {
        numberEntry.cancel()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                // Panel open: its rows handle navigation (up/down must no longer switch channels)
                if (showChannelPanel || showOptionsPanel) return@onKeyEvent false
                if (numberEntry.onKey(keyEvent)) return@onKeyEvent true
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                when (keyEvent.nativeKeyEvent.keyCode) {
                    // Direct channel switching (CH+ = next channel, CH- = previous channel)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        onNextChannel()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        onPreviousChannel()
                        true
                    }
                    // OK: shows the banner (retries after an error); long press: stream choice
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (repeatCount == 0) toggledByPress = false
                        when {
                            repeatCount == 1 -> {
                                // A long press: not a pause, the press that started it is undone
                                if (toggledByPress) {
                                    playerManager.timeshiftPlayPause()
                                    timeshift = playerManager.timeshiftState()
                                    toggledByPress = false
                                }
                                showStreamPicker = true
                            }
                            repeatCount == 0 && errorMessage != null -> playerManager.retry()
                            // Paused live channel: OK resumes it; banner shown: OK pauses (the banner says so)
                            repeatCount == 0 && (timeshift?.paused == true || (showOverlay && timeshift != null)) -> {
                                playerManager.timeshiftPlayPause()
                                timeshift = playerManager.timeshiftState()
                                toggledByPress = true
                                revealOverlay()
                            }
                            repeatCount == 0 -> revealOverlay()
                        }
                        true
                    }
                    // Right arrow (or Menu): playback options, mirroring the channel panel on the left
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (repeatCount == 0) {
                            showOverlay = false
                            showOptionsPanel = true
                        }
                        true
                    }
                    // Left arrow: channel panel with the guide, over the live stream
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (repeatCount == 0) {
                            showOverlay = false
                            showChannelPanel = true
                        }
                        true
                    }
                    KeyEvent.KEYCODE_INFO -> {
                        revealOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        playerManager.timeshiftPlayPause()
                        timeshift = playerManager.timeshiftState()
                        revealOverlay()
                        true
                    }
                    // Back and forward in the live channel kept on the device
                    KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                        playerManager.timeshiftSeekBy(-TIMESHIFT_STEP_MS)
                        timeshift = playerManager.timeshiftState()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                        playerManager.timeshiftSeekBy(TIMESHIFT_STEP_MS)
                        timeshift = playerManager.timeshiftState()
                        true
                    }
                    else -> false
                }
            }
    ) {
        castDevice?.let { device ->
            CastRemoteScreen(
                device = device,
                title = channel.name,
                subtitle = channel.currentProgram?.title,
                imageUrl = channel.logoUrl,
                hint = stringResource(R.string.cast_remote_hint)
            )
        }
        // Native ExoPlayer video view (hardware-accelerated SurfaceView)
        if (castDevice == null) AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerManager.getPlayer()
                    useController = false
                    // Prevents the Android TV screensaver from covering the live stream
                    keepScreenOn = true
                    isFocusable = false
                    subtitleView?.applyTeleStyle()
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view -> view.resizeMode = pictureFormat.resizeMode },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize()
        )

        // Phone / tablet: tap for the banner and its buttons (or to close a panel), swipe up / down to switch
        // channel, hold for the streams
        if (Device.isTouch) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerGestures(
                        onTap = {
                            when {
                                showChannelPanel || showOptionsPanel -> {
                                    showChannelPanel = false
                                    showOptionsPanel = false
                                    refocusToken++
                                }
                                showOverlay -> showOverlay = false
                                else -> revealOverlay()
                            }
                        },
                        onLongPress = { showStreamPicker = true },
                        onSwipe = { step ->
                            if (!showChannelPanel && !showOptionsPanel) {
                                if (step > 0) onNextChannel() else onPreviousChannel()
                            }
                        }
                    )
            )
        }

        // Loading spinner (buffering / channel switching)
        if (isLoading && errorMessage == null) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.85f),
                strokeWidth = 2.5.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
            )
        }

        // Playback error: message and a hint to get out of it
        if (errorMessage != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 28.dp, vertical = 22.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_playback_failed),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = errorMessage?.resolve() ?: "",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (Device.isTouch) {
                    // The keys of the remote, as buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VodChip(label = stringResource(R.string.home_retry), selected = false, onClick = { playerManager.retry() })
                        VodChip(label = stringResource(R.string.player_other_stream), selected = false, onClick = { showStreamPicker = true })
                    }
                } else {
                    Text(
                        text = stringResource(R.string.player_retry_hint),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.player_other_stream_hint),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Discreet reconnection indicator after a drop (the spinner shows the loading itself)
        reconnection?.let { attempt ->
            if (errorMessage == null) {
                Text(
                    text = stringResource(R.string.player_reconnecting, attempt.attempt, attempt.maxAttempts),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 84.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        if (switchNotice && errorMessage == null && reconnection == null) {
            Text(
                text = stringResource(R.string.player_stream_switched),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        // Information banner (OSD)
        AnimatedVisibility(
            visible = showOverlay && !showChannelPanel && !showOptionsPanel,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlayerOverlay(channel = channel, streamInfo = streamInfo, paused = timeshift?.paused)
        }

        // Phone / tablet: with the banner, the buttons of the keys (Back, channel panel, options)
        if (Device.isTouch) {
            AnimatedVisibility(
                visible = showOverlay && !showChannelPanel && !showOptionsPanel,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                PlayerTopBar(
                    playerManager = playerManager,
                    onBack = onBack,
                    onChannels = {
                        showOverlay = false
                        showChannelPanel = true
                    },
                    onOptions = {
                        showOverlay = false
                        showOptionsPanel = true
                    },
                    paused = timeshift?.paused,
                    onPlayPause = {
                        playerManager.timeshiftPlayPause()
                        timeshift = playerManager.timeshiftState()
                        revealOverlay()
                    }
                )
            }
        }

        ChannelNumberBadge(
            entry = numberEntry,
            channels = channels,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 32.dp, end = 48.dp)
        )

        // Channel panel (left arrow): the video keeps playing behind
        AnimatedVisibility(
            visible = showChannelPanel,
            enter = fadeIn() + slideInHorizontally { -it / 4 },
            exit = fadeOut() + slideOutHorizontally { -it / 4 },
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            ChannelListPanel(
                channels = channels,
                categoryOrder = categoryOrder,
                playingChannelId = channel.id,
                programsOf = programsOf,
                favorites = favorites,
                recents = recents,
                actions = guideActions,
                onSelectChannel = { selected ->
                    showChannelPanel = false
                    refocusToken++
                    if (selected.id != channel.id) onSelectChannel(selected)
                },
                onDismiss = {
                    showChannelPanel = false
                    refocusToken++
                }
            )
        }

        if (showStats) PlaybackStatsOverlay(playerManager, modifier = Modifier.align(Alignment.TopStart))

        // Live channel paused or played behind live
        timeshift?.takeIf { it.paused || it.behindMs >= TIMESHIFT_LIVE_MS }?.let { state ->
            TimeshiftBadge(state, modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        }

        if (inset != null && insetPlayer != null && castDevice == null) {
            InsetVideo(channel = inset, player = insetPlayer, modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp))
        }

        // Playback options (right arrow): stream, audio, subtitles, picture format, recording of the program on air
        val onAir = remember(showOptionsPanel, channel) {
            val now = System.currentTimeMillis()
            if (showOptionsPanel) programsOf(channel).firstOrNull { it.startMillis <= now && it.endMillis > now } else null
        }
        AnimatedVisibility(
            visible = showOptionsPanel,
            enter = fadeIn() + slideInHorizontally { it / 4 },
            exit = fadeOut() + slideOutHorizontally { it / 4 },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            PlaybackOptionsPanel(
                tracks = tracks,
                pictureFormat = pictureFormat,
                onChooseStream = {
                    showOptionsPanel = false
                    showStreamPicker = true
                },
                onSelectAudio = { choice ->
                    playerManager.selectAudio(choice)
                    onAudioLanguageChange(choice.language)
                },
                onSelectSubtitles = { choice -> playerManager.selectSubtitles(choice) },
                onSelectPictureFormat = onPictureFormatChange,
                onDismiss = {
                    showOptionsPanel = false
                    refocusToken++
                },
                statsOn = showStats,
                onToggleStats = { playerManager.toggleStats() },
                insetName = inset?.name,
                onSwapInset = {
                    showOptionsPanel = false
                    refocusToken++
                    onSwapInset()
                },
                onCloseInset = {
                    showOptionsPanel = false
                    refocusToken++
                    onCloseInset()
                },
                timeshift = timeshift,
                onTimeshiftPlayPause = {
                    playerManager.timeshiftPlayPause()
                    timeshift = playerManager.timeshiftState()
                },
                onTimeshiftSeek = { delta ->
                    playerManager.timeshiftSeekBy(delta)
                    timeshift = playerManager.timeshiftState()
                },
                onTimeshiftLive = {
                    playerManager.timeshiftToLive()
                    timeshift = playerManager.timeshiftState()
                },
                recordTitle = onAir?.takeIf { guideActions.canRecord(channel, it, System.currentTimeMillis()) }?.title,
                recording = onAir?.let { guideActions.isRecorded(channel, it) } == true,
                onToggleRecording = { onAir?.let { guideActions.toggleRecording?.invoke(channel, it) } }
            )
        }
    }

    if (showStreamPicker) {
        StreamPickerDialog(
            channel = channel,
            automaticDescription = automaticStreamDescription,
            loadStreams = loadStreams,
            onSelectStream = onSelectStream,
            onDismiss = {
                showStreamPicker = false
                refocusToken++
            }
        )
    }
}

/** Phone / tablet: Back on the left, the channel panel and the playback options on the right. */
@Composable
private fun PlayerTopBar(
    playerManager: TvPlayerManager,
    onBack: () -> Unit,
    onChannels: () -> Unit,
    onOptions: () -> Unit,
    // Live channel kept on the device: paused or not (null: no pause)
    paused: Boolean? = null,
    onPlayPause: () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .padding(horizontal = screenMargin(32.dp), vertical = 16.dp)
    ) {
        RoundIconButton(icon = Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back), onClick = onBack)
        Spacer(modifier = Modifier.weight(1f))
        if (paused != null) {
            RoundIconButton(
                icon = if (paused) Icons.Filled.PlayArrow else PauseIcon,
                contentDescription = stringResource(if (paused) R.string.timeshift_resume else R.string.timeshift_pause),
                onClick = onPlayPause
            )
        }
        CastButton(playerManager)
        RoundIconButton(icon = Icons.Filled.List, contentDescription = stringResource(R.string.panel_title), onClick = onChannels)
        RoundIconButton(icon = Icons.Filled.Settings, contentDescription = stringResource(R.string.options_title), onClick = onOptions)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerOverlay(channel: Channel, streamInfo: StreamInfo, paused: Boolean? = null) {
    val program = channel.currentProgram
    // Phone: narrower banner, without the technical column
    val compact = LocalScreen.current.compact

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Black.copy(alpha = 0.92f)
                )
            )
            .padding(start = screenMargin(56.dp), end = screenMargin(56.dp), top = 70.dp, bottom = if (compact) 20.dp else 34.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            LogoPlate(
                logoUrl = channel.logoUrl,
                name = channel.name,
                corner = 10.dp,
                padding = 8.dp,
                modifier = if (compact) Modifier.size(width = 64.dp, height = 44.dp) else Modifier.size(width = 92.dp, height = 64.dp)
            )

            Spacer(modifier = Modifier.width(if (compact) 14.dp else 22.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(LiveRed)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = stringResource(R.string.player_live),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.4.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = channel.name,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = program?.title ?: channel.name,
                    fontSize = if (compact) 20.sp else 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (program != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = program.startTime, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(10.dp))
                        ProgressLine(
                            progress = program.progress,
                            height = 3.dp,
                            trackColor = Color.White.copy(alpha = 0.2f),
                            modifier = if (compact) Modifier.weight(1f) else Modifier.width(280.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = program.endTime, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                        if (!program.category.isNullOrBlank() && !compact) {
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = program.category,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }
                    }
                    if (!program.nextTitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.player_next_program, program.nextStartTime ?: "", program.nextTitle),
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (compact) return@Row
            Spacer(modifier = Modifier.width(24.dp))

            // Actual technical information and active stream
            Column(horizontalAlignment = Alignment.End) {
                // The pause of the live channel, done with OK while the banner shows
                if (paused != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                        androidx.compose.material3.Icon(
                            imageVector = if (paused) Icons.Filled.PlayArrow else PauseIcon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(if (paused) R.string.player_ok_resume else R.string.player_ok_pause),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val badgeColor = Color.White.copy(alpha = 0.75f)
                    streamInfo.resolution?.let { Tag(text = it, color = badgeColor) }
                    streamInfo.fps?.let { Tag(text = stringResource(R.string.player_fps, Math.round(it)), color = badgeColor) }
                    streamInfo.videoCodec?.let { Tag(text = it, color = badgeColor) }
                    streamInfo.audioCodec?.let { codec ->
                        val channels = streamInfo.audioChannels
                        Tag(text = if (channels != null && channels > 2) stringResource(R.string.player_audio_channels, codec, channels) else codec, color = badgeColor)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = channel.selectedStreamName?.let { stringResource(R.string.player_stream_name, it) } ?: stringResource(R.string.player_stream_automatic),
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
                // Keys of the remote: buttons on a touch screen
                if (!Device.isTouch) {
                    Text(
                        text = stringResource(R.string.player_change_stream_hint),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                    Text(
                        text = stringResource(R.string.player_options_hint),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}

/** What the Chromecast shows on the TV: the channel, its current program and its logo. */
private fun Channel.castInfo() = CastInfo(title = name, subtitle = currentProgram?.title, artworkUrl = logoUrl)

/** Second channel in a corner, muted: its name, or why it does not play (no free provider connection). */
@Composable
private fun InsetVideo(channel: Channel, player: InsetPlayer, modifier: Modifier = Modifier) {
    val playing by player.playing.collectAsState()
    val failed by player.failed.collectAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth(INSET_WIDTH)
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
    ) {
        AndroidView(
            factory = { context -> TextureView(context).also { player.attach(it) } },
            onRelease = { player.detach(it) },
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = if (failed) stringResource(R.string.inset_failed, channel.name) else channel.name,
            fontSize = 12.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(if (playing) Alignment.BottomStart else Alignment.Center)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private const val INSET_WIDTH = 0.3f

/** "Paused · −2:05" or "Delayed −2:05": where the live channel is. */
@Composable
private fun TimeshiftBadge(state: TimeshiftState, modifier: Modifier = Modifier) {
    val behind = "−" + clockLabel(state.behindMs)
    Text(
        text = if (state.paused) stringResource(R.string.timeshift_badge_paused, behind) else stringResource(R.string.timeshift_badge_behind, behind),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

private const val TIMESHIFT_STEP_MS = 30_000L
private const val TIMESHIFT_POLL_MS = 1_000L
private const val STREAM_SWITCH_NOTICE_MS = 3_000L

// Material "Pause" icon (not in material-icons-core)
internal val PauseIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Pause", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes("M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z"), fill = SolidColor(Color.Black))
        .build()
}
