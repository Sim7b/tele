package io.github.sim7b.tele.ui.vod

import io.github.sim7b.tele.data.vod.VodLibrary
import io.github.sim7b.tele.data.vod.GenreCatalog
import io.github.sim7b.tele.data.vod.VodGenre
import io.github.sim7b.tele.data.vod.StreamingPlatform
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.pluralStringResource
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.vod.PlatformCatalog
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
import io.github.sim7b.tele.data.vod.VodShelf
import io.github.sim7b.tele.data.vod.VodTitle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.GlassClock
import io.github.sim7b.tele.ui.components.GlassInk
import io.github.sim7b.tele.ui.components.GlassNavBar
import io.github.sim7b.tele.ui.components.GlassTab
import io.github.sim7b.tele.ui.components.ListsMenu
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import io.github.sim7b.tele.ui.components.LocalImmersive
import io.github.sim7b.tele.ui.components.NavSection
import io.github.sim7b.tele.ui.components.ScreenNav
import io.github.sim7b.tele.ui.components.glass
import io.github.sim7b.tele.data.remote.ProgramPosterRepository
import androidx.compose.runtime.CompositionLocalProvider
import io.github.sim7b.tele.ui.components.LocalScreen
import io.github.sim7b.tele.ui.components.RoundIconButton
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.components.touchClick
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

private enum class VodTab(val labelRes: Int) {
    MOVIES(R.string.vod_tab_movies),
    SERIES(R.string.vod_tab_series),
    COLLECTIONS(R.string.vod_tab_collections)
}

/** Filter whose values a dialog shows. */
private const val FOCUS_RECOVERY_DELAY_MS = 150L

private enum class FilterField { COUNTRY, STUDIO, GENRE, DECADE, AUDIO, SUBTITLES, SORT }

