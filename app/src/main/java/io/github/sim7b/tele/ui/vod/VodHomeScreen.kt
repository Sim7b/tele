package io.github.sim7b.tele.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.res.pluralStringResource
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.vod.RankedItem
import io.github.sim7b.tele.data.vod.Ranking
import io.github.sim7b.tele.data.vod.TmdbFacts
import io.github.sim7b.tele.data.vod.VodBrowse
import io.github.sim7b.tele.data.vod.VodFilter
import io.github.sim7b.tele.data.vod.VodFilterOptions
import io.github.sim7b.tele.data.vod.VodIndexProgress
import io.github.sim7b.tele.data.vod.VodRankings
import io.github.sim7b.tele.data.vod.VodSort
import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodLibraryState
import io.github.sim7b.tele.data.vod.VodRow
import io.github.sim7b.tele.data.vod.VodRows
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.RoundIconButton
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.Tag
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.SurfaceDark
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

private enum class VodTab(val labelRes: Int) {
    HOME(R.string.vod_tab_home),
    MOVIES(R.string.vod_tab_movies),
    SERIES(R.string.vod_tab_series),
    COLLECTIONS(R.string.vod_tab_collections)
}

/** Filter whose values a dialog shows. */
private enum class FilterField { COUNTRY, STUDIO, GENRE, DECADE, SORT }

