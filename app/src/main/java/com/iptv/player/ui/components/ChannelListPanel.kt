package com.iptv.player.ui.components

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.player.data.model.Channel
import com.iptv.player.ui.theme.AccentCyan
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val ALL_CHANNELS = "Toutes les chaînes"
private const val AUTO_HIDE_MS = 20_000L
private val PanelWidth = 430.dp
private val FadeWidth = 140.dp

/**
 * Volet des chaînes affiché par-dessus le direct (flèche gauche dans le lecteur), à la manière de TiviMate :
 * la vidéo continue derrière un dégradé transparent, chaque ligne montre le programme en cours et sa
 * progression, la chaîne focalisée déplie sa catégorie et le programme suivant.
 * ◀ ▶ change de catégorie, OK zappe, Retour ou l'inactivité referment le volet.
 */
@Composable
fun ChannelListPanel(
    channels: List<Channel>,
    playingChannelId: String,
    onSelectChannel: (Channel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = remember(channels) { listOf(ALL_CHANNELS) + channels.map { it.group }.distinct() }
    var categoryIndex by remember { mutableIntStateOf(0) }
    val category = categories.getOrElse(categoryIndex) { ALL_CHANNELS }
    val shown = remember(channels, category) {
        if (category == ALL_CHANNELS) channels else channels.filter { it.group == category }
    }

    // La chaîne en cours (ou la première de la catégorie) reçoit le focus à l'ouverture
    val focusTargetId = shown.firstOrNull { it.id == playingChannelId }?.id ?: shown.firstOrNull()?.id
    val focusTargetIndex = shown.indexOfFirst { it.id == focusTargetId }.coerceAtLeast(0)
    val listState = rememberTvLazyListState(initialFirstVisibleItemIndex = (focusTargetIndex - 2).coerceAtLeast(0))
    val initialFocus = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(focusTargetIndex) }
    var interactions by remember { mutableIntStateOf(0) }

    // Retour automatique au plein écran après quelques secondes sans action
    LaunchedEffect(interactions) {
        delay(AUTO_HIDE_MS)
        onDismiss()
    }

    LaunchedEffect(category) {
        listState.scrollToItem((focusTargetIndex - 2).coerceAtLeast(0))
        initialFocus.requestFocusWithRetry()
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(PanelWidth + FadeWidth)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.9f),
                    0.62f to Color.Black.copy(alpha = 0.78f),
                    1f to Color.Transparent
                )
            )
            .onPreviewKeyEvent { event ->
                interactions++
                // Retour ferme le volet. Sans cela, Compose l'utilise d'abord pour sortir le focus
                // de la liste vers le lecteur, et il faut appuyer deux fois.
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.type == KeyEventType.KeyUp) onDismiss()
                    true
                } else {
                    false
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        categoryIndex = Math.floorMod(categoryIndex - 1, categories.size)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        categoryIndex = Math.floorMod(categoryIndex + 1, categories.size)
                        true
                    }
                    // Le focus ne doit pas quitter le volet par le haut ou le bas de la liste
                    KeyEvent.KEYCODE_DPAD_UP -> focusedIndex <= 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusedIndex >= shown.lastIndex
                    else -> false
                }
            }
    ) {
        Column(
            modifier = Modifier
                .width(PanelWidth)
                .fillMaxHeight()
        ) {
            PanelHeader(category = category, count = shown.size)

            TvLazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(shown, key = { _, channel -> channel.id }) { index, channel ->
                    PanelChannelRow(
                        channel = channel,
                        isPlaying = channel.id == playingChannelId,
                        onClick = { onSelectChannel(channel) },
                        modifier = Modifier
                            .then(if (channel.id == focusTargetId) Modifier.focusRequester(initialFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedIndex = index }
                    )
                }
            }

            Text(
                text = "◀ ▶  Catégorie      OK  Regarder      Retour  Fermer",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.38f),
                modifier = Modifier.padding(start = 30.dp, bottom = 22.dp, top = 6.dp)
            )
        }
    }
}

@Composable
private fun PanelHeader(category: String, count: Int) {
    val now by produceState(initialValue = LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(5_000)
        }
    }

    Column(modifier = Modifier.padding(start = 30.dp, end = 22.dp, top = 30.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Chaînes",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = category,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = "  ·  $count",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.45f)
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelChannelRow(
    channel: Channel,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val program = channel.currentProgram

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.13f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text(
                text = channel.number?.toString() ?: "",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = if (isFocused) 0.75f else 0.4f),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .width(30.dp)
                    .padding(top = 9.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            LogoPlate(
                logoUrl = channel.logoUrl,
                name = channel.name,
                corner = 6.dp,
                padding = 4.dp,
                modifier = Modifier.size(width = 54.dp, height = 36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(AccentCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = channel.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (program != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${program.startTime} – ${program.endTime}",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }
                }

                Text(
                    text = program?.title ?: "Aucune information",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (program != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ProgressLine(
                        progress = program.progress,
                        trackColor = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Détails dépliés sur la chaîne focalisée : catégorie et programme suivant (sans résumé)
                AnimatedVisibility(
                    visible = isFocused && program != null &&
                        (!program.category.isNullOrBlank() || !program.nextTitle.isNullOrBlank()),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        if (!program?.category.isNullOrBlank()) {
                            Text(
                                text = program?.category.orEmpty().uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp,
                                color = Color.White.copy(alpha = 0.45f)
                            )
                        }
                        if (!program?.nextTitle.isNullOrBlank()) {
                            Text(
                                text = "Puis ${program?.nextStartTime.orEmpty()}  ·  ${program?.nextTitle}",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