/**
 * Movies and series home, like Plex: the focused title at the top (wide image, year, genres, summary),
 * rows below (continue watching, TMDB rankings, collections, latest, genres); the Movies and Series tabs
 * list the whole library with filters (country, studio, genre, decade) and a sort; the Collections tab
 * shows the user's collections.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // Settings of the movies and series
    onOpenSettings: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    findPoster: (suspend (VodTitle) -> String?)? = null,
    hiddenRows: Set<VodRow.Kind> = emptySet(),
    actions: VodTitleActions? = null,
    // Rows last built (shown at once), the TMDB rankings matching kept between openings, and the rows built
    initialRows: List<VodRow> = emptyList(),
    matcherFor: (List<VodTitle>, Map<Ranking, List<RankedItem>>) -> VodRankings.Matcher? = { titles, ranked ->
        if (ranked.isEmpty()) null else VodRankings.Matcher(titles, facts())
    },
    onRows: (List<VodRow>) -> Unit = {},
    // New categories suggested (source name, count), to choose or dismiss
    newCategories: Pair<String, Int>? = null,
    onChooseNewCategories: () -> Unit = {},
    onDismissNewCategories: () -> Unit = {},
    // Section opened from the live TV home (movies or series), again each time [sectionToken] changes
    section: VodKind = VodKind.MOVIE,
    sectionToken: Int = 0,
    // The other screens in a glass bar: the immersive style (the focused title over the whole screen)
    nav: ScreenNav? = null,
    // Streaming services: their logos, and their catalog on TMDB (by popularity) when one is opened
    platformLogos: Map<StreamingPlatform, String> = emptyMap(),
    platformCatalog: suspend (StreamingPlatform, VodKind) -> PlatformCatalog = { platform, kind ->
        PlatformCatalog(Ranking.of(platform, kind)?.let { rankings[it] }.orEmpty())
    },
    // The TMDB lists of a genre opened from the row of the genres (shown with a TMDB key)
    genreCatalog: suspend (VodGenre, VodKind) -> GenreCatalog = { _, _ -> GenreCatalog() },
    // A streaming service focused a moment: its catalog fetched ahead, to open at once
    onPrefetchPlatform: (StreamingPlatform, VodKind) -> Unit = { _, _ -> }
) {
    BackHandler(onBack = onBack)
    val screen = LocalScreen.current
    val compact = screen.compact
    val immersive = nav != null && !compact
    var tab by rememberSaveable { mutableStateOf(if (section == VodKind.SERIES) VodTab.SERIES else VodTab.MOVIES) }
    LaunchedEffect(sectionToken) { tab = if (section == VodKind.SERIES) VodTab.SERIES else VodTab.MOVIES }
    // Long press on a title: its actions, then possibly the collection picker
    var menuFor by remember { mutableStateOf<Pair<VodTitle, VodRow?>?>(null) }
    var pickerFor by remember { mutableStateOf<VodTitle?>(null) }
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openFilter by remember { mutableStateOf<FilterField?>(null) }
    var naming by remember { mutableStateOf<VodCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    // Immersive: the menu of the rows, opened by Left at the start of a row
    var railOpen by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun rowRequester(key: String) = rowFocus.getOrPut(key) { FocusRequester() }
    val rowsState = rememberLazyListState()
    var currentRow by remember { mutableIntStateOf(0) }
    var currentKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(railOpen) { if (railOpen) railFocus.requestFocusWithRetry() }
    fun closeRail() {
        railOpen = false
        currentKey?.let { key -> scope.launch { rowRequester(key).requestFocusWithRetry() } }
    }
    // A streaming service opened from the row of their logos (its titles in rows); Back comes back to its logo
    var platform by rememberSaveable { mutableStateOf<StreamingPlatform?>(null) }
    val platformFocus = remember { HashMap<StreamingPlatform, FocusRequester>() }
    fun platformRequester(entry: StreamingPlatform) = platformFocus.getOrPut(entry) { FocusRequester() }
    // A service focused a moment: its catalog fetched ahead (not each one passed over)
    var focusedPlatform by remember { mutableStateOf<StreamingPlatform?>(null) }
    LaunchedEffect(focusedPlatform) {
        val focused = focusedPlatform ?: return@LaunchedEffect
        delay(PREFETCH_DELAY_MS)
        val focusedKind = if (tab == VodTab.SERIES) VodKind.SERIES else VodKind.MOVIE
        onPrefetchPlatform(focused, focusedKind)
    }
    // Or a genre opened from the row of the genres (its TMDB lists); Back comes back to it
    var genre by rememberSaveable { mutableStateOf<VodGenre?>(null) }
    val genreFocus = remember { HashMap<VodGenre, FocusRequester>() }
    fun genreRequester(entry: VodGenre) = genreFocus.getOrPut(entry) { FocusRequester() }
    // Without a TMDB key, a genre of the provider instead (its titles of the library)
    var libraryGenre by rememberSaveable { mutableStateOf<String?>(null) }
    val libraryGenreFocus = remember { HashMap<String, FocusRequester>() }
    fun libraryGenreRequester(entry: String) = libraryGenreFocus.getOrPut(entry) { FocusRequester() }
    fun closeSubview() {
        val closed = platform?.let { platformRequester(it) } ?: genre?.let { genreRequester(it) } ?: libraryGenre?.let { libraryGenreRequester(it) } ?: return
        platform = null
        genre = null
        libraryGenre = null
        scope.launch {
            rowsState.scrollToItem(0)
            closed.requestFocusWithRetry()
        }
    }
    // Registered after the one of the screen, before the one of the menu: Back closes the menu, then the service
    BackHandler(enabled = platform != null || genre != null || libraryGenre != null) { closeSubview() }
    // Registered after the one of the screen: Back closes the menu first
    BackHandler(enabled = railOpen) { closeRail() }
    val kind = when (tab) {
        VodTab.MOVIES -> VodKind.MOVIE
        VodTab.SERIES -> VodKind.SERIES
        else -> null
    }
    var subviewTab by remember { mutableStateOf(tab) }
    if (subviewTab != tab) {
        subviewTab = tab
        platform = null
        genre = null
        libraryGenre = null
    }
    val filter = kind?.let { filters[it] } ?: VodFilter()

    // Built off the main thread: libraries hold tens of thousands of titles
    // The rows last built first (the home opens with them), then the ones of the current state
    val rows by produceState(initialRows, library.titles, progress, myList, collections, rankings, indexVersion, hiddenRows) {
        value = withContext(Dispatchers.Default) {
            VodRows.build(library.titles, progress, myList, collections, rankings, matcherFor(library.titles, rankings), hiddenRows)
        }
        onRows(value)
    }
    // The rows of the movies or the series (no filter chosen): continue watching, my list, collections, rankings;
    // the genres come from the shelves below
    val rowsOfKind = remember(rows, kind, filter) {
        if (kind == null || filter != VodFilter(sort = filter.sort)) {
            emptyList()
        } else {
            rows.filter { it.kind != VodRow.Kind.GENRE }.mapNotNull { row ->
                row.titles.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { row.copy(titles = it) }
            }
        }
    }
    // The streaming services with titles of this kind in the library: a row of logos, not a row each
    val platforms = remember(rowsOfKind) { rowsOfKind.filter { it.kind == VodRow.Kind.PLATFORM }.mapNotNull { it.platform } }
    val kindRows = remember(rowsOfKind) { rowsOfKind.filter { it.kind != VodRow.Kind.PLATFORM } }
    // The main genres of this kind, from TMDB (with a key, no filter chosen)
    val genres = remember(kind, hasTmdbKey, rowsOfKind.isNotEmpty()) {
        if (kind != null && hasTmdbKey && rowsOfKind.isNotEmpty()) VodGenre.of(kind) else emptyList()
    }
    // Without a key, the main genres the provider gives (TMDB ones are preferred: same for every provider)
    val libraryGenres by produceState(emptyList<String>(), kind, hasTmdbKey, rowsOfKind.isNotEmpty(), library.titles) {
        value = if (kind == null || hasTmdbKey || rowsOfKind.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            VodLibrary.topGenres(library.titles.filter { it.kind == kind }, LIBRARY_GENRES)
        }
    }
    // The rows of the opened service: its titles of the home row at once, then its whole catalog on TMDB
    var subviewLoading by remember { mutableStateOf(false) }
    var subviewCount by remember { mutableIntStateOf(0) }
    val subviewShelves by produceState(emptyList<VodShelf>(), platform, genre, libraryGenre, kind, library.titles, indexVersion) {
        val openedLibraryGenre = libraryGenre
        if (openedLibraryGenre != null && kind != null) {
            value = withContext(Dispatchers.Default) {
                val titles = library.titles.filter { it.kind == kind && openedLibraryGenre in it.genres }
                subviewCount = titles.size
                VodBrowse.libraryGenreShelves(titles, facts())
            }
            return@produceState
        }
        val openedGenre = genre
        if (openedGenre != null && kind != null) {
            subviewCount = 0
            value = emptyList()
            subviewLoading = true
            try {
                val catalog = genreCatalog(openedGenre, kind)
                value = withContext(Dispatchers.Default) {
                    val matcher = matcherFor(library.titles, rankings) ?: VodRankings.Matcher(library.titles, facts())
                    fun matched(items: List<RankedItem>) = items.mapNotNull { matcher.match(it) }.filter { it.kind == kind }.distinctBy { it.key }
                    val popular = matched(catalog.popular)
                    val recentBest = matched(catalog.recentBest)
                    val allTimeBest = matched(catalog.allTimeBest)
                    subviewCount = (popular + recentBest + allTimeBest).distinctBy { it.key }.size
                    VodBrowse.genreShelves(popular, recentBest, allTimeBest)
                }
            } finally {
                subviewLoading = false
            }
            return@produceState
        }
        val opened = platform
        if (opened == null || kind == null) {
            value = emptyList()
            return@produceState
        }
        val homeRow = rowsOfKind.firstOrNull { it.platform == opened }?.titles.orEmpty()
        subviewCount = homeRow.size
        value = withContext(Dispatchers.Default) { VodBrowse.platformShelves(homeRow, facts()) }
        subviewLoading = true
        try {
            val catalog = platformCatalog(opened, kind)
            value = withContext(Dispatchers.Default) {
                val matcher = matcherFor(library.titles, rankings) ?: VodRankings.Matcher(library.titles, facts())
                fun matched(items: List<RankedItem>) = items.mapNotNull { matcher.match(it) }.filter { it.kind == kind }.distinctBy { it.key }
                val titles = matched(catalog.popular).ifEmpty { homeRow }
                val originals = matched(catalog.originals)
                subviewCount = (titles + originals).distinctBy { it.key }.size
                VodBrowse.platformShelves(titles, facts(), originals)
            }
        } finally {
            subviewLoading = false
        }
    }
    // The first poster of the service once its rows are there
    LaunchedEffect(platform, genre, libraryGenre, subviewShelves.isNotEmpty()) {
        if ((platform != null || genre != null || libraryGenre != null) && subviewShelves.isNotEmpty()) {
            rowsState.scrollToItem(0)
            firstFocus.requestFocusWithRetry(attempts = 40)
        }
    }
    val listed by produceState(emptyList<VodTitle>(), library.titles, kind, filter, indexVersion) {
        value = if (kind == null) emptyList() else withContext(Dispatchers.Default) { VodBrowse.filter(library.titles, kind, filter, facts()) }
    }
    // Movies and Series tabs: rows (latest releases, best rated, genres) of the filtered titles, or all of them in a grid
    var gridView by rememberSaveable { mutableStateOf(false) }
    val shelves by produceState(emptyList<VodShelf>(), listed, indexVersion) {
        value = if (listed.isEmpty()) emptyList() else withContext(Dispatchers.Default) { VodBrowse.shelves(listed, facts()) }
    }
    val options by produceState(VodFilterOptions(), library.titles, kind, indexVersion) {
        value = if (kind == null) VodFilterOptions() else withContext(Dispatchers.Default) { VodBrowse.options(library.titles, kind, facts()) }
    }
    val hero = focusedKey?.let { library.byKey[it] } ?: library.featured
    val progressByTitle = remember(progress) {
        progress.values.filter { !it.finished }.groupBy { it.titleKey }.mapValues { (_, list) -> list.maxBy { it.updatedAt }.fraction }
    }

    // The current tab right away: the button that opened this screen is gone with its focus, and a large
    // library takes a while to load
    LaunchedEffect(Unit) {
        if (rows.isEmpty() && listed.isEmpty()) tabFocus.requestFocusWithRetry()
    }
    // First card once the content is there
    LaunchedEffect(kindRows.isNotEmpty(), listed.isNotEmpty(), shelves.isNotEmpty(), gridView, tab) {
        val tabReady = if (gridView) listed.isNotEmpty() else shelves.isNotEmpty()
        if ((kind != null && (tabReady || kindRows.isNotEmpty())) || tab == VodTab.COLLECTIONS) {
            firstFocus.requestFocusWithRetry(attempts = 40)
        }
    }
    // Content replaced under the focused card (library loaded, rows rebuilt): the focus comes back to
    // the first card, else the remote would act on nothing
    val dialogOpen = railOpen || menuFor != null || pickerFor != null || openFilter != null || naming != null || creating
    var hadFocus by remember { mutableStateOf(false) }
    var focusLost by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusLost) {
        if (focusLost == 0) return@LaunchedEffect
        delay(FOCUS_RECOVERY_DELAY_MS)
        if (!hadFocus && !dialogOpen) firstFocus.requestFocusWithRetry()
    }

    val background = if (immersive) ImmersiveBackground else LocalAppColors.current.background
    CompositionLocalProvider(LocalImmersive provides immersive) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .onFocusChanged { state ->
                if (hadFocus && !state.hasFocus && !dialogOpen) focusLost++
                hadFocus = state.hasFocus
            }
    ) {
        // Wide image of the focused title, fading into the background (immersive: over the whole screen)
        if (kind != null && !gridView && hero != null) {
            Box(
                modifier = if (immersive) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(if (compact) 1f else 0.62f)
                        .height(if (compact) 220.dp else 300.dp)
                }
            ) {
                HeroBackdrop(title = hero, alpha = if (immersive) 1f else 0.55f, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (immersive) {
                                Brush.horizontalGradient(0f to background.copy(alpha = 0.94f), 0.45f to background.copy(alpha = 0.55f), 0.8f to Color.Transparent)
                            } else {
                                Brush.horizontalGradient(listOf(background, Color.Transparent))
                            }
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            // Immersive: darker at the top too, under the glass bar and the time
                            if (immersive) Brush.verticalGradient(0f to background.copy(alpha = 0.6f), 0.2f to Color.Transparent, 0.4f to Color.Transparent, 0.72f to background.copy(alpha = 0.85f), 1f to background)
                            else Brush.verticalGradient(listOf(Color.Transparent, background))
                        )
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(start = screenMargin(48.dp), end = screenMargin(48.dp), top = if (immersive) 18.dp else 26.dp)) {
            val tabs: @Composable () -> Unit = {
                VodTab.entries.forEach { entry ->
                    VodChip(
                        label = stringResource(entry.labelRes),
                        selected = entry == tab,
                        onClick = { tab = entry },
                        modifier = if (entry == tab) Modifier.focusRequester(tabFocus) else Modifier
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            if (nav != null && immersive) {
                // The sections in the glass bar (movies and series switch the tab here), the state of the library
                // on the left, the time on the right
                Box(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    val status = when {
                        library.loading -> stringResource(if (library.titles.isEmpty()) R.string.vod_loading else R.string.vod_updating)
                        indexProgress.running -> stringResource(R.string.vod_index_progress, indexProgress.indexed * 100 / indexProgress.total.coerceAtLeast(1))
                        else -> library.error?.resolve()
                    }
                    status?.let {
                        Text(
                            text = it,
                            fontSize = 12.sp,
                            color = if (library.error != null && !library.loading) LiveRed.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterStart).widthIn(max = 200.dp)
                        )
                    }
                    GlassNavBar(
                        nav = ScreenNav(
                            onHome = nav.onHome,
                            onGuide = nav.onGuide,
                            onVod = { tab = if (it == VodKind.SERIES) VodTab.SERIES else VodTab.MOVIES },
                            onRecordings = nav.onRecordings,
                            onSearch = onOpenSearch,
                            onSettings = onOpenSettings,
                            updateAvailable = nav.updateAvailable
                        ),
                        current = when (tab) {
                            VodTab.MOVIES -> NavSection.MOVIES
                            VodTab.SERIES -> NavSection.SERIES
                            VodTab.COLLECTIONS -> null
                        },
                        currentFocus = tabFocus,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        GlassTab(
                            label = stringResource(R.string.vod_tab_collections),
                            selected = tab == VodTab.COLLECTIONS,
                            onClick = { tab = VodTab.COLLECTIONS },
                            modifier = if (tab == VodTab.COLLECTIONS) Modifier.focusRequester(tabFocus) else Modifier
                        )
                    }
                    GlassClock(modifier = Modifier.align(Alignment.CenterEnd))
                }
            } else {
            // Header: tabs, status, search, refresh, back to live TV; on a phone, the tabs under the title
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(tab.labelRes),
                    fontSize = if (compact) 18.sp else 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    // Phone: the buttons on the right first
                    maxLines = if (compact) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (compact) Modifier.weight(1f, fill = false) else Modifier
                )
                Spacer(modifier = Modifier.width(if (compact) 12.dp else 20.dp))
                if (!compact) tabs()
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
                RoundIconButton(icon = Icons.Filled.Settings, contentDescription = stringResource(R.string.vod_settings), onClick = onOpenSettings)
                Spacer(modifier = Modifier.width(10.dp))
                RoundIconButton(icon = Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.vod_back_to_tv), onClick = onBack)
            }
            if (compact) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .horizontalScroll(rememberScrollState())
                ) { tabs() }
            }
            }
            newCategories?.let { (source, count) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.vod_new_categories, count, count, source),
                        fontSize = 13.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    VodChip(label = stringResource(R.string.vod_new_categories_choose), selected = false, onClick = onChooseNewCategories)
                    VodChip(label = stringResource(R.string.vod_new_categories_dismiss), selected = false, onClick = onDismissNewCategories)
                }
            }

            if (library.titles.isEmpty()) {
                if (!library.loading) EmptyLibrary()
                return@Column
            }

            when (tab) {
                VodTab.MOVIES, VodTab.SERIES -> {
                    // The focused title over its picture (rows only; low screen: none)
                    if (!gridView && !screen.short) Hero(hero, large = immersive)
                    val openedPlatform = platform
                    val openedGenre = genre
                    val openedLibraryGenre = libraryGenre
                    val opened = openedPlatform != null || openedGenre != null || openedLibraryGenre != null
                    if (opened) {
                        SubviewHeader(
                            label = openedPlatform?.label ?: openedGenre?.let { genreLabel(it) } ?: openedLibraryGenre.orEmpty(),
                            logo = openedPlatform?.let { platformLogos[it] },
                            count = subviewCount,
                            loading = subviewLoading
                        )
                    } else FilterBar(
                        filter = filter,
                        count = listed.size,
                        onOpen = { openFilter = it },
                        onClear = { kind?.let { onFiltersChange(it, VodFilter(sort = filter.sort)) } },
                        gridView = gridView,
                        onToggleView = { gridView = !gridView }
                    )
                    // Immersive: the focused row comes up whole under the filters (its title, its posters and their
                    // names), the rows keeping their own way of following the focus
                    val rowFollow = LocalBringIntoViewSpec.current
                    val lead = with(LocalDensity.current) { RowLead.toPx() }
                    val listFollow = remember(immersive, lead) { if (immersive) RowStartFollow(lead) else rowFollow }
                    if (opened) CompositionLocalProvider(LocalBringIntoViewSpec provides listFollow) { LazyColumn(
                        state = rowsState,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // The rows of the opened service: popular, latest releases, best rated, genres
                        subviewShelves.forEachIndexed { index, shelf ->
                            item(key = "platform:${shelf.kind}:${shelf.genre}") {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowFollow) {
                                PosterRow(
                                    label = shelfTitle(shelf),
                                    titles = shelf.titles,
                                    progress = progressByTitle,
                                    onOpenTitle = onOpenTitle,
                                    onFocused = { focusedKey = it.key },
                                    onLongClick = { menuFor = it to null },
                                    findPoster = findPoster,
                                    rowFocus = rowRequester("platform:${shelf.kind}:${shelf.genre}"),
                                    onRowFocused = {
                                        currentRow = index
                                        currentKey = "platform:${shelf.kind}:${shelf.genre}"
                                    },
                                    onLeftAtStart = if (immersive) ({ railOpen = true }) else null,
                                    firstFocus = if (index == 0) firstFocus else null
                                )
                                }
                            }
                        }
                    } } else if (!gridView) CompositionLocalProvider(LocalBringIntoViewSpec provides listFollow) { LazyColumn(
                        state = rowsState,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Under the featured title, the streaming services: each opens its own rows
                        if (platforms.isNotEmpty()) {
                            item(key = PLATFORMS_ROW) {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowFollow) {
                                    SubviewRow(
                                        label = stringResource(R.string.vod_platforms_row),
                                        entries = platforms.map { SubviewEntry(it, it.name, it.label, platformLogos[it]) },
                                        onOpen = {
                                            gridView = false
                                            platform = it
                                        },
                                        onEntryFocused = { focusedPlatform = it },
                                        focusOf = ::platformRequester,
                                        rowFocus = rowRequester(PLATFORMS_ROW),
                                        onRowFocused = {
                                            currentRow = 0
                                            currentKey = PLATFORMS_ROW
                                        },
                                        onLeftAtStart = if (immersive) ({ railOpen = true }) else null,
                                        firstFocus = firstFocus
                                    )
                                }
                            }
                        }
                        // Then the main genres: each opens its best of the last years, of all time, its most popular
                        if (genres.isNotEmpty()) {
                            item(key = GENRES_ROW) {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowFollow) {
                                    SubviewRow(
                                        label = stringResource(R.string.vod_genres_row),
                                        entries = genres.map { SubviewEntry(it, it.name, genreLabel(it), null) },
                                        onOpen = {
                                            gridView = false
                                            genre = it
                                        },
                                        focusOf = ::genreRequester,
                                        rowFocus = rowRequester(GENRES_ROW),
                                        onRowFocused = {
                                            currentRow = if (platforms.isNotEmpty()) 1 else 0
                                            currentKey = GENRES_ROW
                                        },
                                        onLeftAtStart = if (immersive) ({ railOpen = true }) else null,
                                        firstFocus = if (platforms.isEmpty()) firstFocus else null
                                    )
                                }
                            }
                        } else if (libraryGenres.isNotEmpty()) {
                            item(key = GENRES_ROW) {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowFollow) {
                                    SubviewRow(
                                        label = stringResource(R.string.vod_genres_row),
                                        entries = libraryGenres.map { SubviewEntry(it, it, it, null) },
                                        onOpen = {
                                            gridView = false
                                            libraryGenre = it
                                        },
                                        focusOf = ::libraryGenreRequester,
                                        rowFocus = rowRequester(GENRES_ROW),
                                        onRowFocused = {
                                            currentRow = if (platforms.isNotEmpty()) 1 else 0
                                            currentKey = GENRES_ROW
                                        },
                                        onLeftAtStart = if (immersive) ({ railOpen = true }) else null,
                                        firstFocus = if (platforms.isEmpty()) firstFocus else null
                                    )
                                }
                            }
                        }
                        val rowsBefore = (if (platforms.isNotEmpty()) 1 else 0) + (if (genres.isNotEmpty() || libraryGenres.isNotEmpty()) 1 else 0)
                        // Then the rows of this kind: continue watching, my list, collections, TMDB rankings
                        kindRows.forEachIndexed { rowIndex, row ->
                            item(key = "${row.kind}:${row.name}") {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowFollow) {
                                PosterRow(
                                    label = rowTitle(row),
                                    titles = row.titles,
                                    ranked = row.ranked,
                                    progress = progressByTitle,
                                    onOpenTitle = onOpenTitle,
                                    onFocused = { focusedKey = it.key },
                                    onLongClick = { menuFor = it to row },
                                    findPoster = findPoster,
                                    rowFocus = rowRequester("${row.kind}:${row.name}"),
                                    onRowFocused = {
                                        currentRow = rowsBefore + rowIndex
                                        currentKey = "${row.kind}:${row.name}"
                                    },
                                    onLeftAtStart = if (immersive) ({ railOpen = true }) else null,
                                    firstFocus = if (rowIndex == 0 && rowsBefore == 0) firstFocus else null
                                )
                                }
                            }
                        }
                        shelves.forEachIndexed { index, shelf ->
                            item(key = "${shelf.kind}:${shelf.genre}") {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowFollow) {
                                PosterRow(
                                    label = shelfTitle(shelf),
                                    titles = shelf.titles,
                                    progress = progressByTitle,
                                    onOpenTitle = onOpenTitle,
                                    onFocused = { focusedKey = it.key },
                                    onLongClick = { menuFor = it to null },
                                    findPoster = findPoster,
                                    rowFocus = rowRequester("${shelf.kind}:${shelf.genre}"),
                                    onRowFocused = {
                                        currentRow = rowsBefore + kindRows.size + index
                                        currentKey = "${shelf.kind}:${shelf.genre}"
                                    },
                                    onLeftAtStart = if (immersive) ({ railOpen = true }) else null,
                                    firstFocus = if (index == 0 && kindRows.isEmpty() && rowsBefore == 0) firstFocus else null
                                )
                                }
                            }
                        }
                    } } else LazyVerticalGrid(
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

        // The rows of this section, to jump to one (Right or Back closes it)
        val menuEntries = when {
            kind == null -> emptyList()
            platform != null || genre != null || libraryGenre != null -> subviewShelves.map { "platform:${it.kind}:${it.genre}" to shelfTitle(it) }
            gridView -> emptyList()
            else -> (if (platforms.isNotEmpty()) listOf(PLATFORMS_ROW to stringResource(R.string.vod_platforms_row)) else emptyList()) +
                (if (genres.isNotEmpty() || libraryGenres.isNotEmpty()) listOf(GENRES_ROW to stringResource(R.string.vod_genres_row)) else emptyList()) +
                kindRows.map { "${it.kind}:${it.name}" to rowTitle(it) } + shelves.map { "${it.kind}:${it.genre}" to shelfTitle(it) }
        }
        AnimatedVisibility(
            visible = railOpen && menuEntries.isNotEmpty(),
            enter = fadeIn() + slideInHorizontally { -it / 3 },
            exit = fadeOut() + slideOutHorizontally { -it / 3 },
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            ListsMenu(
                title = platform?.label ?: genre?.let { genreLabel(it) } ?: libraryGenre ?: stringResource(tab.labelRes),
                entries = menuEntries,
                current = currentRow,
                focus = railFocus,
                onPick = { index, key ->
                    railOpen = false
                    currentRow = index
                    currentKey = key
                    scope.launch {
                        rowsState.scrollToItem(index)
                        rowRequester(key).requestFocusWithRetry()
                    }
                },
                onClose = ::closeRail
            )
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
            // Several countries: the dialog stays open while they are toggled
            FilterField.COUNTRY -> MultiFilterDialog(
                title = stringResource(R.string.vod_filter_country),
                subtitle = tmdbNote,
                options = options.countries,
                selected = filter.countries,
                label = { countryName(it) },
                onToggle = { code ->
                    onFiltersChange(kind, filter.copy(countries = if (code in filter.countries) filter.countries - code else filter.countries + code))
                },
                onClear = { onFiltersChange(kind, filter.copy(countries = emptySet())); close() },
                onDismiss = close
            )
            FilterField.AUDIO -> FilterDialog(
                title = stringResource(R.string.vod_filter_audio),
                subtitle = stringResource(R.string.vod_filter_languages_note),
                options = options.audio,
                selected = filter.audio,
                label = { languageName(it) ?: it },
                onSelect = { onFiltersChange(kind, filter.copy(audio = it)); close() },
                onDismiss = close
            )
            FilterField.SUBTITLES -> FilterDialog(
                title = stringResource(R.string.vod_filter_subtitles),
                subtitle = stringResource(R.string.vod_filter_languages_note),
                options = options.subtitles,
                selected = filter.subtitles,
                label = { languageName(it) ?: it },
                onSelect = { onFiltersChange(kind, filter.copy(subtitles = it)); close() },
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
    firstFocus: FocusRequester? = null,
    // Its first poster, to come back to the row; a poster of the row focused; Left on its first poster
    rowFocus: FocusRequester? = null,
    onRowFocused: () -> Unit = {},
    onLeftAtStart: (() -> Unit)? = null
) {
    var focusedIndex by remember { mutableIntStateOf(-1) }
    Column {
        label?.let { SectionLabel(text = it) }
        if (titles.isEmpty()) {
            Text(text = stringResource(R.string.vod_collection_empty), fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        // A little wider on the left than its place, the first poster still at the edge of the labels: once focused
        // and enlarged, it is not cut
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = RowBleed, top = 10.dp, end = 24.dp),
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val open = onLeftAtStart ?: return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && focusedIndex == 0) {
                        open()
                        true
                    } else {
                        false
                    }
                }
                .layout { measurable, constraints ->
                val bleed = RowBleed.roundToPx()
                val placeable = measurable.measure(
                    if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + bleed) else constraints
                )
                layout((placeable.width - bleed).coerceAtLeast(0), placeable.height) { placeable.place(-bleed, 0) }
            }
        ) {
            itemsIndexed(titles, key = { _, title -> title.key }) { index, title ->
                PosterCard(
                    title = title,
                    progress = progress[title.key],
                    rank = if (ranked) index + 1 else null,
                    onClick = { onOpenTitle(title) },
                    onLongClick = { onLongClick(title) },
                    onFocused = {
                        focusedIndex = index
                        onRowFocused()
                        onFocused(title)
                    },
                    findPoster = findPoster,
                    modifier = Modifier
                        .then(if (firstFocus != null && index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (rowFocus != null && index == 0) Modifier.focusRequester(rowFocus) else Modifier)
                )
            }
        }
    }
}

private const val PREFETCH_DELAY_MS = 700L
private const val LIBRARY_GENRES = 16
/** Filters of the Movies and Series tabs, each opening its values; the count of titles shown. */
@Composable
private fun FilterBar(
    filter: VodFilter,
    count: Int,
    onOpen: (FilterField) -> Unit,
    onClear: () -> Unit,
    gridView: Boolean,
    onToggleView: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(top = 14.dp)
    ) {
        // Rows of the titles, or all of them in a grid
        item {
            VodChip(
                label = stringResource(if (gridView) R.string.vod_view_rows else R.string.vod_view_grid),
                selected = false,
                onClick = onToggleView
            )
        }
        item {
            VodChip(
                label = stringResource(R.string.vod_filter_country) +
                    (filter.countries.takeIf { it.isNotEmpty() }?.let { " : " + countriesLabel(it) } ?: " ▾"),
                selected = filter.countries.isNotEmpty(),
                onClick = { onOpen(FilterField.COUNTRY) }
            )
        }
        item {
            VodChip(
                label = stringResource(R.string.vod_filter_audio) + (filter.audio?.let { " : " + (languageName(it) ?: it) } ?: " ▾"),
                selected = filter.audio != null,
                onClick = { onOpen(FilterField.AUDIO) }
            )
        }
        item {
            VodChip(
                label = stringResource(R.string.vod_filter_subtitles) + (filter.subtitles?.let { " : " + (languageName(it) ?: it) } ?: " ▾"),
                selected = filter.subtitles != null,
                onClick = { onOpen(FilterField.SUBTITLES) }
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

/** The focused title: name, year, kind, rating, genres and summary; [large] in the immersive style. */
@Composable
private fun Hero(title: VodTitle?, large: Boolean = false) {
    val primary = if (large) Color.White else TextPrimary
    val secondary = if (large) Color.White.copy(alpha = 0.78f) else TextSecondary
    Column(
        modifier = Modifier
            .fillMaxWidth(if (LocalScreen.current.compact) 1f else if (large) 0.5f else 0.55f)
            // Immersive: room left for a whole row of posters under the filters
            .height(if (large) 160.dp else 170.dp)
            .padding(top = 18.dp)
    ) {
        if (title == null) return@Column
        Text(
            text = stringResource(R.string.vod_featured).uppercase(),
            fontSize = 11.sp,
            fontWeight = if (large) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = 1.6.sp,
            color = secondary
        )
        Text(
            text = title.title,
            fontSize = if (large) 34.sp else 26.sp,
            fontWeight = if (large) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = primary,
            maxLines = 1,
            lineHeight = if (large) 38.sp else 30.sp,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (large) 8.dp else 4.dp)) {
            val meta = listOfNotNull(
                title.year?.toString(),
                stringResource(if (title.kind == VodKind.MOVIE) R.string.vod_movie else R.string.vod_series),
                title.genres.take(3).joinToString(", ").ifBlank { null }
            ).joinToString(" · ")
            Text(text = meta, fontSize = 13.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            title.rating?.let {
                Spacer(modifier = Modifier.width(10.dp))
                RatingText(rating = it, fontSize = 13, color = primary)
            }
            title.entries.flatMap { it.qualityTags }.distinct().take(2).forEach {
                Spacer(modifier = Modifier.width(8.dp))
                Tag(text = it, color = secondary)
            }
        }
        title.plot?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = secondary,
                maxLines = if (large) 2 else 3,
                lineHeight = 19.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Wide image of a title: the one of its provider, else (none, or missing on the server) the TMDB backdrop of
 * that name, looked up once the title stayed focused a moment.
 */
@Composable
private fun HeroBackdrop(title: VodTitle, alpha: Float, modifier: Modifier = Modifier) {
    var failed by remember(title.key) { mutableStateOf(false) }
    val url by produceState<String?>(null, title.key, failed) {
        value = title.backdropUrl.takeUnless { failed }
        if (value == null) {
            delay(BACKDROP_LOOKUP_DELAY_MS)
            value = ProgramPosterRepository.getBackdropUrl(title.title)
        }
    }
    FadeInImage(url = url, contentDescription = null, modifier = modifier, alpha = alpha, onError = { failed = true })
}

private const val BACKDROP_LOOKUP_DELAY_MS = 500L
// Room on the left of a row for its first poster enlarged, and above a focused poster for the title of its row
internal val RowBleed = 10.dp
private val RowLead = 34.dp

/** The list of rows scrolls so that the focused poster sits [lead] under its top (the title of its row above). */
@OptIn(ExperimentalFoundationApi::class)
private class RowStartFollow(private val lead: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - lead
}
// Behind the pictures of the immersive style: a deep blue black
private val ImmersiveBackground = Color(0xFF05070B)

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
internal fun VodChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val shape = RoundedCornerShape(50)
    // Immersive screens: glass over the picture, white when focused
    val glass = LocalImmersive.current
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (glass) Modifier.glass(shape) else Modifier)
            .touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = if (glass) {
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = GlassInk
            )
        } else {
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) TextPrimary.copy(alpha = 0.16f) else SurfaceDark,
                contentColor = if (selected) TextPrimary else TextSecondary,
                focusedContainerColor = TextPrimary,
                focusedContentColor = LocalAppColors.current.background
            )
        }
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
        )
    }
}
