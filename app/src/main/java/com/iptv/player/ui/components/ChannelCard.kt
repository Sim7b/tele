package com.iptv.player.ui.components

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.StreamSource
import com.iptv.player.data.remote.ProgramPosterRepository
import com.iptv.player.ui.theme.*

private val FavoriteShape = RoundedCornerShape(10.dp)

/** Sous-titre de l'option "Automatique" du choix de flux, selon le type de source. */
const val DISPATCHARR_AUTOMATIC = "Dispatcharr choisit le flux et bascule en cas de panne"
private val CompactShape = RoundedCornerShape(10.dp)

/**
 * Vignette Favori : visuel 16:9 du programme en pleine carte, textes posés sur un voile dégradé.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoriteChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isMoving: Boolean = false,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val program = channel.currentProgram

    // Visuel EPG du serveur, sinon recherche externe (TVMaze) ; à défaut, le logo de la chaîne
    val posterUrl by produceState(initialValue = program?.posterUrl, program?.title, program?.posterUrl) {
        value = program?.posterUrl
        val title = program?.title
        if (value.isNullOrBlank() && title != null) {
            value = ProgramPosterRepository.getPosterUrl(title)
        }
    }
    val imageAlpha by animateFloatAsState(targetValue = if (isFocused) 1f else 0.82f, label = "favImageAlpha")

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        shape = ClickableSurfaceDefaults.shape(shape = FavoriteShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (isMoving) 1.1f else 1.06f),
        // En déplacement : contour d'accent permanent pour distinguer la vignette "soulevée"
        border = if (isMoving) {
            val movingBorder = Border(BorderStroke(3.dp, AccentCyan), shape = FavoriteShape)
            ClickableSurfaceDefaults.border(border = movingBorder, focusedBorder = movingBorder)
        } else {
            focusRing(FavoriteShape)
        },
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.Black.copy(alpha = 0.55f), 14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceCard,
            focusedContainerColor = SurfaceCard
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!posterUrl.isNullOrBlank()) {
                FadeInImage(
                    url = posterUrl,
                    contentDescription = program?.title,
                    modifier = Modifier.fillMaxSize(),
                    alpha = imageAlpha
                )
            } else {
                LogoBackdrop(channel = channel, modifier = Modifier.fillMaxSize())
            }

            // Voile pour la lisibilité des textes (transparent en haut, sombre en bas)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.38f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.9f)
                        )
                    )
            )

            if (!posterUrl.isNullOrBlank()) {
                LogoPlate(
                    logoUrl = channel.logoUrl,
                    name = channel.name,
                    corner = 5.dp,
                    padding = 3.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(width = 38.dp, height = 24.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 9.dp)
            ) {
                Text(
                    text = program?.title ?: channel.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = when {
                        program == null -> channel.group
                        !program.nextTitle.isNullOrBlank() ->
                            "${program.startTime} – ${program.endTime}  ·  Puis ${program.nextTitle}"
                        else -> "${program.startTime} – ${program.endTime}"
                    },
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (program != null) {
                ProgressLine(
                    progress = program.progress,
                    height = 3.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                )
            }
        }
    }
}

/** Fond de vignette sans visuel : logo de la chaîne sur plaque, sur un dégradé sobre. */
@Composable
private fun LogoBackdrop(channel: Channel, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(SurfaceCard, SurfaceDark))
        ),
        contentAlignment = Alignment.Center
    ) {
        LogoPlate(
            logoUrl = channel.logoUrl,
            name = channel.name,
            corner = 10.dp,
            padding = 8.dp,
            modifier = Modifier
                .offset(y = (-12).dp)
                .size(width = 76.dp, height = 48.dp)
        )
    }
}

