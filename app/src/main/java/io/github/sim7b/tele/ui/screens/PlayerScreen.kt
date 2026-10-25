package io.github.sim7b.tele.ui.screens

import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.R
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
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.playback.StreamInfo
import io.github.sim7b.tele.playback.TvPlayerManager
import io.github.sim7b.tele.ui.components.ChannelListPanel
import io.github.sim7b.tele.ui.components.LogoPlate
import io.github.sim7b.tele.ui.components.ProgressLine
import io.github.sim7b.tele.ui.components.StreamPickerDialog
import io.github.sim7b.tele.ui.components.Tag
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.*
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    channel: Channel,
    channels: List<Channel>,
    playerManager: TvPlayerManager,
    streamAuth: ApiKeyAuth? = null,
    userAgent: String? = null,
    loadStreams: suspend () -> Result<List<StreamSource>>,
    onSelectStream: (StreamSource?) -> Unit,
    onSelectChannel: (Channel) -> Unit,
    automaticStreamDescription: String,
    onBack: () -> Unit,
    onNextChannel: () -> Unit,
    onPreviousChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLoading by playerManager.isLoading.collectAsState()
    val errorMessage by playerManager.error.collectAsState()
    val streamInfo by playerManager.streamInfo.collectAsState()

    var showOverlay by remember { mutableStateOf(true) }
    // Incremented on every requested display to restart the hide delay
    var overlayToken by remember { mutableIntStateOf(0) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var showChannelPanel by remember { mutableStateOf(false) }
    var refocusToken by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val streamUrl = channel.activeStreamUrl
    val currentStreamUrl by rememberUpdatedState(streamUrl)
    val currentStreamAuth by rememberUpdatedState(streamAuth)
    val currentUserAgent by rememberUpdatedState(userAgent)

    fun revealOverlay() {
        showOverlay = true
        overlayToken++
    }

    // Channel start (and on every channel switch / stream change)
    LaunchedEffect(streamUrl, streamAuth, userAgent) {
        playerManager.playStream(streamUrl, customUserAgent = userAgent, auth = streamAuth)
        revealOverlay()
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
                Lifecycle.Event.ON_STOP -> {
                    stoppedInBackground = true
                    playerManager.stop()
                }
                Lifecycle.Event.ON_START -> if (stoppedInBackground) {
                    stoppedInBackground = false
                    playerManager.playStream(currentStreamUrl, customUserAgent = currentUserAgent, auth = currentStreamAuth)
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

    // Declared after: takes priority while the channel panel is open
    BackHandler(enabled = showChannelPanel) {
        showChannelPanel = false
        refocusToken++
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                // Panel open: its rows handle navigation (up/down must no longer switch channels)
                if (showChannelPanel) return@onKeyEvent false
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
                    // OK: shows the banner; long press: stream choice
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (repeatCount == 1) showStreamPicker = true else if (repeatCount == 0) revealOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        showStreamPicker = true
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
                    KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        revealOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        playerManager.togglePlayPause()
                        revealOverlay()
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Native ExoPlayer video view (hardware-accelerated SurfaceView)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerManager.getPlayer()
                    useController = false
                    // Prevents the Android TV screensaver from covering the live stream
                    keepScreenOn = true
                    isFocusable = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize()
        )

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
                Text(
                    text = stringResource(R.string.player_other_stream_hint),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Information banner (OSD)
        AnimatedVisibility(
            visible = showOverlay && !showChannelPanel,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlayerOverlay(channel = channel, streamInfo = streamInfo)
        }

        // Channel panel (left arrow): the video keeps playing behind
        AnimatedVisibility(
            visible = showChannelPanel,
            enter = fadeIn() + slideInHorizontally { -it / 4 },
            exit = fadeOut() + slideOutHorizontally { -it / 4 },
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            ChannelListPanel(
                channels = channels,
                playingChannelId = channel.id,
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerOverlay(channel: Channel, streamInfo: StreamInfo) {
    val program = channel.currentProgram

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
            .padding(start = 56.dp, end = 56.dp, top = 70.dp, bottom = 34.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            LogoPlate(
                logoUrl = channel.logoUrl,
                name = channel.name,
                corner = 10.dp,
                padding = 8.dp,
                modifier = Modifier.size(width = 92.dp, height = 64.dp)
            )

            Spacer(modifier = Modifier.width(22.dp))

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
                    fontSize = 26.sp,
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
                            modifier = Modifier.width(280.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = program.endTime, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                        if (!program.category.isNullOrBlank()) {
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

            Spacer(modifier = Modifier.width(24.dp))

            // Actual technical information and active stream
            Column(horizontalAlignment = Alignment.End) {
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
                Text(
                    text = stringResource(R.string.player_change_stream_hint),
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.35f)
                )
            }
        }
    }
}
