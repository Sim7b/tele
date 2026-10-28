package io.github.sim7b.tele.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.vod.VodDetails
import io.github.sim7b.tele.data.vod.VodEpisode
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodRows
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.data.vod.VodVersion
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.ProgressLine
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.Tag
import io.github.sim7b.tele.ui.components.focusRing
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.SurfaceCard
import io.github.sim7b.tele.ui.theme.SurfaceDark
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/** What to play: a movie or an episode file, its other files as fallbacks, from a position. */
data class VodPlayRequest(
    val titleKey: String,
    val episodeKey: String?,
    val label: String,
    val sourceId: String,
    val url: String,
    val fallbacks: List<String>,
    val startMs: Long
)

/**
 * Sheet of a movie or a series: wide image, poster, year, duration, genres, rating, summary, play /
 * resume / my list, then the chosen file's quality, audio and subtitles (as the provider describes them);
 * for a series, its seasons and episodes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VodDetailScreen(
    title: VodTitle,
    details: VodDetails?,
    loadFailed: Boolean,
    progress: Map<String, VodProgress>,
    inMyList: Boolean,
    collections: List<VodCollection>,
    onPlay: (VodPlayRequest) -> Unit,
    onToggleMyList: () -> Unit,
    onToggleCollection: (collectionId: String) -> Unit,
    onCreateCollection: (name: String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Episode marked watched (true) or unwatched, by its progress key
    onSetEpisodeWatched: (episodeKey: String, watched: Boolean) -> Unit = { _, _ -> }
) {
    BackHandler(onBack = onBack)
    val shown = details?.title ?: title
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { playFocus.requestFocusWithRetry() }

    var versionIndex by rememberSaveable(title.key) { mutableIntStateOf(0) }
    val versions = details?.versions.orEmpty()
    val version = versions.getOrNull(versionIndex) ?: versions.firstOrNull()
    val seasons = details?.seasons.orEmpty()
    var seasonIndex by rememberSaveable(title.key) { mutableIntStateOf(-1) }
    var focusedEpisode by remember(title.key) { mutableStateOf<VodEpisode?>(null) }
    var pickingCollections by remember { mutableStateOf(false) }
    var episodeMenu by remember { mutableStateOf<VodEpisode?>(null) }
    val inSomeCollection = inMyList || collections.any { title.key in it.titleKeys }

    // Series: the episode to play (the one in progress, else the one after the last watched, else the first)
    val episodes = seasons.flatMap { it.episodes }
    val nextEpisode = remember(episodes, progress) { nextEpisode(title.key, episodes, progress) }
    val currentSeason = if (seasonIndex >= 0) seasonIndex else seasons.indexOfFirst { it.number == nextEpisode?.season }.coerceAtLeast(0)

    val moviePosition = progress[title.key]?.takeIf { !it.finished && it.positionMs > 0 }
    val episodePosition = nextEpisode?.let { progress[VodRows.progressKey(title.key, it.key)] }?.takeIf { !it.finished && it.positionMs > 0 }
    val resumeAt = if (title.kind == VodKind.MOVIE) moviePosition else episodePosition

    fun request(episode: VodEpisode?, files: List<VodVersion>, chosen: VodVersion?, fromStart: Boolean): VodPlayRequest? {
        val file = chosen ?: files.firstOrNull() ?: return null
        val key = VodRows.progressKey(title.key, episode?.key)
        val position = if (fromStart) 0L else progress[key]?.takeIf { !it.finished }?.positionMs ?: 0L
        return VodPlayRequest(
            titleKey = title.key,
            episodeKey = episode?.key,
            label = if (episode == null) shown.title else "${shown.title} · ${episodeLabelPlain(episode)}",
            sourceId = file.sourceId,
            url = file.url,
            fallbacks = files.filter { it !== file }.map { it.url },
            startMs = position
        )
    }

    fun play(fromStart: Boolean) {
        val playRequest = if (title.kind == VodKind.MOVIE) {
            request(null, versions, version, fromStart)
        } else {
            nextEpisode?.let { request(it, it.versions, null, fromStart) }
        }
        playRequest?.let(onPlay)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
    ) {
        shown.backdropUrl?.let { url ->
            FadeInImage(url = url, contentDescription = null, modifier = Modifier.fillMaxSize(), alpha = 0.32f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, LocalAppColors.current.background), startY = 200f))
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row {
                    Box(
                        modifier = Modifier
                            .width(150.dp)
                            .height(225.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceCard)
                    ) {
                        FadeInImage(url = shown.posterUrl, contentDescription = shown.title, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(modifier = Modifier.width(26.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = shown.title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            val meta = listOfNotNull(
                                shown.year?.toString(),
                                durationLabel(details?.durationSecs),
                                if (title.kind == VodKind.SERIES && seasons.isNotEmpty()) pluralStringResource(R.plurals.vod_seasons, seasons.size, seasons.size) else null,
                                if (title.kind == VodKind.SERIES && episodes.isNotEmpty()) pluralStringResource(R.plurals.vod_episodes, episodes.size, episodes.size) else null,
                                shown.genres.take(3).joinToString(", ").ifBlank { null }
                            ).joinToString(" · ")
                            Text(text = meta, fontSize = 14.sp, color = TextSecondary)
                            shown.rating?.let {
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = ratingLabel(it), fontSize = 14.sp, color = AccentCyan)
                            }
                        }
                        details?.director?.let {
                            Text(text = stringResource(R.string.vod_director, it), fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
                        }
                        shown.plot?.let {
                            Text(
                                text = it,
                                fontSize = 14.sp,
                                color = TextPrimary.copy(alpha = 0.85f),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth(0.85f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                            VodButton(
                                label = resumeAt?.let { stringResource(R.string.vod_resume, positionLabel(it.positionMs)) }
                                    ?: if (title.kind == VodKind.SERIES && nextEpisode != null) "${stringResource(R.string.vod_play)} · ${episodeShort(nextEpisode)}" else stringResource(R.string.vod_play),
                                icon = Icons.Filled.PlayArrow,
                                primary = true,
                                onClick = { play(fromStart = false) },
                                modifier = Modifier.focusRequester(playFocus)
                            )
                            if (resumeAt != null) {
                                VodButton(label = stringResource(R.string.vod_restart), icon = Icons.Filled.Refresh, onClick = { play(fromStart = true) })
                            }
                            VodButton(
                                label = stringResource(R.string.vod_add_to),
                                icon = if (inSomeCollection) Icons.Filled.Check else Icons.Filled.Add,
                                onClick = { pickingCollections = true }
                            )
                            if (details == null && !loadFailed) {
                                CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Text(text = stringResource(R.string.vod_loading_details), fontSize = 12.sp, color = TextSecondary)
                            }
                            if (loadFailed) {
                                VodButton(label = stringResource(R.string.vod_refresh), icon = Icons.Filled.Refresh, onClick = onRetry)
                            }
                        }
                    }
                }
            }

            // Movie: the chosen file, its audio and subtitles
            if (title.kind == VodKind.MOVIE && details != null) {
                item {
                    if (versions.isEmpty()) {
                        Text(text = stringResource(R.string.vod_no_files), fontSize = 13.sp, color = TextSecondary)
                    } else {
                        FileInfo(versions = versions, selected = versionIndex.coerceIn(0, versions.lastIndex), onSelect = { versionIndex = it })
                    }
                }
            }

            // Series: seasons, then the episodes of the chosen season
            if (title.kind == VodKind.SERIES && seasons.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(seasons) { index, season ->
                            VodChip(
                                label = if (season.number == 0) stringResource(R.string.vod_specials) else stringResource(R.string.vod_season, season.number),
                                selected = index == currentSeason,
                                onClick = { seasonIndex = index }
                            )
                        }
                    }
                }
                item {
                    val season = seasons.getOrNull(currentSeason)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 24.dp)) {
                        items(season?.episodes.orEmpty(), key = { it.key }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                progress = progress[VodRows.progressKey(title.key, episode.key)],
                                onFocused = { focusedEpisode = episode },
                                onClick = { request(episode, episode.versions, null, fromStart = false)?.let(onPlay) },
                                onLongClick = { episodeMenu = episode }
                            )
                        }
                    }
                }
                item {
                    val episode = focusedEpisode ?: nextEpisode
                    if (episode != null) {
                        Column {
                            Text(
                                text = episodeLabel(episode),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            episode.plot?.let {
                                Text(text = it, fontSize = 13.sp, color = TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.8f))
                            }
                            if (episode.versions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                FileInfo(versions = episode.versions, selected = 0, onSelect = null)
                            }
                        }
                    }
                }
            }

            details?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                item {
                    Column {
                        SectionLabel(text = stringResource(R.string.vod_cast))
                        Text(text = cast.joinToString(" · "), fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }

    episodeMenu?.let { episode ->
        val key = VodRows.progressKey(title.key, episode.key)
        val state = progress[key]
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstFocus.requestFocusWithRetry() }
        VodDialogFrame(title = episodeLabel(episode), onDismiss = { episodeMenu = null }) {
            val watched = state?.finished == true
            MenuRow(
                label = stringResource(if (watched) R.string.vod_menu_mark_unwatched else R.string.vod_menu_mark_watched),
                icon = Icons.Filled.Check,
                onClick = {
                    onSetEpisodeWatched(key, !watched)
                    episodeMenu = null
                },
                modifier = Modifier.focusRequester(firstFocus)
            )
            if (state != null && !state.finished && state.positionMs > 0) {
                MenuRow(
                    label = stringResource(R.string.vod_menu_remove_continue),
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        onSetEpisodeWatched(key, false)
                        episodeMenu = null
                    }
                )
            }
        }
    }

    if (pickingCollections) {
        CollectionPickerDialog(
            collections = collections,
            titleKey = title.key,
            inMyList = inMyList,
            onToggleMyList = onToggleMyList,
            onToggle = onToggleCollection,
            onCreate = onCreateCollection,
            onDismiss = { pickingCollections = false }
        )
    }
}

/** Quality, audio and subtitles of a file, and the choice of the file when there are several. */
@Composable
private fun FileInfo(versions: List<VodVersion>, selected: Int, onSelect: ((Int) -> Unit)?) {
    val version = versions[selected]
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        InfoBlock(title = stringResource(R.string.vod_version), modifier = Modifier.weight(1.2f)) {
            if (onSelect != null && versions.size > 1) {
                versions.forEachIndexed { index, file ->
                    VodChip(label = versionLabel(file), selected = index == selected, onClick = { onSelect(index) }, modifier = Modifier.padding(bottom = 6.dp))
                }
            } else {
                Text(text = versionLabel(version), fontSize = 13.sp, color = TextPrimary)
                if (versions.size > 1) {
                    Text(text = pluralStringResource(R.plurals.vod_versions, versions.size, versions.size), fontSize = 12.sp, color = TextSecondary)
                }
            }
        }
        InfoBlock(title = stringResource(R.string.vod_audio), modifier = Modifier.weight(1f)) {
            ValueList(audioLabels(version))
        }
        InfoBlock(title = stringResource(R.string.vod_subtitles), modifier = Modifier.weight(1f)) {
            ValueList(subtitleLabels(version))
        }
    }
}