/**
 * Carte compacte de la grille : logo, chaîne, programme en cours et progression.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CompactChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val program = channel.currentProgram

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CompactShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = focusRing(CompactShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceDark,
            focusedContainerColor = SurfaceCard
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoPlate(
                logoUrl = channel.logoUrl,
                name = channel.name,
                corner = 7.dp,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1.15f)
            )

            Spacer(modifier = Modifier.width(11.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = channel.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Programme et horaire sur la même ligne : le nom de la chaîne garde toute la largeur
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = program?.title ?: "Aucune information",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (program != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${program.startTime} – ${program.endTime}",
                            fontSize = 10.sp,
                            color = TextSecondary.copy(alpha = 0.75f),
                            maxLines = 1
                        )
                    }
                }

                if (program != null) {
                    Spacer(modifier = Modifier.height(5.dp))
                    ProgressLine(
                        progress = program.progress,
                        trackColor = TextSecondary.copy(alpha = 0.18f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Puce de catégorie : texte seul au repos, fond discret si sélectionnée, pleine au focus.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscreteCategoryChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)

    val contentColor = when {
        isFocused -> LocalAppColors.current.background
        isSelected -> TextPrimary
        else -> TextSecondary
    }

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) SurfaceCard else Color.Transparent,
            focusedContainerColor = TextPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(AccentCyan)
                )
                Spacer(modifier = Modifier.width(7.dp))
            }
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}

/**
 * Panneau de dialogue commun : sombre, arrondi, limité en hauteur à l'écran.
 *
 * Le menu s'ouvre sur un appui long : les répétitions et le relâchement de cette touche OK arrivent
 * ensuite dans le dialogue et valideraient le premier élément. On les ignore tant qu'un nouvel appui
 * n'a pas commencé dans le dialogue.
 */
@Composable
private fun DialogPanel(
    width: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    var confirmArmed by remember { mutableStateOf(false) }

    BoxWithConstraints {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .width(width)
                .heightIn(max = maxHeight * 0.92f)
                .clip(shape)
                .background(SurfaceDark)
                .border(1.dp, TextPrimary.copy(alpha = 0.08f), shape)
                .onPreviewKeyEvent { event ->
                    val keyCode = event.nativeKeyEvent.keyCode
                    if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER &&
                        keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
                    ) return@onPreviewKeyEvent false
                    when {
                        event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                            confirmArmed = true
                            false
                        }
                        !confirmArmed -> true
                        event.type == KeyEventType.KeyUp -> {
                            confirmArmed = false
                            false
                        }
                        else -> false
                    }
                }
                .padding(22.dp),
            content = content
        )
    }
}