/**
 * Movies and series home, like Plex: the focused title at the top (wide image, year, genres, summary),
 * rows below (continue watching, TMDB rankings, collections, latest, genres); the Movies and Series tabs
 * list the whole library with filters (country, studio, genre, decade) and a sort; the Collections tab
 * shows the user's collections.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VodHomeScreen(
    library: VodLibraryState,
    progress: Map<String, VodProgress>,
    myList: List<String>,
    collections: List<VodCollection>,
    rankings: Map<Ranking, List<RankedItem>>,
    indexVersion: Int,
    indexProgress: VodIndexProgress,
    facts: () -> Map<String, TmdbFacts>,
    hasTmdbKey: Boolean,
    filters: Map<VodKind, VodFilter>,
    onFiltersChange: (VodKind, VodFilter) -> Unit,
    onOpenTitle: (VodTitle) -> Unit,
    onOpenSearch: () -> Unit,
    onCreateCollection: (String) -> Unit,
    onRenameCollection: (id: String, name: String) -> Unit,
    onDeleteCollection: (id: String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    findPoster: (suspend (VodTitle) -> String?)? = null,
    hiddenRows: Set<VodRow.Kind> = emptySet(),
    actions: VodTitleActions? = null
) {
    BackHandler(onBack = onBack)
    var tab by rememberSaveable { mutableStateOf(VodTab.HOME) }
    // Long press on a title: its actions, then possibly the collection picker
    var menuFor by remember { mutableStateOf<Pair<VodTitle, VodRow?>?>(null) }
    var pickerFor by remember { mutableStateOf<VodTitle?>(null) }
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openFilter by remember { mutableStateOf<FilterField?>(null) }
    var naming by remember { mutableStateOf<VodCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val kind = when (tab) {
        VodTab.MOVIES -> VodKind.MOVIE
        VodTab.SERIES -> VodKind.SERIES
        else -> null
    }
    val filter = kind?.let { filters[it] } ?: VodFilter()

    // Built off the main thread: libraries hold tens of thousands of titles
    val rows by produceState(emptyList<VodRow>(), library.titles, progress, myList, collections, rankings, indexVersion, hiddenRows) {
        value = withContext(Dispatchers.Default) {
            val matcher = if (rankings.isEmpty()) null else VodRankings.Matcher(library.titles, facts())
            VodRows.build(library.titles, progress, myList, collections, rankings, matcher, hiddenRows)
        }
    }
    val listed by produceState(emptyList<VodTitle>(), library.titles, kind, filter, indexVersion) {
        value = if (kind == null) emptyList() else withContext(Dispatchers.Default) { VodBrowse.filter(library.titles, kind, filter, facts()) }
    }
    val options by produceState(VodFilterOptions(), library.titles, kind, indexVersion) {
        value = if (kind == null) VodFilterOptions() else withContext(Dispatchers.Default) { VodBrowse.options(library.titles, kind, facts()) }
    }
    val hero = focusedKey?.let { library.byKey[it] } ?: library.featured
    val progressByTitle = remember(progress) {
        progress.values.filter { !it.finished }.groupBy { it.titleKey }.mapValues { (_, list) -> list.maxBy { it.updatedAt }.fraction }
    }

    // First card once the content is there
    LaunchedEffect(rows.isNotEmpty(), listed.isNotEmpty(), tab) {
        if ((tab == VodTab.HOME && rows.isNotEmpty()) || (kind != null && listed.isNotEmpty()) || tab == VodTab.COLLECTIONS) {
            firstFocus.requestFocusWithRetry()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
    ) {
        // Wide image of the focused title, fading into the background
        if (tab == VodTab.HOME) {
            hero?.backdropUrl?.let { url ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(0.62f)
                        .height(300.dp)
                ) {
                    FadeInImage(url = url, contentDescription = null, modifier = Modifier.fillMaxSize(), alpha = 0.55f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.horizontalGradient(listOf(LocalAppColors.current.background, Color.Transparent)))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, LocalAppColors.current.background)))
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 26.dp)) {
            // Header: tabs, status, search, refresh, back to live TV
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.vod_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(20.dp))
                VodTab.entries.forEach { entry ->
                    VodChip(label = stringResource(entry.labelRes), selected = entry == tab, onClick = { tab = entry })
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                when {
                    library.loading -> {
                        CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(if (library.titles.isEmpty()) R.string.vod_loading else R.string.vod_updating),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    indexProgress.running -> {
                        Text(
                            text = stringResource(R.string.vod_index_progress, indexProgress.indexed * 100 / indexProgress.total.coerceAtLeast(1)),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    library.error != null -> {
                        Text(
                            text = library.error.resolve(),
                            fontSize = 12.sp,
                            color = LiveRed.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Shrinks with the room left: the buttons on the right always stay on screen
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .widthIn(max = 320.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                }
                RoundIconButton(icon = Icons.Filled.Search, contentDescription = stringResource(R.string.vod_search), onClick = onOpenSearch)
                Spacer(modifier = Modifier.width(10.dp))
                RoundIconButton(icon = Icons.Filled.Refresh, contentDescription = stringResource(R.string.vod_refresh), onClick = onRefresh)
                Spacer(modifier = Modifier.width(10.dp))
                RoundIconButton(icon = Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.vod_back_to_tv), onClick = onBack)
            }

            if (library.titles.isEmpty()) {
                if (!library.loading) EmptyLibrary()
                return@Column
            }

            when (tab) {
                VodTab.HOME -> {
                    Hero(hero)
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        rows.forEachIndexed { rowIndex, row ->
                            item(key = "${row.kind}:${row.name}") {
                                PosterRow(
                                    label = rowTitle(row),
                                    titles = row.titles,
                                    ranked = row.ranked,
                                    progress = progressByTitle,
                                    onOpenTitle = onOpenTitle,
                                    onFocused = { focusedKey = it.key },
                                    onLongClick = { menuFor = it to row },
                                    findPoster = findPoster,
                                    firstFocus = if (rowIndex == 0) firstFocus else null
                                )
                            }
                        }
                    }
                }

                VodTab.MOVIES, VodTab.SERIES -> {
                    FilterBar(
                        filter = filter,
                        count = listed.size,
                        onOpen = { openFilter = it },
                        onClear = { kind?.let { onFiltersChange(it, VodFilter(sort = filter.sort)) } }
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(LocalPosterWidth.current + 14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(listed, key = { it.key }) { title ->
                            PosterCard(
                                title = title,
                                progress = progressByTitle[title.key],
                                onClick = { onOpenTitle(title) },
                                onLongClick = { menuFor = title to null },
                                findPoster = findPoster,
                                modifier = if (title == listed.first()) Modifier.focusRequester(firstFocus) else Modifier
                            )
                        }
                    }
                }

                VodTab.COLLECTIONS -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                VodChip(
                                    label = "+ " + stringResource(R.string.vod_collection_new),
                                    selected = false,
                                    onClick = { creating = true },
                                    modifier = Modifier.focusRequester(firstFocus)
                                )
                                if (collections.isEmpty()) {
                                    Text(text = stringResource(R.string.vod_collections_empty), fontSize = 13.sp, color = TextSecondary)
                                }
                            }
                        }
                        val byKey = library.byKey
                        item(key = "my-list") {
                            val titles = myList.mapNotNull { byKey[it] }
                            PosterRow(
                                label = stringResource(R.string.vod_row_my_list),
                                titles = titles,
                                progress = progressByTitle,
                                onOpenTitle = onOpenTitle,
                                onLongClick = { menuFor = it to VodRow(VodRow.Kind.MY_LIST, titles) },
                                findPoster = findPoster
                            )
                        }
                        items(collections, key = { it.id }) { collection ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SectionLabel(text = collection.name)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    VodChip(label = stringResource(R.string.vod_collection_rename), selected = false, onClick = { naming = collection })
                                    VodChip(label = stringResource(R.string.vod_collection_delete), selected = false, onClick = { onDeleteCollection(collection.id) })
                                }
                                val titles = collection.titleKeys.mapNotNull { byKey[it] }
                                PosterRow(
                                    label = null,
                                    titles = titles,
                                    progress = progressByTitle,
                                    onOpenTitle = onOpenTitle,
                                    onLongClick = { menuFor = it to VodRow(VodRow.Kind.COLLECTION, titles, collection.name, collectionId = collection.id) },
                                    findPoster = findPoster
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Values of a filter, with their number of titles
    val field = openFilter
    if (field != null && kind != null) {
        val close = { openFilter = null }
        val tmdbNote = when {
            !hasTmdbKey -> stringResource(R.string.vod_filter_needs_tmdb)
            indexProgress.running -> stringResource(R.string.vod_filter_indexing)
            else -> null
        }
        when (field) {
            FilterField.COUNTRY -> FilterDialog(
                title = stringResource(R.string.vod_filter_country),
                subtitle = tmdbNote,
                options = options.countries,
                selected = filter.country,
                label = { countryName(it) },
                onSelect = { onFiltersChange(kind, filter.copy(country = it)); close() },
                onDismiss = close
            )
            FilterField.STUDIO -> FilterDialog(
                title = stringResource(R.string.vod_filter_studio),
                subtitle = tmdbNote,
                options = options.studios,
                selected = filter.studio,
                label = { it.name },
                onSelect = { onFiltersChange(kind, filter.copy(studio = it)); close() },
                onDismiss = close
            )
            FilterField.GENRE -> FilterDialog(
                title = stringResource(R.string.vod_filter_genre),
                options = options.genres,
                selected = filter.genre,
                label = { it },
                onSelect = { onFiltersChange(kind, filter.copy(genre = it)); close() },
                onDismiss = close
            )
            FilterField.DECADE -> FilterDialog(
                title = stringResource(R.string.vod_filter_decade),
                options = options.decades,
                selected = filter.decade,
                label = { stringResource(R.string.vod_decade, it) },
                onSelect = { onFiltersChange(kind, filter.copy(decade = it)); close() },
                onDismiss = close
            )
            FilterField.SORT -> FilterDialog(
                title = stringResource(R.string.vod_filter_sort),
                options = VodSort.entries.map { it to listed.size },
                selected = filter.sort,
                label = { sortLabel(it) },
                onSelect = { onFiltersChange(kind, filter.copy(sort = it ?: VodSort.POPULARITY)); close() },
                onDismiss = close,
                allowNone = false
            )
        }
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.vod_collection_new),
            initial = "",
            confirm = stringResource(R.string.vod_collection_create),
            onConfirm = { name ->
                onCreateCollection(name)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }
    menuFor?.let { (title, row) ->
        val removeFrom = when (row?.kind) {
            VodRow.Kind.MY_LIST -> stringResource(R.string.vod_row_my_list)
            VodRow.Kind.COLLECTION -> row.name
            else -> null
        }
        TitleActionsDialog(
            title = title,
            inContinue = progress.values.any { it.titleKey == title.key && !it.finished && it.positionMs > 0 },
            watched = if (title.kind == VodKind.MOVIE) progress[title.key]?.finished == true else null,
            removeFrom = removeFrom.takeIf { actions != null },
            onOpen = { onOpenTitle(title) },
            onAddTo = { pickerFor = title },
            onRemoveContinue = { actions?.removeFromContinue?.invoke(title.key) },
            onToggleWatched = { actions?.setWatched?.invoke(title, progress[title.key]?.finished != true) },
            onRemoveFrom = {
                when (row?.kind) {
                    VodRow.Kind.MY_LIST -> actions?.toggleMyList?.invoke(title.key)
                    VodRow.Kind.COLLECTION -> row.collectionId?.let { actions?.toggleInCollection?.invoke(it, title.key) }
                    else -> Unit
                }
            },
            onDismiss = { menuFor = null }
        )
    }
    pickerFor?.let { title ->
        CollectionPickerDialog(
            collections = collections,
            titleKey = title.key,
            inMyList = title.key in myList,
            onToggleMyList = { actions?.toggleMyList?.invoke(title.key) },
            onToggle = { id -> actions?.toggleInCollection?.invoke(id, title.key) },
            onCreate = { name -> actions?.createCollectionWith?.invoke(name, title.key) },
            onDismiss = { pickerFor = null }
        )
    }
    naming?.let { collection ->
        NameDialog(
            title = stringResource(R.string.vod_collection_rename),
            initial = collection.name,
            confirm = stringResource(R.string.vod_collection_save),
            onConfirm = { name ->
                onRenameCollection(collection.id, name)
                naming = null
            },
            onDismiss = { naming = null }
        )
    }
}

/** A labelled row of posters; [ranked] shows the position of each title (TMDB trends). */
@Composable
private fun PosterRow(
    label: String?,
    titles: List<VodTitle>,
    progress: Map<String, Float>,
    onOpenTitle: (VodTitle) -> Unit,
    findPoster: (suspend (VodTitle) -> String?)?,
    ranked: Boolean = false,
    onFocused: (VodTitle) -> Unit = {},
    onLongClick: (VodTitle) -> Unit = {},
    firstFocus: FocusRequester? = null
) {
    Column {
        label?.let { SectionLabel(text = it) }
        if (titles.isEmpty()) {
            Text(text = stringResource(R.string.vod_collection_empty), fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 10.dp, end = 24.dp)
        ) {
            itemsIndexed(titles, key = { _, title -> title.key }) { index, title ->
                PosterCard(
                    title = title,
                    progress = progress[title.key],
                    rank = if (ranked) index + 1 else null,
                    onClick = { onOpenTitle(title) },
                    onLongClick = { onLongClick(title) },
                    onFocused = { onFocused(title) },
                    findPoster = findPoster,
                    modifier = if (firstFocus != null && index == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }
}

/** Filters of the Movies and Series tabs, each opening its values; the count of titles shown. */
@Composable
private fun FilterBar(filter: VodFilter, count: Int, onOpen: (FilterField) -> Unit, onClear: () -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(top = 14.dp)
    ) {
        item {
            VodChip(
                label = stringResource(R.string.vod_filter_country) + (filter.country?.let { " : " + countryName(it) } ?: " ▾"),
                selected = filter.country != null,
                onClick = { onOpen(FilterField.COUNTRY) }
            )
        }
        item {
            VodChip(
                label = stringResource(R.string.vod_filter_studio) + (filter.studio?.let { " : " + it.name } ?: " ▾"),
                selected = filter.studio != null,
                onClick = { onOpen(FilterField.STUDIO) }
            )
        }
        item {
            VodChip(
                label = stringResource(R.string.vod_filter_genre) + (filter.genre?.let { " : $it" } ?: " ▾"),
                selected = filter.genre != null,
                onClick = { onOpen(FilterField.GENRE) }
            )
        }
        item {
            VodChip(
                label = stringResource(R.string.vod_filter_decade) + (filter.decade?.let { " : " + stringResource(R.string.vod_decade, it) } ?: " ▾"),
                selected = filter.decade != null,
                onClick = { onOpen(FilterField.DECADE) }
            )
        }
        item {
            VodChip(label = stringResource(R.string.vod_filter_sort) + " : " + sortLabel(filter.sort), selected = false, onClick = { onOpen(FilterField.SORT) })
        }
        if (filter.active) {
            item { VodChip(label = "✕ " + stringResource(R.string.vod_filter_clear), selected = false, onClick = onClear) }
        }
        item {
            Text(text = pluralStringResource(R.plurals.vod_titles_count, count, count), fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
internal fun sortLabel(sort: VodSort): String = stringResource(
    when (sort) {
        VodSort.POPULARITY -> R.string.vod_sort_popularity
        VodSort.TITLE -> R.string.vod_sort_title
        VodSort.YEAR -> R.string.vod_sort_year
        VodSort.RATING -> R.string.vod_sort_rating
        VodSort.ADDED -> R.string.vod_sort_added
    }
)

/** The focused title: name, year, kind, rating, genres and summary. */
@Composable
private fun Hero(title: VodTitle?) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.55f)
            .height(170.dp)
            .padding(top = 18.dp)
    ) {
        if (title == null) return@Column
        Text(text = stringResource(R.string.vod_featured).uppercase(), fontSize = 11.sp, letterSpacing = 1.6.sp, color = TextSecondary)
        Text(
            text = title.title,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            val meta = listOfNotNull(
                title.year?.toString(),
                stringResource(if (title.kind == VodKind.MOVIE) R.string.vod_movie else R.string.vod_series),
                title.genres.take(3).joinToString(", ").ifBlank { null }
            ).joinToString(" · ")
            Text(text = meta, fontSize = 13.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            title.rating?.let {
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = ratingLabel(it), fontSize = 13.sp, color = AccentCyan)
            }
            title.entries.flatMap { it.qualityTags }.distinct().take(2).forEach {
                Spacer(modifier = Modifier.width(8.dp))
                Tag(text = it)
            }
        }
        title.plot?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = stringResource(R.string.vod_empty), fontSize = 18.sp, color = TextPrimary)
        Text(
            text = stringResource(R.string.vod_empty_hint),
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 560.dp)
        )
    }
}

/** Tab of the movies and series home: filled when focused, marked when selected. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun VodChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) TextPrimary.copy(alpha = 0.16f) else SurfaceDark,
            contentColor = if (selected) TextPrimary else TextSecondary,
            focusedContainerColor = TextPrimary,
            focusedContentColor = LocalAppColors.current.background
        )
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
        )
    }
}
