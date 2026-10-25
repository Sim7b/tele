package com.iptv.player.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.StreamSource
import com.iptv.player.ui.components.CategoryActionDialog
import com.iptv.player.ui.components.ChannelActionMenuDialog
import com.iptv.player.ui.components.CompactChannelCard
import com.iptv.player.ui.components.DiscreteCategoryChip
import com.iptv.player.ui.components.FavoriteChannelCard
import com.iptv.player.ui.components.MenuRow
import com.iptv.player.ui.components.RoundIconButton
import com.iptv.player.ui.components.SectionLabel
import com.iptv.player.ui.components.requestFocusWithRetry
import com.iptv.player.ui.theme.*
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ALL_CATEGORIES = "Toutes"

private const val FAVORITE_SLOTS = 8
private const val FAVORITE_COLUMNS = 4

/**
 * Les 8 vignettes du haut : les favoris dans l'ordre choisi (même si leur catégorie est masquée),
 * complétés par les premières chaînes visibles.
 */
fun pickFavoriteChannels(
    allChannels: List<Channel>,
    visibleChannels: List<Channel>,
    favoriteIds: List<String>
): List<Channel> {
    val byId = allChannels.associateBy { it.id }
    val favorites = favoriteIds.mapNotNull { byId[it] }
    val filler = visibleChannels.filterNot { it.id in favoriteIds }
    return (favorites + filler).take(FAVORITE_SLOTS)
}