@Composable
private fun DialogHeader(channel: Channel, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoPlate(
            logoUrl = channel.logoUrl,
            name = channel.name,
            modifier = Modifier.size(width = 60.dp, height = 44.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Menu contextuel (appui long sur une chaîne) : favoris, masquage et choix du flux.
 * Fenêtre Dialog : le focus reste dans le menu et la touche Retour le ferme.
 */
@Composable
fun ChannelActionMenuDialog(
    channel: Channel,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onHideChannel: () -> Unit,
    loadStreams: suspend () -> Result<List<StreamSource>>,
    onSelectStream: (StreamSource?) -> Unit,
    onDismiss: () -> Unit,
    onMove: (() -> Unit)? = null,
    automaticDescription: String = DISPATCHARR_AUTOMATIC
) {
    val firstItemFocus = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LaunchedEffect(Unit) {
            firstItemFocus.requestFocusWithRetry()
        }

        DialogPanel(width = 560.dp) {
            DialogHeader(
                channel = channel,
                subtitle = channel.currentProgram?.let { "${it.startTime} – ${it.endTime}  ·  ${it.title}" } ?: channel.group
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuRow(
                    label = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    onClick = {
                        onToggleFavorite()
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(firstItemFocus)
                )
                MenuRow(
                    label = "Masquer la chaîne",
                    icon = Icons.Filled.Close,
                    onClick = {
                        onHideChannel()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            if (onMove != null) {
                MenuRow(
                    label = "Déplacer dans les favoris",
                    icon = Icons.Filled.Menu,
                    onClick = {
                        onDismiss()
                        onMove()
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            SectionLabel(text = "Flux", modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))

            StreamList(
                channel = channel,
                loadStreams = loadStreams,
                onSelect = { stream ->
                    onSelectStream(stream)
                    onDismiss()
                },
                automaticDescription = automaticDescription,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

/**
 * Sélecteur de flux ouvert depuis le lecteur (appui long sur OK ou touche Menu).
 */
@Composable
fun StreamPickerDialog(
    channel: Channel,
    loadStreams: suspend () -> Result<List<StreamSource>>,
    onSelectStream: (StreamSource?) -> Unit,
    onDismiss: () -> Unit,
    automaticDescription: String = DISPATCHARR_AUTOMATIC
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DialogPanel(width = 560.dp) {
            DialogHeader(channel = channel, subtitle = "Choisir le flux de lecture")
            Spacer(modifier = Modifier.height(14.dp))
            StreamList(
                channel = channel,
                loadStreams = loadStreams,
                onSelect = { stream ->
                    onSelectStream(stream)
                    onDismiss()
                },
                requestInitialFocus = true,
                automaticDescription = automaticDescription,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

private sealed class StreamsState {
    object Loading : StreamsState()
    data class Loaded(val streams: List<StreamSource>) : StreamsState()
    data class Failed(val message: String) : StreamsState()
}

/**
 * Liste des flux : "Automatique" (Dispatcharr choisit et bascule en cas de panne) puis les flux réels.
 */
@Composable
private fun StreamList(
    channel: Channel,
    loadStreams: suspend () -> Result<List<StreamSource>>,
    onSelect: (StreamSource?) -> Unit,
    automaticDescription: String,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false
) {
    val state by produceState<StreamsState>(StreamsState.Loading, channel.id) {
        value = loadStreams().fold(
            onSuccess = { StreamsState.Loaded(it) },
            onFailure = { StreamsState.Failed(it.localizedMessage ?: "Flux indisponibles") }
        )
    }
    val selectedFocus = remember { FocusRequester() }
    val streams = (state as? StreamsState.Loaded)?.streams.orEmpty()
    val selectedIndex = streams.indexOfFirst { it.url == channel.selectedStreamUrl }

    // Focus initial sur le flux actif (ou "Automatique")
    if (requestInitialFocus) {
        LaunchedEffect(state is StreamsState.Loading) {
            selectedFocus.requestFocusWithRetry()
        }
    }

    TvLazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            val isSelected = channel.selectedStreamUrl == null
            MenuRow(
                label = "Automatique",
                subtitle = automaticDescription,
                onClick = { onSelect(null) },
                leading = { focused -> SelectionDot(selected = isSelected, focused = focused) },
                modifier = if (isSelected || selectedIndex == -1) Modifier.focusRequester(selectedFocus) else Modifier
            )
        }

        when (val current = state) {
            is StreamsState.Loading -> item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    CircularProgressIndicator(
                        color = TextSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Recherche des flux…", fontSize = 13.sp, color = TextSecondary)
                }
            }
            is StreamsState.Failed -> item {
                Text(
                    text = current.message,
                    fontSize = 13.sp,
                    color = LiveRed,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
            is StreamsState.Loaded -> {
                if (current.streams.isEmpty()) {
                    item {
                        Text(
                            text = "Aucun autre flux disponible pour cette chaîne",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
                items(current.streams) { stream ->
                    val isSelected = stream.url == channel.selectedStreamUrl
                    MenuRow(
                        label = stream.name,
                        subtitle = listOfNotNull(stream.provider, stream.details).joinToString("  ·  ").ifBlank { null },
                        onClick = { onSelect(stream) },
                        leading = { focused -> SelectionDot(selected = isSelected, focused = focused) },
                        trailing = { focused ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                stream.qualityTags.forEach { tag ->
                                    Tag(
                                        text = tag,
                                        color = if (focused) LocalAppColors.current.background else TextSecondary
                                    )
                                }
                            }
                        },
                        modifier = if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier
                    )
                }
            }
        }
    }
}

/**
 * Menu d'une catégorie (appui long sur sa puce) : la masquer de l'accueil, du volet et du zapping.
 */
@Composable
fun CategoryActionDialog(
    category: String,
    channelCount: Int,
    onHide: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstItemFocus = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LaunchedEffect(Unit) {
            firstItemFocus.requestFocusWithRetry()
        }

        DialogPanel(width = 460.dp) {
            Text(
                text = category,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$channelCount chaîne(s)  ·  réaffichable dans les paramètres",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            MenuRow(
                label = "Masquer cette catégorie",
                icon = Icons.Filled.Close,
                onClick = {
                    onHide()
                    onDismiss()
                },
                modifier = Modifier.focusRequester(firstItemFocus)
            )
            MenuRow(label = "Annuler", onClick = onDismiss)
        }
    }
}
