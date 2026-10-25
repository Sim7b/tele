package com.iptv.player.ui.screens

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
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.StreamSource
import com.iptv.player.playback.StreamAuth
import com.iptv.player.playback.StreamInfo
import com.iptv.player.playback.TvPlayerManager
import com.iptv.player.ui.components.ChannelListPanel
import com.iptv.player.ui.components.LogoPlate
import com.iptv.player.ui.components.ProgressLine
import com.iptv.player.ui.components.StreamPickerDialog
import com.iptv.player.ui.components.Tag
import com.iptv.player.ui.components.requestFocusWithRetry
import com.iptv.player.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    channel: Channel,
    channels: List<Channel>,
    playerManager: TvPlayerManager,
    streamAuth: StreamAuth? = null,
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
    // Incrémenté à chaque affichage demandé pour relancer le délai de masquage
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

    // Démarrage de la chaîne (et à chaque zapping / changement de flux)
    LaunchedEffect(streamUrl, streamAuth, userAgent) {
        playerManager.playStream(streamUrl, customUserAgent = userAgent, auth = streamAuth)
        revealOverlay()
    }

    // Masquage automatique de l'overlay au bout de 5 secondes
    LaunchedEffect(showOverlay, overlayToken) {
        if (showOverlay) {
            delay(5000)
            showOverlay = false
        }
    }

    // Arrêt du flux en quittant le lecteur : sinon le son continue derrière l'accueil
    DisposableEffect(Unit) {
        onDispose { playerManager.stop() }
    }

    // Application en arrière-plan (touche Home, mise en veille) : couper le flux, le reprendre au retour
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

    // Le lecteur capte les touches de la télécommande (zapping, OSD, lecture/pause, choix du flux)
    LaunchedEffect(refocusToken) {
        focusRequester.requestFocusWithRetry()
    }

    BackHandler {
        onBack()
    }

    // Déclaré après : prioritaire tant que le volet des chaînes est ouvert
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
                // Volet ouvert : ses lignes gèrent la navigation (haut/bas ne doivent plus zapper)
                if (showChannelPanel) return@onKeyEvent false
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                when (keyEvent.nativeKeyEvent.keyCode) {
                    // Zapping direct (CH+ = chaîne suivante, CH- = chaîne précédente)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        onNextChannel()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        onPreviousChannel()
                        true
                    }
                    // OK : affiche le bandeau ; appui long : choix du flux
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (repeatCount == 1) showStreamPicker = true else if (repeatCount == 0) revealOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        showStreamPicker = true
                        true
                    }
                    // Flèche gauche : volet des chaînes avec le guide, par-dessus le direct
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
        // Vue vidéo ExoPlayer native (SurfaceView accélérée matériellement)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerManager.getPlayer()
                    useController = false
                    // Empêche l'écran de veille Android TV de recouvrir le direct
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

        // Spinner de chargement (mise en mémoire tampon / zapping)
        if (isLoading && errorMessage == null) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.85f),
                strokeWidth = 2.5.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
            )
        }

        // Erreur de lecture : message et piste pour en sortir
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
                    text = "Lecture impossible",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = errorMessage ?: "",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Maintenez OK pour choisir un autre flux",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Bandeau d'informations (OSD)
        AnimatedVisibility(
            visible = showOverlay && !showChannelPanel,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlayerOverlay(channel = channel, streamInfo = streamInfo)
        }

        // Volet des chaînes (flèche gauche) : la vidéo continue derrière
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
                        text = "DIRECT",
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
                            text = "Ensuite ${program.nextStartTime ?: ""}  ·  ${program.nextTitle}",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Informations techniques réelles et flux actif
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val badgeColor = Color.White.copy(alpha = 0.75f)
                    streamInfo.resolution?.let { Tag(text = it, color = badgeColor) }
                    streamInfo.fps?.let { Tag(text = "${Math.round(it)} FPS", color = badgeColor) }
                    streamInfo.videoCodec?.let { Tag(text = it, color = badgeColor) }
                    streamInfo.audioCodec?.let { codec ->
                        val channels = streamInfo.audioChannels
                        Tag(text = if (channels != null && channels > 2) "$codec ${channels}ch" else codec, color = badgeColor)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = channel.selectedStreamName?.let { "Flux : $it" } ?: "Flux automatique",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
                Text(
                    text = "Maintenir OK : changer de flux",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.35f)
                )
            }
        }
    }
}