/**
 * Accueil : favoris, catégories et grille de chaînes.
 * [channels] ne contient que les chaînes visibles (chaînes et catégories masquées filtrées en amont),
 * [favorites] les 8 vignettes du haut (voir [pickFavoriteChannels]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    channels: List<Channel>,
    favorites: List<Channel>,
    activeConfig: SourceConfig?,
    favoriteIds: List<String> = emptyList(),
    isLoading: Boolean,
    loadError: String? = null,
    onChannelSelected: (Channel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onHideChannel: (String) -> Unit,
    loadStreams: suspend (Channel) -> Result<List<StreamSource>>,
    onSelectStream: (Channel, StreamSource?) -> Unit,
    onMoveFavorite: (channelId: String, toIndex: Int) -> Unit,
    onHideCategory: (String) -> Unit,
    automaticStreamDescription: String,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // États sauvegardés : restaurés au retour du lecteur ou des paramètres
    var selectedCategory by rememberSaveable { mutableStateOf(ALL_CATEGORIES) }
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberTvLazyListState()
    val categoriesState = rememberTvLazyListState()

    var actionMenuChannel by remember { mutableStateOf<Channel?>(null) }
    var categoryMenu by remember { mutableStateOf<String?>(null) }
    val restoreFocusRequester = remember { FocusRequester() }
    val emptyStateFocusRequester = remember { FocusRequester() }
    var focusRestoreRequest by remember { mutableIntStateOf(0) }

    // Favoris réels en tête de grille (les vignettes suivantes ne font que compléter les 8 places)
    val favoriteChannels = favorites
    val movableCount = remember(favorites, favoriteIds) { favorites.takeWhile { it.id in favoriteIds }.size }
    val hasFavorites = movableCount > 0

    // Mode "déplacer" : la vignette choisie suit les flèches, OK ou Retour valide
    var movingChannelId by remember { mutableStateOf<String?>(null) }
    val movingFocus = remember { FocusRequester() }
    LaunchedEffect(movingChannelId, favorites) {
        if (movingChannelId != null) movingFocus.requestFocusWithRetry()
    }

    fun moveBy(step: Int) {
        val movingId = movingChannelId ?: return
        val from = favorites.indexOfFirst { it.id == movingId }
        val to = from + step
        if (from == -1 || to < 0 || to >= movableCount) return
        onMoveFavorite(movingId, favoriteIds.indexOf(favorites[to].id))
    }

    // Catégories
    val categories = remember(channels) {
        listOf(ALL_CATEGORIES) + channels.map { it.group }.distinct()
    }

    // Une catégorie disparue (chaînes masquées, changement de serveur) ne doit pas laisser une liste vide
    val effectiveCategory = selectedCategory.takeIf { it in categories } ?: ALL_CATEGORIES

    // Chaînes filtrées
    val filteredChannels = remember(channels, effectiveCategory) {
        if (effectiveCategory == ALL_CATEGORIES) channels else channels.filter { it.group == effectiveCategory }
    }
    // Rangées de 4 calculées une fois par changement de liste (et non à chaque recomposition de la grille)
    val channelRows = remember(filteredChannels) { filteredChannels.chunked(4) }

    // Élément qui reçoit le focus à l'affichage : le dernier focalisé, sinon le premier favori
    val focusTargetKey = remember(favoriteChannels, filteredChannels, lastFocusedKey) {
        val keys = favoriteChannels.map { "fav:${it.id}" } + filteredChannels.map { "list:${it.id}" }
        lastFocusedKey?.takeIf { it in keys } ?: keys.firstOrNull()
    }
    val hasContent = channels.isNotEmpty() || favorites.isNotEmpty()

    LaunchedEffect(focusRestoreRequest, hasContent) {
        // En déplacement, c'est la vignette déplacée qui garde le focus
        if (movingChannelId != null) return@LaunchedEffect
        if (hasContent) {
            restoreFocusRequester.requestFocusWithRetry()
        } else {
            emptyStateFocusRequester.requestFocusWithRetry()
        }
    }

    fun Modifier.trackFocus(key: String): Modifier = this
        .then(if (key == focusTargetKey) Modifier.focusRequester(restoreFocusRequester) else Modifier)
        .onFocusChanged { if (it.isFocused) lastFocusedKey = key }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // En mode déplacement, les flèches déplacent la vignette au lieu de déplacer le focus
                if (movingChannelId == null) return@onPreviewKeyEvent false
                val keyCode = event.nativeKeyEvent.keyCode
                val isConfirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_BACK
                if (event.type == KeyEventType.KeyUp) {
                    if (isConfirm) {
                        movingChannelId = null
                        focusRestoreRequest++
                    }
                    return@onPreviewKeyEvent true
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(+1)
                    KeyEvent.KEYCODE_DPAD_UP -> moveBy(-FAVORITE_COLUMNS)
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(+FAVORITE_COLUMNS)
                }
                true
            }
            .padding(start = 48.dp, end = 48.dp, top = 22.dp, bottom = 8.dp) // TV Safe Area
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // En-tête : heure et date, état de synchronisation, paramètres
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderClock()

                Spacer(modifier = Modifier.weight(1f))

                if (isLoading && hasContent) {
                    CircularProgressIndicator(
                        color = TextSecondary,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Synchronisation", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.width(18.dp))
                } else if (loadError != null && hasContent) {
                    Text(
                        text = loadError,
                        fontSize = 12.sp,
                        color = LiveRed.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 420.dp)
                    )
                    Spacer(modifier = Modifier.width(18.dp))
                }

                RoundIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "Paramètres",
                    onClick = onOpenSettings
                )
            }

            if (isLoading && !hasContent) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = TextSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Chargement des chaînes et du guide…",
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else if (!hasContent) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(460.dp)
                    ) {
                        Text(
                            text = if (activeConfig == null) "Bienvenue" else "Aucune chaîne disponible",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                activeConfig == null -> "Ajoutez une source (Dispatcharr, Xtream Codes ou playlist M3U) pour commencer."
                                loadError != null -> loadError
                                else -> "Vérifiez vos paramètres ou réaffichez les chaînes masquées."
                            },
                            fontSize = 14.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        MenuRow(
                            label = "Ouvrir les paramètres",
                            icon = Icons.Filled.Settings,
                            onClick = onOpenSettings,
                            modifier = Modifier.focusRequester(emptyStateFocusRequester)
                        )
                        if (activeConfig != null) {
                            MenuRow(
                                label = "Réessayer",
                                icon = Icons.Filled.Refresh,
                                onClick = onRefresh
                            )
                        }
                    }
                }
            } else {
                TvLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // SECTION 1 : FAVORIS (2 lignes x 4 colonnes)
                    item {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 10.dp)
                            ) {
                                SectionLabel(text = if (hasFavorites) "Favoris" else "À la une")
                                if (movingChannelId != null) {
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = "◀ ▶ ▲ ▼  Déplacer      OK  Valider",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AccentCyan
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                favoriteChannels.chunked(4).forEach { favRow ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        for (channel in favRow) {
                                            val isMoving = channel.id == movingChannelId
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    // Les autres vignettes s'estompent pendant un déplacement
                                                    .alpha(if (movingChannelId != null && !isMoving) 0.45f else 1f)
                                            ) {
                                                FavoriteChannelCard(
                                                    channel = channel,
                                                    onClick = { onChannelSelected(channel) },
                                                    onLongClick = { actionMenuChannel = channel },
                                                    isMoving = isMoving,
                                                    modifier = Modifier
                                                        .then(if (isMoving) Modifier.focusRequester(movingFocus) else Modifier)
                                                        .trackFocus("fav:${channel.id}")
                                                )
                                            }
                                        }
                                        repeat(4 - favRow.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 2 : CATÉGORIES
                    item {
                        TvLazyRow(
                            state = categoriesState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(categories) { category ->
                                DiscreteCategoryChip(
                                    text = category,
                                    isSelected = category == effectiveCategory,
                                    onClick = { selectedCategory = category },
                                    onLongClick = if (category == ALL_CATEGORIES) null else ({ categoryMenu = category })
                                )
                            }
                        }
                    }

                    // SECTION 3 : GRILLE DES CHAÎNES (4 colonnes)
                    items(
                        channelRows,
                        // Clé stable : les rangées déjà composées sont réutilisées lors des mises à jour du guide
                        key = { row -> "row:${row.first().id}" },
                        contentType = { "channelRow" }
                    ) { rowChannels ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            for (channel in rowChannels) {
                                Box(modifier = Modifier.weight(1f)) {
                                    CompactChannelCard(
                                        channel = channel,
                                        onClick = { onChannelSelected(channel) },
                                        onLongClick = { actionMenuChannel = channel },
                                        modifier = Modifier.trackFocus("list:${channel.id}")
                                    )
                                }
                            }
                            repeat(4 - rowChannels.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // Menu contextuel sur appui long
        actionMenuChannel?.let { ch ->
            val isFav = favoriteIds.contains(ch.id) || ch.isFavorite
            // Déplaçable s'il occupe une des places de favori de la grille et qu'il y a au moins deux favoris
            val canMove = movableCount > 1 && favorites.take(movableCount).any { it.id == ch.id }
            ChannelActionMenuDialog(
                channel = ch,
                isFavorite = isFav,
                onToggleFavorite = { onToggleFavorite(ch.id) },
                onHideChannel = { onHideChannel(ch.id) },
                loadStreams = { loadStreams(ch) },
                onSelectStream = { stream -> onSelectStream(ch, stream) },
                onMove = if (canMove) ({ movingChannelId = ch.id }) else null,
                automaticDescription = automaticStreamDescription,
                onDismiss = {
                    actionMenuChannel = null
                    // Rendre le focus à la grille (la carte d'origine a pu disparaître si la chaîne a été masquée)
                    focusRestoreRequest++
                }
            )
        }

        // Menu d'une catégorie (appui long sur sa puce)
        categoryMenu?.let { category ->
            CategoryActionDialog(
                category = category,
                channelCount = channels.count { it.group == category },
                onHide = { onHideCategory(category) },
                onDismiss = {
                    categoryMenu = null
                    focusRestoreRequest++
                }
            )
        }
    }
}

/** Heure et date discrètes en haut de l'accueil. */
@Composable
private fun HeaderClock() {
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(5_000)
        }
    }
    val time = remember(now.hour, now.minute) { now.format(DateTimeFormatter.ofPattern("HH:mm")) }
    val date = remember(now.toLocalDate()) {
        now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH))
            .replaceFirstChar { it.titlecase(Locale.FRENCH) }
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = time,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = date,
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}
