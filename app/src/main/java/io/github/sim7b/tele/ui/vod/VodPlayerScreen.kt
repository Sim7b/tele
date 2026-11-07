package io.github.sim7b.tele.ui.vod

import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.vod.VodLanguages
import io.github.sim7b.tele.data.remote.ApiKeyAuth
import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.data.storage.VodTrackPrefs
import io.github.sim7b.tele.playback.CastInfo
import io.github.sim7b.tele.playback.PictureFormat
import io.github.sim7b.tele.playback.StreamInfo
import io.github.sim7b.tele.playback.TrackChoice
import io.github.sim7b.tele.playback.TvPlayerManager
import io.github.sim7b.tele.ui.components.applyTeleStyle
import io.github.sim7b.tele.ui.components.CastButton
import io.github.sim7b.tele.ui.components.CastRemoteScreen
import io.github.sim7b.tele.ui.components.Device
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.LocalScreen
import io.github.sim7b.tele.ui.components.PlaybackOptionsPanel
import io.github.sim7b.tele.ui.components.PlaybackStatsOverlay
import io.github.sim7b.tele.ui.components.TouchSteps
import io.github.sim7b.tele.ui.components.blockTouches
import io.github.sim7b.tele.ui.components.onAnyTouch
import io.github.sim7b.tele.ui.components.playerGestures
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.components.touchClick
import io.github.sim7b.tele.ui.components.trackLabel
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import io.github.sim7b.tele.ui.theme.AccentCyan
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Date