@Composable
private fun ValueList(values: List<String>) {
    if (values.isEmpty()) {
        Text(text = stringResource(R.string.vod_not_specified), fontSize = 12.sp, color = TextSecondary)
    } else {
        values.forEach { Text(text = it, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(bottom = 2.dp)) }
    }
}

@Composable
private fun InfoBlock(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SectionLabel(text = title, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

/** "2160p HEVC HDR · Maison" / "4K · Liste". */
private fun versionLabel(version: VodVersion): String =
    listOfNotNull(version.qualityLabel, version.sourceName.ifBlank { null }).joinToString(" · ").ifBlank { version.name }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(episode: VodEpisode, progress: VodProgress?, onFocused: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val shape = RoundedCornerShape(8.dp)
    Column(modifier = Modifier.width(220.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            modifier = Modifier
                .width(220.dp)
                .height(124.dp),
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            border = focusRing(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceCard, focusedContainerColor = SurfaceCard)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (episode.stillUrl != null) {
                    FadeInImage(url = episode.stillUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        text = stringResource(R.string.vod_episode, episode.number),
                        fontSize = 14.sp,
                        color = TextSecondary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                if (progress?.finished == true) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = stringResource(R.string.vod_watched), fontSize = 10.sp, color = Color.White)
                    }
                } else if (progress != null && progress.positionMs > 0) {
                    ProgressLine(progress = progress.fraction, height = 3.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            }
        }
        Text(
            text = episodeLabel(episode),
            fontSize = 12.sp,
            color = if (focused) TextPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        val file = episode.versions.firstOrNull()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            durationLabel(episode.durationSecs)?.let { Text(text = it, fontSize = 11.sp, color = TextSecondary) }
            file?.qualityLabel?.let {
                Spacer(modifier = Modifier.width(6.dp))
                Tag(text = it)
            }
        }
    }
}

/** "5. Broken to the Fist", or "Episode 5" without a title. */
@Composable
private fun episodeLabel(episode: VodEpisode): String =
    if (episode.title.isBlank()) stringResource(R.string.vod_episode, episode.number) else "${episode.number}. ${episode.title}"

@Composable
private fun episodeShort(episode: VodEpisode): String = "S${episode.season} " + stringResource(R.string.vod_episode_short, episode.number)

private fun episodeLabelPlain(episode: VodEpisode): String =
    "S${episode.season}E${episode.number}" + if (episode.title.isNotBlank()) " · ${episode.title}" else ""

/** Episode in progress (the latest), else the one after the last watched, else the first. */
internal fun nextEpisode(titleKey: String, episodes: List<VodEpisode>, progress: Map<String, VodProgress>): VodEpisode? {
    if (episodes.isEmpty()) return null
    val ordered = episodes.sortedWith(compareBy<VodEpisode> { if (it.season == 0) Int.MAX_VALUE else it.season }.thenBy { it.number })
    val withProgress = ordered.mapNotNull { episode -> progress[VodRows.progressKey(titleKey, episode.key)]?.let { episode to it } }
    withProgress.filter { !it.second.finished && it.second.positionMs > 0 }.maxByOrNull { it.second.updatedAt }?.let { return it.first }
    val lastWatched = withProgress.filter { it.second.finished }.maxByOrNull { it.second.updatedAt }?.first ?: return ordered.first()
    val index = ordered.indexOf(lastWatched)
    return ordered.getOrNull(index + 1) ?: ordered.first()
}

/** Pill button of the sheet; [primary] is filled at rest. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VodButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) TextPrimary.copy(alpha = 0.9f) else SurfaceDark,
            contentColor = if (primary) LocalAppColors.current.background else TextPrimary,
            focusedContainerColor = AccentCyan,
            focusedContentColor = Color(0xFF080B10)
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 18.dp, top = 9.dp, bottom = 9.dp)) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}