/**
 * Movie or episode player, Plex-like. Controls hidden, ← / → seek (10 s back, 30 s forward, faster when held)
 * and OK shows the controls: title, end time, progress, jumps, play / pause, next episode, audio and subtitles.
 * Menu opens the playback options (tracks, speed, picture format). Paused, the picture dims under the summary;
 * in the credits of an episode, the next one starts after a countdown. The position is reported every few
 * seconds and when leaving, with the request it belongs to.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(
    request: VodPlayRequest,
    playerManager: TvPlayerManager,
    streamAuths: List<ApiKeyAuth>,
    userAgent: String?,
    onProgress: (request: VodPlayRequest, positionMs: Long, durationMs: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    next: VodPlayRequest? = null,
    onPlayNext: (VodPlayRequest) -> Unit = {},
    trackPrefs: VodTrackPrefs? = null,
    onTrackPrefsChange: (VodTrackPrefs) -> Unit = {},
    // Series: end of the intro learned when the user skips it by hand
    onIntroLearned: (Long) -> Unit = {},
    // Series: length of the end credits learned when the next episode is started by hand in them
    onCreditsLearned: (Long) -> Unit = {},
    pictureFormat: PictureFormat = PictureFormat.FIT,
    onPictureFormatChange: (PictureFormat) -> Unit = {},
    subtitles: VodSubtitles? = null,
    // Another file of the movie or episode chosen while it plays: played from the same position
    onSwitchFile: (VodPlayRequest) -> Unit = {}
) {
    val isLoading by playerManager.isLoading.collectAsState()
    val error by playerManager.error.collectAsState()
    val tracks by playerManager.tracks.collectAsState()
    val streamInfo by playerManager.streamInfo.collectAsState()
    val showStats by playerManager.showStats.collectAsState()
    val reportProgress by rememberUpdatedState(onProgress)
    val currentNext by rememberUpdatedState(next)
    val currentPlayNext by rememberUpdatedState(onPlayNext)
    val currentBack by rememberUpdatedState(onBack)
    val exoPlayer = remember { playerManager.getPlayer() }
    // Phone / tablet casting: positions, pause and seeks go to the Chromecast
    val castDevice by playerManager.castDevice.collectAsState()
    val player: Player by rememberUpdatedState(if (castDevice != null) playerManager.controlPlayer() else exoPlayer)

    // Playback state read from the player (its positions are not observable)
    var positionMs by remember(request) { mutableLongStateOf(request.startMs) }
    var durationMs by remember(request) { mutableLongStateOf(0L) }
    var bufferedMs by remember(request) { mutableLongStateOf(0L) }
    var paused by remember(request) { mutableStateOf(false) }
    var speed by remember(request) { mutableFloatStateOf(1f) }

    var showControls by remember(request) { mutableStateOf(true) }
    var controlsToken by remember { mutableIntStateOf(0) }
    var showOptions by remember { mutableStateOf(false) }
    var showSubtitleSearch by remember { mutableStateOf(false) }
    var pickingFile by remember { mutableStateOf(false) }
    // Position chosen with the arrows, applied when the key is released
    var seekTarget by remember(request) { mutableStateOf<Long?>(null) }
    // Progress bar alone, while seeking with the controls hidden
    var showSeekBar by remember { mutableStateOf(false) }
    var seekBarToken by remember { mutableIntStateOf(0) }
    var upNextDismissed by remember(request) { mutableStateOf(false) }
    var countdown by remember(request) { mutableIntStateOf(UP_NEXT_COUNTDOWN_S) }
    // Next episode or sheet already asked for: the end of the stream must not ask again
    var leaving by remember(request) { mutableStateOf(false) }
    var refocusToken by remember { mutableIntStateOf(0) }
    // End of the intro of the series, learned from the previous episodes (or this one)
    var learnedIntro by remember(request.titleKey) { mutableStateOf(trackPrefs?.introEndMs) }
    // Learned in this episode: the jumps right after it adjust it
    var learnedThisTime by remember(request.episodeKey) { mutableStateOf<Long?>(null) }
    // Length of the end credits of the series: from then on, the next episode is offered
    var learnedCreditsMs by remember(request.titleKey) { mutableStateOf(trackPrefs?.creditsMs) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }

    fun report(playing: VodPlayRequest) {
        val duration = player.duration
        if (duration > 0 && player.currentPosition > 0) reportProgress(playing, player.currentPosition, duration)
    }

    fun revealControls() {
        showSeekBar = false
        showControls = true
        controlsToken++
    }

    fun togglePause() {
        if (player.playWhenReady) player.pause() else player.play()
        paused = !player.playWhenReady
    }

    fun scrub(forward: Boolean, repeatCount: Int) {
        val target = (seekTarget ?: player.currentPosition) + seekStep(forward, repeatCount)
        seekTarget = if (durationMs > 0) target.coerceIn(0, durationMs) else target.coerceAtLeast(0)
    }

    fun commitSeek() {
        seekTarget?.let {
            if (request.episodeKey != null) {
                learnedIntroEnd(player.currentPosition, it, durationMs, learnedThisTime)?.let { end ->
                    learnedThisTime = end
                    learnedIntro = end
                    onIntroLearned(end)
                }
            }
            player.seekTo(it)
            positionMs = it
        }
        seekTarget = null
    }

    fun jump(delta: Long) {
        val target = player.currentPosition + delta
        player.seekTo(if (durationMs > 0) target.coerceIn(0, durationMs) else target.coerceAtLeast(0))
    }

    fun playNext() {
        val upcoming = currentNext ?: return
        if (leaving) return
        leaving = true
        // Started by hand in the credits, before they were known: their length, for the next episodes
        if (request.episodeKey != null && !inCredits(player.currentPosition, durationMs, learnedCreditsMs)) {
            learnedCredits(player.currentPosition, durationMs)?.let { credits ->
                learnedCreditsMs = credits
                onCreditsLearned(credits)
            }
        }
        currentPlayNext(upcoming)
    }

    // Arrows, OK and media keys of the progress bar and of the hidden controls: true when handled
    fun seekKeys(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val forward = when (native.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> false
            else -> return false
        }
        if (durationMs <= 0) return true
        when (event.type) {
            KeyEventType.KeyDown -> scrub(forward, native.repeatCount)
            KeyEventType.KeyUp -> commitSeek()
        }
        return true
    }

    DisposableEffect(request) {
        // Languages and speed of this title only: the live channels keep their own
        val saved = exoPlayer.trackSelectionParameters
        onDispose {
            report(request)
            playerManager.stop()
            exoPlayer.trackSelectionParameters = saved
            exoPlayer.setPlaybackSpeed(1f)
        }
    }
    LaunchedEffect(request) {
        // With the subtitles downloaded for it before (read on the device, no request)
        val downloaded = subtitles?.cached?.invoke(request.itemKey).orEmpty()
        playerManager.playVod(
            request.url, request.startMs, userAgent, streamAuths, request.fallbacks, downloaded,
            info = CastInfo(title = request.title, subtitle = request.episodeTitle, artworkUrl = request.posterUrl ?: request.imageUrl)
        )
        trackPrefs?.let { playerManager.applyTrackPreferences(it.audio, it.subtitlesOn, it.subtitles) }
    }
    LaunchedEffect(request) {
        var sinceReport = 0L
        while (isActive) {
            positionMs = player.currentPosition
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            bufferedMs = player.bufferedPosition
            paused = !player.playWhenReady
            if (player.playbackState == Player.STATE_ENDED && !leaving) {
                // End of the episode: the next one, unless it was declined; end of a movie: back to its sheet
                if (currentNext != null && !upNextDismissed) {
                    playNext()
                } else {
                    leaving = true
                    report(request)
                    currentBack()
                }
            }
            delay(POLL_MS)
            sinceReport += POLL_MS
            if (sinceReport >= PROGRESS_INTERVAL_MS) {
                sinceReport = 0
                report(request)
            }
        }
    }

    val upNext = next != null && !upNextDismissed && error == null && seekTarget == null && inCredits(positionMs, durationMs, learnedCreditsMs)
    // Opening minutes of an episode: OK skips the intro
    val introTarget = if (request.episodeKey != null && !upNext && error == null && seekTarget == null && castDevice == null) {
        introSkipTarget(positionMs, durationMs, learnedIntro)
    } else {
        null
    }
    fun skipIntro() {
        val target = introTarget ?: return
        player.seekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
        positionMs = target
    }
    // Countdown of the next episode, stopped while paused
    LaunchedEffect(upNext) {
        if (!upNext) return@LaunchedEffect
        showControls = false
        showOptions = false
        countdown = UP_NEXT_COUNTDOWN_S
        while (countdown > 0) {
            delay(1_000)
            if (player.playWhenReady) countdown--
        }
        playNext()
    }

    LaunchedEffect(showControls, controlsToken) {
        if (showControls) {
            delay(CONTROLS_TIMEOUT_MS)
            if (seekTarget == null) showControls = false
        }
    }
    LaunchedEffect(showSeekBar, seekBarToken) {
        if (showSeekBar) {
            delay(SEEK_BAR_TIMEOUT_MS)
            if (seekTarget == null) showSeekBar = false
        }
    }
    // The controls, the next episode card or the screen itself hold the focus (the options panel takes its own)
    LaunchedEffect(showControls, showOptions, showSubtitleSearch, upNext, refocusToken) {
        when {
            showOptions || showSubtitleSearch -> Unit
            upNext -> upNextFocus.requestFocusWithRetry()
            showControls -> playFocus.requestFocusWithRetry()
            else -> rootFocus.requestFocusWithRetry()
        }
    }

    BackHandler {
        leaving = true
        report(request)
        onBack()
    }
    // Declared after: Back first hides the controls, or declines the next episode
    BackHandler(enabled = showControls) { showControls = false }
    BackHandler(enabled = showSubtitleSearch) {
        showSubtitleSearch = false
        refocusToken++
    }
    BackHandler(enabled = upNext) {
        upNextDismissed = true
        refocusToken++
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent {
                if (showControls) controlsToken++
                false
            }
            // Touch: the controls stay while they are used
            .onAnyTouch { if (showControls) controlsToken++ }
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { event ->
                if (showOptions || showSubtitleSearch) return@onKeyEvent false
                val native = event.nativeKeyEvent
                val down = event.type == KeyEventType.KeyDown
                val up = event.type == KeyEventType.KeyUp
                // Media keys of the remote, whatever is shown
                when (native.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (down && native.repeatCount == 0) {
                            togglePause()
                            revealControls()
                        }
                        return@onKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (down) playNext()
                        return@onKeyEvent true
                    }
                    // Menu (on the remotes that send it to apps): the playback options
                    KeyEvent.KEYCODE_MENU -> {
                        if (down && native.repeatCount == 0 && !upNext) {
                            showControls = false
                            showOptions = true
                        }
                        return@onKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (!showControls) {
                            showSeekBar = true
                            seekBarToken++
                        }
                        return@onKeyEvent seekKeys(event)
                    }
                }
                if (showControls || upNext) return@onKeyEvent false
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (error == null) {
                            showSeekBar = true
                            seekBarToken++
                            seekKeys(event)
                        }
                        true
                    }
                    // Shown on release: the key up must not reach the button that takes the focus
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                        if (up) {
                            val ok = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == KeyEvent.KEYCODE_ENTER
                            when {
                                ok && introTarget != null -> skipIntro()
                                error != null && native.keyCode != KeyEvent.KEYCODE_INFO -> playerManager.retry()
                                else -> revealControls()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Phone / tablet casting: what plays on the Chromecast instead of the picture, this screen is the remote
        castDevice?.let { device ->
            CastRemoteScreen(
                device = device,
                title = request.title,
                subtitle = episodeLine(request),
                imageUrl = request.posterUrl ?: request.imageUrl,
                hint = stringResource(R.string.cast_remote_hint_vod)
            )
        }
        if (castDevice == null) AndroidView(
            factory = { context ->
                PlayerView(context).also { view ->
                    view.player = exoPlayer
                    view.useController = false
                    view.keepScreenOn = true
                    view.isFocusable = false
                    view.subtitleView?.applyTeleStyle()
                }
            },
            update = { view ->
                view.resizeMode = pictureFormat.resizeMode
                // Subtitles above the controls while they are shown
                view.subtitleView?.setBottomPaddingFraction(
                    if (showControls) SUBTITLES_ABOVE_CONTROLS else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
                )
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize()
        )
        // Phone / tablet: tap for the controls (or to close a panel), double tap on a side to jump back or
        // forward, in the middle to pause
        if (Device.isTouch) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerGestures(
                        onTap = {
                            when {
                                showOptions || showSubtitleSearch -> {
                                    showOptions = false
                                    showSubtitleSearch = false
                                    refocusToken++
                                }
                                error != null -> playerManager.retry()
                                showControls -> showControls = false
                                else -> revealControls()
                            }
                        },
                        onDoubleTap = { side ->
                            if (error == null && !showOptions && !showSubtitleSearch) {
                                when (side) {
                                    TouchSteps.Side.LEFT -> jump(-10_000)
                                    TouchSteps.Side.RIGHT -> jump(30_000)
                                    TouchSteps.Side.CENTER -> togglePause()
                                }
                                if (!showControls) {
                                    showSeekBar = true
                                    seekBarToken++
                                }
                            }
                        }
                    )
            )
        }
        if (isLoading && error == null && seekTarget == null) {
            CircularProgressIndicator(color = AccentCyan, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(42.dp))
        }

        AnimatedVisibility(
            visible = paused && !showControls && !showOptions && !showSubtitleSearch && !showSeekBar && error == null && !upNext,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            PauseScreen(request, positionMs, durationMs)
        }

        AnimatedVisibility(visible = showControls && !showOptions && !showSubtitleSearch && !upNext, enter = fadeIn(), exit = fadeOut()) {
            val audio = tracks.audio.firstOrNull { it.selected }
            val subtitle = tracks.subtitles.firstOrNull { it.selected }.takeIf { tracks.subtitlesOn }
            Controls(
                request = request,
                positionMs = seekTarget ?: positionMs,
                bufferedMs = bufferedMs,
                durationMs = durationMs,
                seeking = seekTarget != null,
                speed = speed,
                paused = paused,
                hasNext = next != null,
                audio = audio,
                subtitle = subtitle,
                streamInfo = streamInfo,
                playFocus = playFocus,
                onSeekKey = ::seekKeys,
                onTogglePause = ::togglePause,
                onJump = ::jump,
                onNext = ::playNext,
                onOptions = {
                    showControls = false
                    showOptions = true
                },
                onBack = {
                    leaving = true
                    report(request)
                    onBack()
                },
                // Touch: the bar follows the finger, the position is applied when it lifts
                onSeekTo = { position -> seekTarget = position },
                onSeekDone = ::commitSeek,
                castButton = { CastButton(playerManager, size = 44.dp) }
            )
        }

        AnimatedVisibility(
            visible = showSeekBar && !showControls && !upNext,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(start = screenMargin(48.dp), end = screenMargin(48.dp), top = 40.dp, bottom = 32.dp)
            ) {
                ProgressRow(seekTarget ?: positionMs, bufferedMs, durationMs, seeking = seekTarget != null, focused = false)
            }
        }

        AnimatedVisibility(
            visible = introTarget != null && !showControls && !showOptions && !showSubtitleSearch,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Pill(
                text = stringResource(R.string.vod_player_skip_intro),
                onClick = ::skipIntro,
                modifier = Modifier.padding(end = 48.dp, bottom = 40.dp)
            )
        }

        AnimatedVisibility(
            visible = upNext,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            next?.let { upcoming ->
                UpNextCard(
                    next = upcoming,
                    countdown = countdown,
                    playFocus = upNextFocus,
                    onPlay = ::playNext,
                    onCancel = {
                        upNextDismissed = true
                        refocusToken++
                    }
                )
            }
        }

        if (showStats && castDevice == null) PlaybackStatsOverlay(playerManager, modifier = Modifier.align(Alignment.TopStart))

        AnimatedVisibility(
            visible = showOptions,
            enter = fadeIn() + slideInHorizontally { it / 4 },
            exit = fadeOut() + slideOutHorizontally { it / 4 },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            fun savePrefs(audio: TrackChoice?, subtitle: TrackChoice?, subtitlesOn: Boolean) =
                onTrackPrefsChange(VodTrackPrefs(audio?.language, subtitle?.language, subtitlesOn))
            PlaybackOptionsPanel(
                tracks = tracks,
                pictureFormat = pictureFormat,
                onChooseStream = if (request.files.size > 1 && castDevice == null) ({
                    showOptions = false
                    pickingFile = true
                }) else null,
                chooseStreamLabel = stringResource(R.string.vod_file_choose_title),
                onSelectAudio = { choice ->
                    playerManager.selectAudio(choice)
                    savePrefs(choice, tracks.subtitles.firstOrNull { it.selected }, tracks.subtitlesOn)
                },
                onSelectSubtitles = { choice ->
                    playerManager.selectSubtitles(choice)
                    savePrefs(tracks.audio.firstOrNull { it.selected }, choice, choice != null)
                },
                onSelectPictureFormat = onPictureFormatChange,
                onDismiss = {
                    showOptions = false
                    refocusToken++
                },
                speed = speed,
                statsOn = if (castDevice == null) showStats else null,
                onToggleStats = { playerManager.toggleStats() },
                onSelectSpeed = { value ->
                    speed = value
                    player.setPlaybackSpeed(value)
                },
                onSearchSubtitles = if (subtitles?.canSearch == true) {
                    {
                        showOptions = false
                        showSubtitleSearch = true
                    }
                } else {
                    null
                }
            )
        }

        // Subtitles from OpenSubtitles: searched when the panel opens, added to the stream when chosen
        AnimatedVisibility(
            visible = showSubtitleSearch && subtitles != null,
            enter = fadeIn() + slideInHorizontally { it / 4 },
            exit = fadeOut() + slideOutHorizontally { it / 4 },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            val source = subtitles ?: return@AnimatedVisibility
            SidePanel(title = stringResource(R.string.subtitles_search_title)) {
                SubtitleSearchList(
                    search = { all -> source.search(request.subtitleQuery, all) },
                    download = { result ->
                        val subtitle = source.download(request.itemKey, result)
                        playerManager.addSubtitle(subtitle)
                        onTrackPrefsChange(VodTrackPrefs(tracks.audio.firstOrNull { it.selected }?.language, subtitle.language, true))
                    },
                    onDownloaded = {
                        showSubtitleSearch = false
                        refocusToken++
                    },
                    remaining = source.remaining,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        error?.let { message ->
            Column(modifier = Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = request.label, fontSize = 18.sp, color = Color.White)
                Text(text = message.resolve(), fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 8.dp))
                if (Device.isTouch) {
                    Pill(text = stringResource(R.string.home_retry), onClick = { playerManager.retry() }, modifier = Modifier.padding(top = 14.dp))
                } else {
                    Text(text = stringResource(R.string.vod_player_retry_hint), fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
    }

    // Another file of the movie or episode (playback options): played from where it is
    if (pickingFile) {
        val playing = request.files.firstOrNull { it.url == request.url } ?: request.files.first()
        FilePickerDialog(
            files = request.files,
            selected = playing,
            estimated = VodLanguages(),
            onSelect = { file ->
                pickingFile = false
                refocusToken++
                if (file.url != request.url) {
                    val position = player.currentPosition.coerceAtLeast(0)
                    report(request)
                    leaving = true
                    onSwitchFile(
                        request.copy(
                            url = file.url,
                            sourceId = file.sourceId,
                            fallbacks = request.files.filter { it !== file }.map { it.url },
                            startMs = position
                        )
                    )
                }
            },
            onDismiss = {
                pickingFile = false
                refocusToken++
            }
        )
    }
}

/** Panel on the right of the picture, like the playback options. */
@Composable
private fun SidePanel(title: String, content: @Composable () -> Unit) {
    // Phone: the whole width, opaque
    val compact = LocalScreen.current.compact
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(520.dp))
            .background(
                if (compact) SolidColor(Color.Black.copy(alpha = 0.94f))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.94f)))
            )
            // Touch: taps here stay in the panel (beside it, they close it)
            .blockTouches()
            .padding(start = if (compact) 16.dp else 60.dp, end = screenMargin(32.dp), top = 36.dp, bottom = 24.dp)
    ) {
        Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(start = 14.dp, bottom = 10.dp))
        content()
    }
}

/** Top band (title, episode, clock and end time) and bottom band (progress, tracks, buttons). */
@Composable
private fun Controls(
    request: VodPlayRequest,
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    seeking: Boolean,
    speed: Float,
    paused: Boolean,
    hasNext: Boolean,
    audio: TrackChoice?,
    subtitle: TrackChoice?,
    streamInfo: StreamInfo,
    playFocus: FocusRequester,
    onSeekKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
    onTogglePause: () -> Unit,
    onJump: (Long) -> Unit,
    onNext: () -> Unit,
    onOptions: () -> Unit,
    // Touch: Back, and the bar moved with the finger (position while it moves, then applied)
    onBack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekDone: () -> Unit,
    // Touch: the Chromecast button, beside the title
    castButton: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val now = System.currentTimeMillis()
    // Phone: the buttons above the tracks and the options, smaller titles
    val compact = LocalScreen.current.compact
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                .padding(start = screenMargin(48.dp), end = screenMargin(48.dp), top = 28.dp, bottom = 48.dp)
        ) {
            if (Device.isTouch) {
                RoundButton(onClick = onBack, label = stringResource(R.string.settings_back), size = 44) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = request.title, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                episodeLine(request)?.let {
                    Text(text = it, fontSize = 16.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (Device.isTouch) {
                castButton()
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = timeFormat.format(Date(now)), fontSize = 20.sp, color = Color.White)
                if (durationMs > 0) {
                    val endsAt = now + ((durationMs - positionMs).coerceAtLeast(0) / speed).toLong()
                    Text(
                        text = stringResource(R.string.vod_player_ends_at, timeFormat.format(Date(endsAt))),
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = screenMargin(48.dp), end = screenMargin(48.dp), top = 56.dp, bottom = if (compact) 20.dp else 28.dp)
        ) {
            // The bar takes the focus with ↑: ← / → then seek, OK pauses
            val barInteraction = remember { MutableInteractionSource() }
            val barFocused by barInteraction.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .onKeyEvent { event ->
                        if (onSeekKey(event)) return@onKeyEvent true
                        val code = event.nativeKeyEvent.keyCode
                        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && event.type == KeyEventType.KeyUp) {
                            onTogglePause()
                            true
                        } else {
                            false
                        }
                    }
                    .focusable(interactionSource = barInteraction)
            ) {
                ProgressRow(
                    positionMs, bufferedMs, durationMs, seeking, focused = barFocused,
                    onSeekTo = onSeekTo.takeIf { Device.isTouch },
                    onSeekDone = onSeekDone
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            val tracks: @Composable () -> Unit = {
                if (audio != null) Pill(text = trackShortLabel(audio, locale, isAudio = true), onClick = onOptions)
                Pill(
                    text = subtitle?.let { trackShortLabel(it, locale, isAudio = false) }
                        ?: stringResource(R.string.vod_player_subtitles_off),
                    onClick = onOptions
                )
            }
            val buttons: @Composable () -> Unit = {
                RoundButton(onClick = { onJump(-10_000) }, label = stringResource(R.string.vod_player_back_10)) {
                    Text(text = "−10", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                RoundButton(
                    onClick = onTogglePause,
                    label = stringResource(if (paused) R.string.vod_player_play else R.string.vod_player_pause),
                    size = 64,
                    modifier = Modifier.focusRequester(playFocus)
                ) {
                    if (paused) Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(34.dp)) else PauseGlyph()
                }
                RoundButton(onClick = { onJump(30_000) }, label = stringResource(R.string.vod_player_forward_30)) {
                    Text(text = "+30", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                if (hasNext) {
                    RoundButton(onClick = onNext, label = stringResource(R.string.vod_player_next_episode)) { NextGlyph() }
                }
            }
            val extras: @Composable () -> Unit = {
                qualityLabel(streamInfo)?.let { quality ->
                    Text(
                        text = quality,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                RoundButton(onClick = onOptions, label = stringResource(R.string.options_title), size = 44) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
            if (compact) {
                // Phone: the buttons in the middle, the tracks and the options under them
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) { buttons() }
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) { tracks() }
                    extras()
                }
            } else {
                // Three parts: tracks, buttons, quality and options; the sides never cover the buttons
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) { tracks() }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) { buttons() }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) { extras() }
                }
            }
        }
    }
}

/** Elapsed time, bar (buffered and played parts, thumb and the chosen position while seeking), time left. */
@Composable
private fun ProgressRow(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    seeking: Boolean,
    focused: Boolean,
    // Touch: the position under the finger (tap or drag on the bar), then applied
    onSeekTo: ((Long) -> Unit)? = null,
    onSeekDone: () -> Unit = {}
) {
    val seekTo by rememberUpdatedState(onSeekTo)
    val seekDone by rememberUpdatedState(onSeekDone)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = positionLabel(positionMs), fontSize = 15.sp, color = Color.White, modifier = Modifier.widthIn(min = 64.dp))
        if (durationMs > 0) {
            BoxWithConstraints(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .padding(horizontal = 14.dp)
                    .then(
                        if (onSeekTo == null) Modifier else Modifier
                            .pointerInput(durationMs) {
                                detectTapGestures { offset ->
                                    seekTo?.invoke(TouchSteps.seekPosition(offset.x, size.width.toFloat(), durationMs))
                                    seekDone()
                                }
                            }
                            .pointerInput(durationMs) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset -> seekTo?.invoke(TouchSteps.seekPosition(offset.x, size.width.toFloat(), durationMs)) },
                                    onDragEnd = { seekDone() },
                                    onDragCancel = { seekDone() }
                                ) { change, _ ->
                                    change.consume()
                                    seekTo?.invoke(TouchSteps.seekPosition(change.position.x, size.width.toFloat(), durationMs))
                                }
                            }
                    )
            ) {
                val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                val buffered = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
                val barHeight = if (focused || seeking) 6.dp else 4.dp
                Box(modifier = Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f)))
                Box(modifier = Modifier.fillMaxWidth(buffered).height(barHeight).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f)))
                Box(modifier = Modifier.fillMaxWidth(fraction).height(barHeight).clip(RoundedCornerShape(50)).background(AccentCyan))
                if (focused || seeking) {
                    val thumb = 16.dp
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * fraction - thumb / 2)
                            .size(thumb)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
            Text(text = "−" + positionLabel(durationMs - positionMs), fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f))
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** Paused: the picture dims under the poster, the title and its summary. */
@Composable
private fun PauseScreen(request: VodPlayRequest, positionMs: Long, durationMs: Long) {
    // Phone: the summary alone, the poster takes too much room
    val compact = LocalScreen.current.compact
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.45f))))
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = screenMargin(56.dp), end = screenMargin(56.dp), bottom = if (compact) 32.dp else 56.dp)
        ) {
            if (request.posterUrl != null && !compact) {
                FadeInImage(
                    url = request.posterUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .width(140.dp)
                        .height(210.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(28.dp))
            }
            Column(modifier = Modifier.widthIn(max = 640.dp)) {
                Text(
                    text = stringResource(R.string.vod_player_paused).uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    color = AccentCyan
                )
                Text(text = request.title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                episodeLine(request)?.let {
                    Text(text = it, fontSize = 17.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
                }
                request.plot?.let {
                    Text(text = it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
                }
                if (durationMs > 0) {
                    Text(
                        text = positionLabel(positionMs) + " / " + positionLabel(durationMs),
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

/** Next episode offered in the credits, started at the end of the countdown. */
@Composable
private fun UpNextCard(next: VodPlayRequest, countdown: Int, playFocus: FocusRequester, onPlay: () -> Unit, onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(
                // Phone: across the bottom of the screen
                if (LocalScreen.current.compact) Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).fillMaxWidth()
                else Modifier.padding(end = 48.dp, bottom = 40.dp).width(520.dp)
            )
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .width(if (LocalScreen.current.compact) 120.dp else 180.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            FadeInImage(url = next.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.vod_player_up_next, countdown), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            Text(
                text = episodeLine(next) ?: next.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(text = stringResource(R.string.vod_player_play_now), onClick = onPlay, icon = true, modifier = Modifier.focusRequester(playFocus))
                Pill(text = stringResource(R.string.common_cancel), onClick = onCancel)
            }
        }
    }
}

/** "S1 · E3 — Title" for an episode, null for a movie. */
@Composable
private fun episodeLine(request: VodPlayRequest): String? {
    val season = request.season ?: return null
    val number = request.episode ?: return null
    val line = stringResource(R.string.vod_player_episode_line, season, number)
    return request.episodeTitle?.let { "$line — $it" } ?: line
}

/** "1080p · HEVC" from what the player reads of the stream. */
private fun qualityLabel(info: StreamInfo): String? {
    val width = info.resolution?.substringBefore('x')?.toIntOrNull()
    val height = info.resolution?.substringAfter('x')?.toIntOrNull()
    return listOfNotNull(videoFormatLabel(width, height), info.videoCodec).joinToString(" · ").ifEmpty { null }
}

/**
 * Language of a track for the buttons ("Français · 5.1"): its language rather than its label,
 * which often holds the whole file name.
 */
@Composable
private fun trackShortLabel(choice: TrackChoice, locale: java.util.Locale, isAudio: Boolean): String =
    if (choice.language != null) trackLabel(choice.copy(label = null), locale, isAudio) else trackLabel(choice, locale, isAudio)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoundButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 52,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size.dp)
            .semantics { contentDescription = label }
            .touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
            if (icon) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
}

/** Two bars, drawn in the content color of the button. */
@Composable
private fun PauseGlyph() {
    val color = androidx.tv.material3.LocalContentColor.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { Box(modifier = Modifier.width(6.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(color)) }
    }
}

/** Play triangle against a bar. */
@Composable
private fun NextGlyph() {
    val color = androidx.tv.material3.LocalContentColor.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(26.dp))
        Box(modifier = Modifier.offset(x = (-5).dp).width(3.dp).height(16.dp).background(color))
    }
}

private const val POLL_MS = 500L
private const val PROGRESS_INTERVAL_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 5_000L
private const val SEEK_BAR_TIMEOUT_MS = 2_500L
private const val UP_NEXT_COUNTDOWN_S = 15
private const val SUBTITLES_ABOVE_CONTROLS = 0.3f
