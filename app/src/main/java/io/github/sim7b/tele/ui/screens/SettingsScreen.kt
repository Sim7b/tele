package io.github.sim7b.tele.ui.screens

import androidx.compose.ui.platform.LocalResources
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import io.github.sim7b.tele.ui.components.QrCode
import io.github.sim7b.tele.data.transfer.KeyEntryServer
import io.github.sim7b.tele.data.transfer.KeyEntryPage
import io.github.sim7b.tele.data.transfer.ConfigTransfer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import io.github.sim7b.tele.data.model.ChannelList
import io.github.sim7b.tele.data.model.PreviewDelay
import io.github.sim7b.tele.data.vod.PosterSize
import io.github.sim7b.tele.data.vod.VodFilter
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodRow
import io.github.sim7b.tele.data.vod.VodSort
import io.github.sim7b.tele.ui.vod.NameDialog
import io.github.sim7b.tele.ui.vod.countriesLabel
import io.github.sim7b.tele.ui.vod.countryName
import io.github.sim7b.tele.ui.vod.languageName
import io.github.sim7b.tele.ui.vod.sortLabel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.data.toUiText
import io.github.sim7b.tele.data.UiText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.XtreamClient
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.*
import io.github.sim7b.tele.data.bouquet.BouquetPreference
import io.github.sim7b.tele.data.model.CategoryMode
import io.github.sim7b.tele.data.model.QualityPreference
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.storage.AppAccentColor
import io.github.sim7b.tele.data.storage.AppThemeBackground
import io.github.sim7b.tele.data.storage.SettingsRepository
import io.github.sim7b.tele.data.storage.UiDensity
import io.github.sim7b.tele.ui.components.AdaptiveButtonRow
import io.github.sim7b.tele.ui.components.Device
import io.github.sim7b.tele.ui.components.LocalScreen
import io.github.sim7b.tele.ui.components.RoundIconButton
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.dialogWidth
import io.github.sim7b.tele.ui.components.PinDialog
import io.github.sim7b.tele.ui.components.groupLabel
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.components.touchClick
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.ui.platform.LocalUriHandler
import io.github.sim7b.tele.ui.theme.*
import io.github.sim7b.tele.update.UpdateState
import io.github.sim7b.tele.playback.AudioLeveling
import io.github.sim7b.tele.update.release as offeredRelease
import io.github.sim7b.tele.data.update.AppRelease
import kotlinx.coroutines.launch
import java.util.UUID

/** Sections of the settings, listed on the left. */
private enum class SettingsPage(@param:StringRes val labelRes: Int, @param:StringRes val hintRes: Int, val icon: ImageVector) {
    SOURCES(R.string.settings_nav_sources, R.string.settings_nav_sources_hint, Icons.Filled.List),
    CHANNELS(R.string.settings_nav_channels, R.string.settings_nav_channels_hint, Icons.Filled.Star),
    PLAYBACK(R.string.settings_nav_playback, R.string.settings_nav_playback_hint, Icons.Filled.PlayArrow),
    APPEARANCE(R.string.settings_nav_appearance, R.string.settings_nav_appearance_hint, Icons.Filled.Create),
    VOD(R.string.settings_nav_vod, R.string.settings_nav_vod_hint, Icons.Filled.AccountBox),
    PARENTAL(R.string.settings_nav_parental, R.string.settings_nav_parental_hint, Icons.Filled.Lock),
    SYSTEM(R.string.settings_nav_system, R.string.settings_nav_system_hint, Icons.Filled.Info)
}

/**
 * Settings, by section: a menu on the left (the focused section is shown), its settings on the right.
 * → enters the section, ← goes back to the menu, on the section it was opened from.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onTestConnection: suspend (SourceConfig) -> Result<UiText>,
    categories: List<Pair<String, Int>> = emptyList(),
    unmatchedChannels: List<String> = emptyList(),
    onRefreshPlaylist: () -> Unit = {},
    onRefreshEpg: () -> Unit = {},
    onShowGuide: () -> Unit = {},
    // Choice of the categories of movies and series loaded from a source
    onChooseVodCategories: (SourceConfig) -> Unit = {},
    // Opened from the movies and series: on their section, on its first setting
    startOnVod: Boolean = false,
    installedVersion: String = "",
    updatesSupported: Boolean = false,
    updateState: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: (AppRelease) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configs by repository.configs.collectAsState()
    val enabledConfigs by repository.enabledConfigs.collectAsState()
    val categoryMode by repository.categoryMode.collectAsState()
    val currentDensity by repository.uiDensity.collectAsState()
    val currentTheme by repository.themeBackground.collectAsState()
    val currentAccent by repository.accentColor.collectAsState()
    val currentQuality by repository.qualityPreference.collectAsState()
    val previewDelay by repository.previewDelay.collectAsState()
    val matchFrameRate by repository.matchFrameRate.collectAsState()
    val audioLeveling by repository.audioLeveling.collectAsState()
    val timeshift by repository.timeshift.collectAsState()
    val vodEnabled by repository.vodEnabled.collectAsState()
    val tmdbKey by repository.tmdbKey.collectAsState()
    val vodDisplay by repository.vodDisplay.collectAsState()
    val vodSelections by repository.vodSelections.collectAsState()
    val channelLists by repository.channelLists.collectAsState()
    // Parental control: code, what it locks, code entered
    val parental = repository.parental
    val parentalHasPin by parental.hasPin.collectAsState()
    val parentalUnlocked by parental.unlocked.collectAsState()
    val lockedGroups by parental.lockedGroups.collectAsState()
    val vodLocked by parental.vodLocked.collectAsState()
    var pinStep by remember { mutableStateOf<PinStep?>(null) }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    // The rows of the section change with the code: the focus goes back to its first one
    var parentalRefocus by remember { mutableIntStateOf(0) }
    var renamingList by remember { mutableStateOf<ChannelList?>(null) }
    var editingKey by remember { mutableStateOf<ApiKeyService?>(null) }
    val openSubtitlesKey by repository.openSubtitlesKey.collectAsState()
    val subtitleLanguages by repository.subtitleLanguages.collectAsState()
    val currentBouquet by repository.bouquet.collectAsState()
    val hiddenIds by repository.hiddenIds.collectAsState()
    val hiddenGroups by repository.hiddenGroups.collectAsState()

    // An available update opens the settings on its section
    var page by rememberSaveable {
        mutableStateOf(
            when {
                startOnVod -> SettingsPage.VOD
                updateState is UpdateState.Available || updateState is UpdateState.Ready -> SettingsPage.SYSTEM
                else -> SettingsPage.SOURCES
            }
        )
    }
    val pageFocus = remember { SettingsPage.entries.associateWith { FocusRequester() } }
    // After a source is deleted, focus returns to "Add a source" (not to the menu)
    val addSourceFocus = remember { FocusRequester() }
    var refocusAfterDelete by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocusAfterDelete) {
        if (refocusAfterDelete > 0) addSourceFocus.requestFocusWithRetry()
    }

    var editingConfig by remember { mutableStateOf<SourceConfig?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    // Configuration transfer with another TV of the local network
    var showSendConfig by remember { mutableStateOf(false) }
    var showUnmatched by remember { mutableStateOf(false) }
    var showReceiveConfig by remember { mutableStateOf(false) }

    // Phone: the menu, then the section opened from it (Back returns to the menu)
    val compact = LocalScreen.current.compact
    var pageOpen by rememberSaveable { mutableStateOf(startOnVod) }
    val showMenu = !compact || !pageOpen
    val showPage = !compact || pageOpen

    // The server form is a Dialog: it handles the Back key itself
    BackHandler {
        onBack()
    }
    // Opened from the movies and series: Back goes back to them
    BackHandler(enabled = compact && pageOpen && !startOnVod) {
        pageOpen = false
    }


    // Choices of the setting opened (a panel on the right side), and the row the focus comes back to
    var panel by remember { mutableStateOf<SettingsPanel?>(null) }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    // First row of the open section: → from the menu lands on it (then on the row last focused there)
    val contentStart = remember { FocusRequester() }
    LaunchedEffect(parentalRefocus) {
        if (parentalRefocus > 0) contentStart.requestFocusWithRetry()
    }
    LaunchedEffect(Unit) {
        if (!startOnVod || !contentStart.requestFocusWithRetry()) pageFocus.getValue(page).requestFocusWithRetry()
    }
    fun focusOf(key: Any): FocusRequester = rowFocus.getOrPut(key.toString()) { FocusRequester() }
    var lastRow by remember { mutableStateOf<String?>(null) }
    fun open(next: SettingsPanel) {
        lastRow = next.toString()
        panel = next
    }
    val nothingOpen = panel == null && editingKey == null && renamingList == null && !showUnmatched && pinStep == null
    LaunchedEffect(nothingOpen) {
        val row = lastRow ?: return@LaunchedEffect
        if (!nothingOpen) return@LaunchedEffect
        // The row may be gone (a list deleted): back to the menu
        if (rowFocus[row]?.requestFocusWithRetry() != true) pageFocus.getValue(page).requestFocusWithRetry()
    }

    // Key of a service: entered at once when there is none, else changed or removed
    @Composable
    fun KeyRow(service: ApiKeyService, key: String) {
        val keyPanel = SettingsPanel.KeyActions(service)
        SettingRow(
            label = stringResource(service.rowRes),
            value = stringResource(if (key.isBlank()) R.string.settings_tmdb_none else R.string.settings_tmdb_saved),
            onClick = {
                if (key.isBlank()) {
                    lastRow = keyPanel.toString()
                    editingKey = service
                } else {
                    open(keyPanel)
                }
            },
            modifier = Modifier.focusRequester(focusOf(keyPanel))
        )
    }

    val resources = LocalResources.current
    val themeBgColor = Color(currentTheme.backgroundColorHex)
    val accentColorObj = Color(currentAccent.colorHex)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBgColor)
            .padding(start = screenMargin(48.dp), end = screenMargin(48.dp), top = 26.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (compact && pageOpen) page.labelRes else R.string.settings_title),
                        fontSize = if (compact) 22.sp else 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(if (compact && pageOpen) page.hintRes else R.string.settings_subtitle),
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }

                ActionButton(
                    label = stringResource(R.string.settings_back),
                    icon = Icons.Filled.ArrowBack,
                    onClick = { if (compact && pageOpen) pageOpen = false else onBack() }
                )
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // Menu: focusing a section shows it; coming back from the right lands on the open one
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .then(
                            when {
                                !compact -> Modifier.width(230.dp)
                                showMenu -> Modifier.fillMaxWidth()
                                else -> Modifier
                            }
                        )
                        // Entering the menu (← from a section) lands on the open section, not the nearest one
                        .focusProperties { onEnter = { pageFocus.getValue(page).requestFocus() } }
                        .focusGroup()
                ) {
                    if (showMenu) {
                        SettingsPage.entries.forEach { entry ->
                            SettingsNavItem(
                                page = entry,
                                selected = entry == page,
                                badge = entry == SettingsPage.SYSTEM && (updateState is UpdateState.Available || updateState is UpdateState.Ready),
                                onFocused = { page = entry },
                                onOpen = {
                                    page = entry
                                    pageOpen = true
                                },
                                modifier = Modifier.focusRequester(pageFocus.getValue(entry))
                            )
                        }
                    }
                }

                if (!compact) Spacer(modifier = Modifier.width(24.dp))

                // Content of the open section, from its top: one row per setting, OK opens its choices
                key(page) {
                    if (showPage) LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .focusRestorer(contentStart),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (page) {
                            SettingsPage.SOURCES -> {
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_add_source),
                                        icon = Icons.Filled.Add,
                                        onClick = {
                                            editingConfig = SourceConfig(
                                                id = UUID.randomUUID().toString(),
                                                name = "",
                                                serverUrl = "",
                                                apiKey = ""
                                            )
                                            isAddingNew = true
                                        },
                                        modifier = Modifier.focusRequester(addSourceFocus).focusRequester(contentStart)
                                    )
                                }
                                items(configs, key = { it.id }) { config ->
                                    ServerConfigCard(
                                        config = config,
                                        isActive = config.isActive,
                                        accentColorObj = accentColorObj,
                                        onToggleEnabled = { repository.setSourceEnabled(config.id, !config.isActive) },
                                        onEdit = {
                                            editingConfig = config
                                            isAddingNew = false
                                        },
                                        onDelete = {
                                            repository.deleteConfig(config.id)
                                            refocusAfterDelete++
                                        },
                                        onMoveUp = if (config.id != configs.first().id) ({ repository.moveSource(config.id, -1) }) else null,
                                        onMoveDown = if (config.id != configs.last().id) ({ repository.moveSource(config.id, +1) }) else null
                                    )
                                }
                                item {
                                    SettingGroup(
                                        title = stringResource(R.string.settings_sync_title),
                                        caption = syncMessage ?: stringResource(R.string.settings_sync_description)
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_refresh_channels),
                                        icon = Icons.Filled.Refresh,
                                        chevron = false,
                                        onClick = {
                                            onRefreshPlaylist()
                                            syncMessage = resources.getString(R.string.settings_refresh_channels_started)
                                        }
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_refresh_guide),
                                        icon = Icons.Filled.DateRange,
                                        chevron = false,
                                        onClick = {
                                            onRefreshEpg()
                                            syncMessage = resources.getString(R.string.settings_refresh_guide_started)
                                        }
                                    )
                                }
                                // Another TV: send / receive the whole configuration on the local network
                                item {
                                    SettingGroup(
                                        title = stringResource(R.string.settings_transfer_title),
                                        caption = stringResource(R.string.settings_transfer_description)
                                    )
                                }
                                item {
                                    SettingRow(label = stringResource(R.string.settings_transfer_send), icon = Icons.Filled.Share, onClick = { showSendConfig = true })
                                }
                                item {
                                    SettingRow(label = stringResource(R.string.settings_transfer_receive), icon = Icons.Filled.Add, onClick = { showReceiveConfig = true })
                                }
                            }

                            SettingsPage.CHANNELS -> {
                                // Reference bouquet: channel names, numbers, categories, grouped versions
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_bouquet_title),
                                        value = stringResource(currentBouquet.labelRes),
                                        description = if (currentBouquet != BouquetPreference.NONE && unmatchedChannels.isEmpty()) {
                                            stringResource(R.string.settings_unmatched_all_recognized)
                                        } else {
                                            null
                                        },
                                        onClick = { open(SettingsPanel.Bouquet) },
                                        modifier = Modifier.focusRequester(focusOf(SettingsPanel.Bouquet)).focusRequester(contentStart)
                                    )
                                }
                                if (currentBouquet != BouquetPreference.NONE && unmatchedChannels.isNotEmpty()) {
                                    item {
                                        SettingRow(
                                            label = pluralStringResource(R.plurals.settings_unmatched_button, unmatchedChannels.size, unmatchedChannels.size),
                                            icon = Icons.Filled.Info,
                                            onClick = {
                                                lastRow = UNMATCHED_ROW
                                                showUnmatched = true
                                            },
                                            modifier = Modifier.focusRequester(focusOf(UNMATCHED_ROW))
                                        )
                                    }
                                }
                                // Same category name in several enabled sources: one category, or one per source
                                if (enabledConfigs.size > 1) {
                                    item {
                                        SettingRow(
                                            label = stringResource(R.string.settings_category_mode),
                                            value = stringResource(categoryMode.labelRes),
                                            onClick = { open(SettingsPanel.CategoryModes) },
                                            modifier = Modifier.focusRequester(focusOf(SettingsPanel.CategoryModes))
                                        )
                                    }
                                }
                                if (categories.isNotEmpty()) {
                                    item {
                                        SettingRow(
                                            label = stringResource(R.string.settings_categories_title),
                                            value = "${categories.count { it.first !in hiddenGroups }} / ${categories.size}",
                                            onClick = { open(SettingsPanel.Categories) },
                                            modifier = Modifier.focusRequester(focusOf(SettingsPanel.Categories))
                                        )
                                    }
                                }
                                if (hiddenIds.isNotEmpty()) {
                                    item {
                                        SettingRow(
                                            label = stringResource(R.string.settings_hidden_channels_title),
                                            value = pluralStringResource(R.plurals.channel_count, hiddenIds.size, hiddenIds.size),
                                            onClick = { open(SettingsPanel.Hidden) },
                                            modifier = Modifier.focusRequester(focusOf(SettingsPanel.Hidden))
                                        )
                                    }
                                }
                                // Lists of channels made from the menu of a channel: renamed or deleted here
                                item {
                                    SettingGroup(
                                        title = stringResource(R.string.settings_channel_lists_title),
                                        caption = stringResource(R.string.settings_channel_lists_description)
                                    )
                                }
                                items(channelLists, key = { "list:" + it.id }) { list ->
                                    val listPanel = SettingsPanel.ListActions(list.id)
                                    SettingRow(
                                        label = list.name,
                                        value = pluralStringResource(R.plurals.channel_count, list.channelIds.size, list.channelIds.size),
                                        onClick = { open(listPanel) },
                                        modifier = Modifier.focusRequester(focusOf(listPanel))
                                    )
                                }
                            }

                            SettingsPage.PLAYBACK -> {
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_quality_title),
                                        value = stringResource(currentQuality.labelRes),
                                        onClick = { open(SettingsPanel.Quality) },
                                        modifier = Modifier.focusRequester(focusOf(SettingsPanel.Quality)).focusRequester(contentStart)
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_preview_title),
                                        value = previewDelayLabel(previewDelay),
                                        onClick = { open(SettingsPanel.Preview) },
                                        modifier = Modifier.focusRequester(focusOf(SettingsPanel.Preview))
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_audio_leveling),
                                        value = stringResource(audioLeveling.labelRes),
                                        onClick = { open(SettingsPanel.Leveling) },
                                        modifier = Modifier.focusRequester(focusOf(SettingsPanel.Leveling))
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_match_frame_rate),
                                        description = stringResource(R.string.settings_match_frame_rate_description),
                                        toggle = matchFrameRate,
                                        onClick = { repository.setMatchFrameRate(!matchFrameRate) }
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_timeshift),
                                        description = stringResource(R.string.settings_timeshift_description),
                                        toggle = timeshift,
                                        onClick = { repository.setTimeshift(!timeshift) }
                                    )
                                }
                                item { SettingGroup(title = stringResource(R.string.vod_filter_subtitles)) }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_subtitle_languages_title),
                                        value = if (subtitleLanguages.isEmpty()) {
                                            stringResource(R.string.settings_subtitle_languages_any)
                                        } else {
                                            subtitleLanguages.joinToString(", ") { languageName(it) ?: it }
                                        },
                                        onClick = { open(SettingsPanel.SubtitleLanguages) },
                                        modifier = Modifier.focusRequester(focusOf(SettingsPanel.SubtitleLanguages))
                                    )
                                }
                                item { KeyRow(ApiKeyService.OPENSUBTITLES, openSubtitlesKey) }
                            }

                            SettingsPage.APPEARANCE -> {
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_background),
                                        value = stringResource(currentTheme.labelRes),
                                        swatch = Color(currentTheme.backgroundColorHex),
                                        onClick = { open(SettingsPanel.Background) },
                                        modifier = Modifier.focusRequester(focusOf(SettingsPanel.Background)).focusRequester(contentStart)
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_accent_color),
                                        value = stringResource(currentAccent.labelRes),
                                        swatch = Color(currentAccent.colorHex),
                                        onClick = { open(SettingsPanel.Accent) },
                                        modifier = Modifier.focusRequester(focusOf(SettingsPanel.Accent))
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_density_title),
                                        value = stringResource(currentDensity.labelRes),
                                        onClick = { open(SettingsPanel.Density) },
                                        modifier = Modifier.focusRequester(focusOf(SettingsPanel.Density))
                                    )
                                }
                            }

                            SettingsPage.VOD -> {
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_vod_title),
                                        description = stringResource(if (vodEnabled) R.string.settings_vod_on_description else R.string.settings_vod_off_description),
                                        toggle = vodEnabled,
                                        onClick = { repository.setVodEnabled(!vodEnabled) },
                                        modifier = Modifier.focusRequester(contentStart)
                                    )
                                }
                                if (vodEnabled) {
                                    item { KeyRow(ApiKeyService.TMDB, tmdbKey) }
                                    // Categories loaded from each source (chosen at the first opening)
                                    item {
                                        SettingGroup(
                                            title = stringResource(R.string.settings_vod_categories_title),
                                            caption = stringResource(R.string.settings_vod_categories_description)
                                        )
                                    }
                                    items(enabledConfigs, key = { "vod:" + it.id }) { config ->
                                        val selection = vodSelections[config.id]
                                        SettingRow(
                                            label = config.name.ifBlank { stringResource(R.string.settings_unnamed) },
                                            value = when {
                                                selection == null -> stringResource(R.string.settings_vod_categories_none)
                                                selection.all -> stringResource(R.string.settings_vod_categories_all)
                                                selection.keys.isEmpty() -> stringResource(R.string.settings_vod_categories_skipped)
                                                else -> pluralStringResource(R.plurals.settings_vod_categories_count, selection.keys.size, selection.keys.size)
                                            },
                                            onClick = { onChooseVodCategories(config) }
                                        )
                                    }
                                    item { SettingGroup(title = stringResource(R.string.settings_vod_display_title)) }
                                    item {
                                        SettingRow(
                                            label = stringResource(R.string.settings_vod_rows_title),
                                            value = "${VodRow.Kind.entries.count { it !in vodDisplay.hiddenRows }} / ${VodRow.Kind.entries.size}",
                                            onClick = { open(SettingsPanel.VodRows) },
                                            modifier = Modifier.focusRequester(focusOf(SettingsPanel.VodRows))
                                        )
                                    }
                                    item {
                                        SettingRow(
                                            label = stringResource(R.string.settings_vod_poster_title),
                                            value = posterSizeLabel(vodDisplay.posterSize),
                                            onClick = { open(SettingsPanel.Posters) },
                                            modifier = Modifier.focusRequester(focusOf(SettingsPanel.Posters))
                                        )
                                    }
                                    item {
                                        SettingRow(
                                            label = stringResource(R.string.settings_vod_theater_title),
                                            description = stringResource(R.string.settings_vod_theater_description),
                                            toggle = !vodDisplay.hideTheater,
                                            onClick = { repository.setVodDisplay(vodDisplay.copy(hideTheater = !vodDisplay.hideTheater)) }
                                        )
                                    }
                                    // Filters the Movies and Series tabs open with
                                    item {
                                        SettingGroup(
                                            title = stringResource(R.string.settings_vod_defaults_title),
                                            caption = stringResource(R.string.settings_vod_defaults_description)
                                        )
                                    }
                                    listOf(VodKind.MOVIE, VodKind.SERIES).forEach { kind ->
                                        items(DefaultField.entries, key = { "default:$kind:$it" }) { field ->
                                            val filter = vodDisplay.defaults[kind] ?: VodFilter()
                                            val defaultPanel = SettingsPanel.Default(kind, field)
                                            SettingRow(
                                                label = defaultFieldTitle(kind, field),
                                                value = when (field) {
                                                    DefaultField.COUNTRY -> filter.countries.takeIf { it.isNotEmpty() }?.let { countriesLabel(it) }
                                                        ?: stringResource(R.string.vod_filter_any)
                                                    DefaultField.AUDIO -> filter.audio?.let { languageName(it) ?: it } ?: stringResource(R.string.vod_filter_any)
                                                    DefaultField.SUBTITLES -> filter.subtitles?.let { languageName(it) ?: it } ?: stringResource(R.string.vod_filter_any)
                                                    DefaultField.SORT -> sortLabel(filter.sort)
                                                },
                                                onClick = { open(defaultPanel) },
                                                modifier = Modifier.focusRequester(focusOf(defaultPanel))
                                            )
                                        }
                                    }
                                }
                            }

                            SettingsPage.PARENTAL -> {
                                when {
                                    !parentalHasPin -> item {
                                        SettingRow(
                                            label = stringResource(R.string.parental_set_pin),
                                            description = stringResource(R.string.parental_description),
                                            icon = Icons.Filled.Lock,
                                            onClick = { pinStep = PinStep.NEW },
                                            modifier = Modifier.focusRequester(contentStart)
                                        )
                                    }
                                    !parentalUnlocked -> item {
                                        SettingRow(
                                            label = stringResource(R.string.parental_unlock),
                                            description = stringResource(R.string.parental_locked_description),
                                            icon = Icons.Filled.Lock,
                                            onClick = { pinStep = PinStep.UNLOCK },
                                            modifier = Modifier.focusRequester(contentStart)
                                        )
                                    }
                                    else -> {
                                        item {
                                            SettingRow(
                                                label = stringResource(R.string.parental_categories),
                                                value = "${lockedGroups.size}",
                                                onClick = { open(SettingsPanel.ParentalCategories) },
                                                modifier = Modifier.focusRequester(focusOf(SettingsPanel.ParentalCategories)).focusRequester(contentStart)
                                            )
                                        }
                                        item {
                                            SettingRow(
                                                label = stringResource(R.string.parental_vod),
                                                description = stringResource(R.string.parental_vod_description),
                                                toggle = vodLocked,
                                                onClick = { parental.setVodLocked(!vodLocked) }
                                            )
                                        }
                                        item {
                                            SettingRow(label = stringResource(R.string.parental_change_pin), icon = Icons.Filled.Edit, onClick = { pinStep = PinStep.NEW })
                                        }
                                        item {
                                            SettingRow(
                                                label = stringResource(R.string.parental_lock_now),
                                                icon = Icons.Filled.Lock,
                                                chevron = false,
                                                onClick = {
                                                    parental.lock()
                                                    parentalRefocus++
                                                }
                                            )
                                        }
                                        item {
                                            SettingRow(
                                                label = stringResource(R.string.parental_remove),
                                                icon = Icons.Filled.Delete,
                                                chevron = false,
                                                destructive = true,
                                                onClick = {
                                                    parental.clear()
                                                    parentalRefocus++
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            SettingsPage.SYSTEM -> {
                                item {
                                    UpdateSection(
                                        installedVersion = installedVersion,
                                        supported = updatesSupported,
                                        state = updateState,
                                        onCheck = onCheckUpdate,
                                        onInstall = onInstallUpdate,
                                        modifier = Modifier.focusRequester(contentStart)
                                    )
                                }
                                item {
                                    SettingRow(
                                        label = stringResource(R.string.settings_show_guide),
                                        description = stringResource(R.string.settings_help_description),
                                        icon = Icons.Filled.Info,
                                        onClick = onShowGuide
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }


        panel?.let { current ->
            val close = { panel = null }
            when (current) {
                SettingsPanel.ParentalCategories -> ChoicePanel(
                    title = stringResource(R.string.parental_categories),
                    description = stringResource(R.string.parental_categories_description),
                    style = ChoiceStyle.MULTIPLE,
                    choices = categories.map { (group, count) ->
                        val locked = group in lockedGroups
                        Choice(
                            label = groupLabel(group),
                            trailing = count.toString(),
                            selected = locked,
                            onClick = { parental.setGroupLocked(group, !locked) }
                        )
                    },
                    onSelectAll = { parental.setGroupsLocked(categories.map { it.first }, locked = true) },
                    onSelectNone = { parental.setGroupsLocked(categories.map { it.first }, locked = false) },
                    onDismiss = close
                )
                SettingsPanel.Bouquet -> ChoicePanel(
                    title = stringResource(R.string.settings_bouquet_title),
                    description = stringResource(R.string.settings_bouquet_description),
                    choices = BouquetPreference.entries.map { preference ->
                        Choice(
                            label = stringResource(preference.labelRes),
                            description = stringResource(preference.descriptionRes),
                            selected = preference == currentBouquet,
                            onClick = { repository.setBouquet(preference) }
                        )
                    },
                    onDismiss = close
                )
                SettingsPanel.CategoryModes -> ChoicePanel(
                    title = stringResource(R.string.settings_category_mode),
                    choices = CategoryMode.entries.map { mode ->
                        Choice(
                            label = stringResource(mode.labelRes),
                            description = stringResource(mode.descriptionRes),
                            selected = mode == categoryMode,
                            onClick = { repository.setCategoryMode(mode) }
                        )
                    },
                    onDismiss = close
                )
                SettingsPanel.Categories -> ChoicePanel(
                    title = stringResource(R.string.settings_categories_title),
                    description = stringResource(R.string.settings_categories_description),
                    style = ChoiceStyle.MULTIPLE,
                    choices = categories.map { (group, count) ->
                        val visible = group !in hiddenGroups
                        Choice(
                            label = groupLabel(group),
                            trailing = if (visible) count.toString() else stringResource(R.string.settings_category_hidden),
                            selected = visible,
                            onClick = { repository.setGroupHidden(group, visible) }
                        )
                    },
                    onSelectAll = { repository.setGroupsHidden(categories.map { it.first }, hidden = false) },
                    onSelectNone = { repository.setGroupsHidden(categories.map { it.first }, hidden = true) },
                    onDismiss = close
                )
                SettingsPanel.Hidden -> ChoicePanel(
                    title = stringResource(R.string.settings_hidden_channels_title),
                    description = pluralStringResource(R.plurals.settings_hidden_channels_description, hiddenIds.size, hiddenIds.size),
                    style = ChoiceStyle.ACTIONS,
                    choices = listOf(Choice(stringResource(R.string.settings_unhide_all), { repository.unhideAllChannels() }, icon = Icons.Filled.Refresh)),
                    onDismiss = close
                )
                is SettingsPanel.ListActions -> channelLists.firstOrNull { it.id == current.id }?.let { list ->
                    ChoicePanel(
                        title = list.name,
                        description = pluralStringResource(R.plurals.channel_count, list.channelIds.size, list.channelIds.size),
                        style = ChoiceStyle.ACTIONS,
                        choices = listOf(
                            Choice(stringResource(R.string.settings_channel_list_rename), { renamingList = list }, icon = Icons.Filled.Edit),
                            Choice(
                                stringResource(R.string.settings_channel_list_delete),
                                { repository.deleteChannelList(list.id) },
                                icon = Icons.Filled.Delete,
                                destructive = true
                            )
                        ),
                        onDismiss = close
                    )
                }
                SettingsPanel.Quality -> ChoicePanel(
                    title = stringResource(R.string.settings_quality_title),
                    description = stringResource(R.string.settings_quality_description),
                    choices = QualityPreference.entries.map { preference ->
                        Choice(
                            label = stringResource(preference.labelRes),
                            description = stringResource(preference.descriptionRes),
                            selected = preference == currentQuality,
                            onClick = { repository.setQualityPreference(preference) }
                        )
                    },
                    onDismiss = close
                )
                SettingsPanel.Leveling -> ChoicePanel(
                    title = stringResource(R.string.settings_audio_leveling),
                    description = stringResource(R.string.settings_audio_leveling_description),
                    choices = AudioLeveling.entries.map { choice ->
                        Choice(
                            label = stringResource(choice.labelRes),
                            description = stringResource(choice.descriptionRes),
                            selected = choice == audioLeveling,
                            onClick = { repository.setAudioLeveling(choice) }
                        )
                    },
                    onDismiss = close
                )
                SettingsPanel.Preview -> ChoicePanel(
                    title = stringResource(R.string.settings_preview_title),
                    description = stringResource(R.string.settings_preview_description),
                    choices = PreviewDelay.entries.map { delay ->
                        Choice(label = previewDelayLabel(delay), selected = delay == previewDelay, onClick = { repository.setPreviewDelay(delay) })
                    },
                    onDismiss = close
                )
                SettingsPanel.SubtitleLanguages -> {
                    // The languages offered stay put while toggled: the order of the choice is the order of the search
                    val offered = remember {
                        (subtitleLanguages + listOf(java.util.Locale.getDefault().language) + DEFAULT_LANGUAGES).filter { it.length == 2 }.distinct()
                    }
                    ChoicePanel(
                        title = stringResource(R.string.settings_subtitle_languages_title),
                        description = stringResource(R.string.settings_subtitle_languages_description),
                        style = ChoiceStyle.MULTIPLE,
                        choices = offered.map { language ->
                            val index = subtitleLanguages.indexOf(language)
                            Choice(
                                label = languageName(language) ?: language,
                                trailing = if (index >= 0 && subtitleLanguages.size > 1) "${index + 1}" else null,
                                selected = index >= 0,
                                onClick = {
                                    repository.setSubtitleLanguages(if (index >= 0) subtitleLanguages - language else subtitleLanguages + language)
                                }
                            )
                        },
                        onSelectAll = { repository.setSubtitleLanguages(subtitleLanguages + offered.filter { it !in subtitleLanguages }) },
                        onSelectNone = { repository.setSubtitleLanguages(emptyList()) },
                        onDismiss = close
                    )
                }
                is SettingsPanel.KeyActions -> ChoicePanel(
                    title = stringResource(current.service.rowRes),
                    description = stringResource(current.service.descriptionRes),
                    style = ChoiceStyle.ACTIONS,
                    choices = listOf(
                        Choice(stringResource(R.string.settings_tmdb_enter), { editingKey = current.service }, icon = Icons.Filled.Edit),
                        Choice(
                            stringResource(R.string.settings_tmdb_remove),
                            {
                                when (current.service) {
                                    ApiKeyService.TMDB -> repository.setTmdbKey("")
                                    ApiKeyService.OPENSUBTITLES -> repository.setOpenSubtitlesKey("")
                                }
                            },
                            icon = Icons.Filled.Delete,
                            destructive = true
                        )
                    ),
                    onDismiss = close
                )
                SettingsPanel.VodRows -> ChoicePanel(
                    title = stringResource(R.string.settings_vod_rows_title),
                    description = stringResource(R.string.settings_vod_rows_description),
                    style = ChoiceStyle.MULTIPLE,
                    choices = VodRow.Kind.entries.map { kind ->
                        val hidden = vodDisplay.hiddenRows
                        Choice(
                            label = vodRowLabel(kind),
                            selected = kind !in hidden,
                            onClick = { repository.setVodDisplay(vodDisplay.copy(hiddenRows = if (kind in hidden) hidden - kind else hidden + kind)) }
                        )
                    },
                    onSelectAll = { repository.setVodDisplay(vodDisplay.copy(hiddenRows = emptySet())) },
                    onSelectNone = { repository.setVodDisplay(vodDisplay.copy(hiddenRows = VodRow.Kind.entries.toSet())) },
                    onDismiss = close
                )
                SettingsPanel.Posters -> ChoicePanel(
                    title = stringResource(R.string.settings_vod_poster_title),
                    description = stringResource(R.string.settings_vod_poster_description),
                    choices = PosterSize.entries.map { size ->
                        Choice(
                            label = posterSizeLabel(size),
                            selected = size == vodDisplay.posterSize,
                            onClick = { repository.setVodDisplay(vodDisplay.copy(posterSize = size)) }
                        )
                    },
                    onDismiss = close
                )
                SettingsPanel.Background -> ChoicePanel(
                    title = stringResource(R.string.settings_background),
                    description = stringResource(R.string.settings_appearance_description),
                    choices = AppThemeBackground.entries.map { theme ->
                        Choice(
                            label = stringResource(theme.labelRes),
                            swatch = Color(theme.backgroundColorHex),
                            selected = theme == currentTheme,
                            onClick = { repository.setThemeBackground(theme) }
                        )
                    },
                    onDismiss = close
                )
                SettingsPanel.Accent -> ChoicePanel(
                    title = stringResource(R.string.settings_accent_color),
                    description = stringResource(R.string.settings_appearance_description),
                    choices = AppAccentColor.entries.map { accent ->
                        Choice(
                            label = stringResource(accent.labelRes),
                            swatch = Color(accent.colorHex),
                            selected = accent == currentAccent,
                            onClick = { repository.setAccentColor(accent) }
                        )
                    },
                    onDismiss = close
                )
                SettingsPanel.Density -> ChoicePanel(
                    title = stringResource(R.string.settings_density_title),
                    description = stringResource(R.string.settings_density_description),
                    choices = UiDensity.entries.map { density ->
                        Choice(
                            label = stringResource(density.labelRes),
                            description = stringResource(density.descriptionRes),
                            selected = density == currentDensity,
                            onClick = { repository.setUiDensity(density) }
                        )
                    },
                    onDismiss = close
                )
                is SettingsPanel.Default -> DefaultFilterPanel(
                    kind = current.kind,
                    field = current.field,
                    filter = vodDisplay.defaults[current.kind] ?: VodFilter(),
                    onChange = { filter -> repository.setVodDisplay(vodDisplay.copy(defaults = vodDisplay.defaults + (current.kind to filter))) },
                    onDismiss = close
                )
            }
        }
        editingKey?.let { service ->
            ApiKeyDialog(
                service = service,
                accentColorObj = accentColorObj,
                onDismiss = { editingKey = null },
                onSave = { key ->
                    when (service) {
                        ApiKeyService.TMDB -> repository.setTmdbKey(key)
                        ApiKeyService.OPENSUBTITLES -> repository.setOpenSubtitlesKey(key)
                    }
                    editingKey = null
                }
            )
        }
        pinStep?.let { step ->
            val wrongPin = stringResource(R.string.parental_wrong_pin)
            val mismatch = stringResource(R.string.parental_pin_mismatch)
            fun close() {
                pinStep = null
                firstPin = null
                pinError = null
            }
            PinDialog(
                title = stringResource(
                    when (step) {
                        PinStep.UNLOCK -> R.string.parental_enter_pin
                        PinStep.NEW -> R.string.parental_new_pin
                        PinStep.CONFIRM -> R.string.parental_confirm_pin
                    }
                ),
                error = pinError,
                onSubmit = { pin ->
                    when (step) {
                        PinStep.UNLOCK -> if (parental.unlock(pin)) {
                            close()
                            parentalRefocus++
                        } else {
                            val wait = parental.waitMs()
                            pinError = if (wait > 0) resources.getString(R.string.parental_wait, (wait + 999) / 1000) else wrongPin
                        }
                        PinStep.NEW -> {
                            firstPin = pin
                            pinError = null
                            pinStep = PinStep.CONFIRM
                        }
                        PinStep.CONFIRM -> if (pin == firstPin) {
                            parental.setPin(pin)
                            close()
                            parentalRefocus++
                        } else {
                            firstPin = null
                            pinError = mismatch
                            pinStep = PinStep.NEW
                        }
                    }
                },
                onDismiss = ::close
            )
        }
        renamingList?.let { list ->
            NameDialog(
                title = stringResource(R.string.settings_channel_list_rename),
                initial = list.name,
                confirm = stringResource(R.string.settings_channel_list_rename),
                hint = stringResource(R.string.channel_list_name_hint),
                onConfirm = { name ->
                    repository.renameChannelList(list.id, name)
                    renamingList = null
                },
                onDismiss = { renamingList = null }
            )
        }
        if (showUnmatched) {
            UnmatchedChannelsDialog(names = unmatchedChannels, onDismiss = { showUnmatched = false })
        }
        if (showSendConfig) {
            SendConfigDialog(repository = repository, onDismiss = { showSendConfig = false })
        }
        if (showReceiveConfig) {
            ReceiveConfigDialog(repository = repository, onDismiss = { showReceiveConfig = false })
        }

        if (editingConfig != null) {
            ConfigEditDialog(
                initialConfig = editingConfig!!,
                isNew = isAddingNew,
                onTestConnection = onTestConnection,
                accentColorObj = accentColorObj,
                onDismiss = {
                    editingConfig = null
                    isAddingNew = false
                },
                onSave = { updated ->
                    repository.saveOrUpdateConfig(updated)
                    editingConfig = null
                    isAddingNew = false
                }
            )
        }
    }
}

/** The choices opened from a row of the settings. */
private sealed interface SettingsPanel {
    data object Bouquet : SettingsPanel
    data object ParentalCategories : SettingsPanel
    data object CategoryModes : SettingsPanel
    data object Categories : SettingsPanel
    data object Hidden : SettingsPanel
    data class ListActions(val id: String) : SettingsPanel
    data object Quality : SettingsPanel
    data object Preview : SettingsPanel
    data object Leveling : SettingsPanel
    data object SubtitleLanguages : SettingsPanel
    data class KeyActions(val service: ApiKeyService) : SettingsPanel
    data object VodRows : SettingsPanel
    data object Posters : SettingsPanel
    data object Background : SettingsPanel
    data object Accent : SettingsPanel
    data object Density : SettingsPanel
    data class Default(val kind: VodKind, val field: DefaultField) : SettingsPanel
}

/** Filters the Movies and Series tabs open with. */
private enum class DefaultField { COUNTRY, AUDIO, SUBTITLES, SORT }

/** Entry of the parental code: to unlock, or a new one then its confirmation. */
private enum class PinStep { UNLOCK, NEW, CONFIRM }

private const val UNMATCHED_ROW = "unmatched"

@Composable
private fun posterSizeLabel(size: PosterSize): String = stringResource(
    when (size) {
        PosterSize.SMALL -> R.string.settings_vod_poster_small
        PosterSize.NORMAL -> R.string.settings_vod_poster_normal
        PosterSize.LARGE -> R.string.settings_vod_poster_large
    }
)

@Composable
private fun defaultFieldTitle(kind: VodKind, field: DefaultField): String =
    stringResource(if (kind == VodKind.MOVIE) R.string.vod_tab_movies else R.string.vod_tab_series) + " · " + stringResource(
        when (field) {
            DefaultField.COUNTRY -> R.string.settings_vod_default_country
            DefaultField.AUDIO -> R.string.vod_filter_audio
            DefaultField.SUBTITLES -> R.string.vod_filter_subtitles
            DefaultField.SORT -> R.string.settings_vod_default_sort
        }
    )

/** One default filter of the Movies or Series tab: several countries, else one value ("any" first). */
@Composable
private fun DefaultFilterPanel(kind: VodKind, field: DefaultField, filter: VodFilter, onChange: (VodFilter) -> Unit, onDismiss: () -> Unit) {
    val title = defaultFieldTitle(kind, field)
    val description = stringResource(R.string.settings_vod_defaults_description)
    val any = stringResource(R.string.vod_filter_any)
    val languages = remember {
        listOf<String?>(null) + (listOf(java.util.Locale.getDefault().language) + DEFAULT_LANGUAGES).filter { it.length == 2 }.distinct()
    }
    when (field) {
        DefaultField.COUNTRY -> {
            val countries = remember {
                (listOf(java.util.Locale.getDefault().country) + DEFAULT_COUNTRIES).filter { it.length == 2 }.distinct()
            }
            ChoicePanel(
                title = title,
                description = description,
                style = ChoiceStyle.MULTIPLE,
                choices = countries.map { country ->
                    Choice(
                        label = countryName(country),
                        selected = country in filter.countries,
                        onClick = {
                            onChange(filter.copy(countries = if (country in filter.countries) filter.countries - country else filter.countries + country))
                        }
                    )
                },
                // None: any country
                onSelectAll = { onChange(filter.copy(countries = countries.toSet())) },
                onSelectNone = { onChange(filter.copy(countries = emptySet())) },
                onDismiss = onDismiss
            )
        }
        DefaultField.AUDIO, DefaultField.SUBTITLES -> {
            val current = if (field == DefaultField.AUDIO) filter.audio else filter.subtitles
            ChoicePanel(
                title = title,
                description = description,
                choices = languages.map { language ->
                    Choice(
                        label = language?.let { languageName(it) ?: it } ?: any,
                        selected = current == language,
                        onClick = { onChange(if (field == DefaultField.AUDIO) filter.copy(audio = language) else filter.copy(subtitles = language)) }
                    )
                },
                onDismiss = onDismiss
            )
        }
        DefaultField.SORT -> ChoicePanel(
            title = title,
            description = description,
            choices = VodSort.entries.map { sort -> Choice(label = sortLabel(sort), selected = filter.sort == sort, onClick = { onChange(filter.copy(sort = sort)) }) },
            onDismiss = onDismiss
        )
    }
}

/** Services whose API key the user brings: the page where it is created, and what the dialog says of it. */
private enum class ApiKeyService(
    val label: String,
    val keyPageUrl: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val hintRes: Int,
    @param:StringRes val rowRes: Int,
    @param:StringRes val descriptionRes: Int
) {
    TMDB(
        "TMDB", "https://www.themoviedb.org/settings/api", R.string.settings_tmdb_dialog_title, R.string.settings_tmdb_dialog_hint,
        R.string.settings_tmdb_title, R.string.settings_tmdb_description
    ),
    OPENSUBTITLES(
        "OpenSubtitles", "https://www.opensubtitles.com/consumers", R.string.settings_opensubtitles_dialog_title, R.string.settings_opensubtitles_dialog_hint,
        R.string.settings_opensubtitles_title, R.string.settings_opensubtitles_description
    )
}

/**
 * Entry of an API key, from a phone: a QR code of the page where the key is created, and one of a page of the
 * TV (local network) where it is pasted. It can also be typed with the remote, hidden, never shown back.
 */
@Composable
private fun ApiKeyDialog(service: ApiKeyService, accentColorObj: Color, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // Phone / tablet: the key is typed (or pasted) here at once, no QR code nor page of the device
    var typing by remember { mutableStateOf(Device.isTouch) }
    var key by remember { mutableStateOf("") }
    val currentSave by rememberUpdatedState(onSave)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val host = remember { if (Device.isTouch) null else ConfigTransfer.localAddresses().firstOrNull() }
    val page = KeyEntryPage(
        title = stringResource(R.string.api_key_page_title, service.label),
        label = stringResource(service.hintRes),
        send = stringResource(R.string.api_key_page_send),
        done = stringResource(R.string.api_key_page_done),
        invalid = stringResource(R.string.api_key_page_invalid),
        getKey = stringResource(R.string.api_key_page_get, service.label),
        providerUrl = service.keyPageUrl
    )
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    // Page of the TV, only while the dialog is open: the first valid key closes it
    DisposableEffect(host) {
        val server = host?.let {
            try {
                KeyEntryServer(page) { received -> scope.launch { currentSave(received) } }
            } catch (e: java.io.IOException) {
                null
            }
        }
        server?.start(scope)
        phoneUrl = host?.let { server?.url(it) }
        onDispose { server?.stop() }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWithRetry() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .dialogWidth(780.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceDark)
                .padding(horizontal = 30.dp, vertical = 24.dp)
        ) {
            Text(text = stringResource(service.titleRes), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(modifier = Modifier.height(18.dp))
            if (Device.isTouch) {
                // The page where the key is created, in the browser of the device
                ActionButton(
                    label = stringResource(R.string.api_key_open_page, service.label),
                    icon = Icons.Filled.ArrowForward,
                    onClick = { runCatching { uriHandler.openUri(service.keyPageUrl) } }
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    KeyQrBlock(url = service.keyPageUrl, caption = stringResource(R.string.api_key_step_create, service.label))
                    val url = phoneUrl
                    if (url != null) {
                        KeyQrBlock(url = url, caption = stringResource(R.string.api_key_step_paste))
                    } else {
                        Text(
                            text = stringResource(R.string.api_key_no_network),
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.width(220.dp).padding(top = 20.dp)
                        )
                    }
                }
            }
            if (phoneUrl != null) {
                Text(
                    text = stringResource(R.string.api_key_same_network),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            if (typing) {
                Spacer(modifier = Modifier.height(16.dp))
                TvInputField(
                    label = stringResource(service.hintRes),
                    value = key,
                    onValueChange = { key = it },
                    placeholder = stringResource(service.hintRes),
                    accentColorObj = accentColorObj,
                    secret = true
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (typing) {
                    ActionButton(
                        label = stringResource(R.string.settings_tmdb_save),
                        icon = Icons.Filled.Check,
                        primary = true,
                        onClick = { if (key.isNotBlank()) onSave(key) }
                    )
                } else {
                    ActionButton(
                        label = stringResource(R.string.api_key_type),
                        icon = Icons.Filled.Edit,
                        onClick = { typing = true },
                        modifier = Modifier.focusRequester(firstFocus)
                    )
                }
                ActionButton(
                    label = stringResource(R.string.settings_tmdb_cancel),
                    icon = Icons.Filled.Close,
                    onClick = onDismiss
                )
            }
        }
    }
}

/** A QR code to scan, its step and the address it opens. */
@Composable
private fun KeyQrBlock(url: String, caption: String) {
    Column(modifier = Modifier.width(220.dp)) {
        QrCode(text = url, size = 200.dp)
        Text(text = caption, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, modifier = Modifier.padding(top = 10.dp))
        Text(text = url.removePrefix("https://").removePrefix("http://"), fontSize = 11.sp, color = TextSecondary, maxLines = 2)
    }
}

/** Countries offered as default filter, after the TV's own. */
private val DEFAULT_COUNTRIES = listOf("FR", "US", "GB", "BE", "CH", "CA", "KR", "JP", "ES", "IT", "DE", "IN")

/** Audio and subtitle languages offered as default filter, after the TV's own. */
private val DEFAULT_LANGUAGES = listOf("fr", "en", "es", "de", "it", "pt", "ar", "tr", "nl")

@Composable
private fun vodRowLabel(kind: VodRow.Kind): String = stringResource(
    when (kind) {
        VodRow.Kind.CONTINUE -> R.string.vod_row_continue
        VodRow.Kind.TRENDING -> R.string.vod_row_trending
        VodRow.Kind.MY_LIST -> R.string.vod_row_my_list
        VodRow.Kind.COLLECTION -> R.string.settings_vod_collections_row
        VodRow.Kind.RECENT_MOVIES -> R.string.vod_row_recent_movies
        VodRow.Kind.RECENT_SERIES -> R.string.vod_row_recent_series
        VodRow.Kind.POPULAR_MOVIES -> R.string.vod_row_popular_movies
        VodRow.Kind.POPULAR_SERIES -> R.string.vod_row_popular_series
        VodRow.Kind.NOW_PLAYING -> R.string.vod_row_now_playing
        VodRow.Kind.UPCOMING -> R.string.vod_row_upcoming
        VodRow.Kind.TOP_MOVIES -> R.string.vod_row_top_movies
        VodRow.Kind.TOP_SERIES -> R.string.vod_row_top_series
        VodRow.Kind.GENRE -> R.string.settings_vod_genres_row
    }
)

@Composable
private fun previewDelayLabel(delay: PreviewDelay): String = when (delay) {
    PreviewDelay.OFF -> stringResource(R.string.preview_delay_off)
    PreviewDelay.IMMEDIATE -> stringResource(R.string.preview_delay_immediate)
    else -> stringResource(R.string.preview_delay_seconds, delay.seconds)
}

/** Section of the settings menu: icon, name and what it holds; filled when focused, marked when open. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsNavItem(
    page: SettingsPage,
    selected: Boolean,
    badge: Boolean,
    onFocused: () -> Unit,
    // Touch: a tap opens the section (on a phone, in place of the menu)
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isFocused) { if (isFocused) onFocused() }
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(12.dp)
    val contentColor = if (isFocused) LocalAppColors.current.background else TextPrimary
    val secondaryColor = if (isFocused) LocalAppColors.current.background.copy(alpha = 0.7f) else TextSecondary

    Surface(
        // OK enters the section, like →
        onClick = { focusManager.moveFocus(FocusDirection.Right) },
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().touchClick(onOpen),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) SurfaceDark else Color.Transparent,
            focusedContainerColor = TextPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = if (selected && !isFocused) AccentCyan else secondaryColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(page.labelRes),
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = contentColor
                )
                Text(
                    text = stringResource(page.hintRes),
                    fontSize = 10.sp,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (badge) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AccentCyan)
                )
            }
        }
    }
}

/**
 * Exclusive option: subtle background, accent outline and check mark when selected, filled when focused.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    swatch: Color? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val accent = AccentCyan
    val contentColor = if (isFocused) LocalAppColors.current.background else TextPrimary

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.7f) else Color.Transparent), shape = shape),
            focusedBorder = Border(BorderStroke(1.dp, Color.Transparent), shape = shape)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.12f) else SurfaceCard,
            focusedContainerColor = TextPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (description != null) 10.dp else 11.dp)
        ) {
            if (swatch != null) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, TextSecondary.copy(alpha = 0.4f), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description != null) {
                    Text(
                        text = description,
                        fontSize = 10.sp,
                        color = if (isFocused) contentColor.copy(alpha = 0.7f) else TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Check mark reserved for wide options; for swatches, the outline is enough
            if (selected && swatch == null) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.settings_selected),
                    tint = if (isFocused) contentColor else accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Pill-shaped action button, with an optional icon. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    primary: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val contentColor = when {
        isFocused && destructive -> Color.White
        isFocused -> LocalAppColors.current.background
        primary -> LocalAppColors.current.background
        else -> TextPrimary
    }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) TextPrimary.copy(alpha = 0.85f) else SurfaceCard,
            focusedContainerColor = if (destructive) LiveRed else TextPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = if (icon != null) 14.dp else 18.dp, end = 18.dp, top = 9.dp, bottom = 9.dp)
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = contentColor)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerConfigCard(
    config: SourceConfig,
    isActive: Boolean,
    accentColorObj: Color,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    // Order of the sources = order of their channels (null: already first / last)
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    // Non-focusable container: only the action buttons are. A clickable Card containing
    // buttons grabbed the D-pad focus, overflowed the screen once scaled up and hid Edit/Delete.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(
                width = 1.dp,
                color = if (isActive) accentColorObj.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = config.name.ifBlank { stringResource(R.string.settings_unnamed) },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accentColorObj)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.settings_active), fontSize = 12.sp, color = TextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${stringResource(config.type.labelRes)}  ·  ${config.displayUrl}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            AdaptiveButtonRow(wrap = LocalScreen.current.compact, spacing = 8.dp) {
                onMoveUp?.let {
                    RoundIconButton(icon = Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.settings_move_up), onClick = it)
                }
                onMoveDown?.let {
                    RoundIconButton(icon = Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.settings_move_down), onClick = it)
                }
                // Several sources can be enabled at once: their channels are shown together
                ActionButton(
                    label = stringResource(if (isActive) R.string.settings_deactivate else R.string.settings_activate),
                    icon = if (isActive) Icons.Filled.Close else Icons.Filled.Check,
                    onClick = onToggleEnabled
                )
                ActionButton(label = stringResource(R.string.settings_edit), icon = Icons.Filled.Edit, onClick = onEdit)
                ActionButton(label = stringResource(R.string.settings_delete), icon = Icons.Filled.Delete, onClick = onDelete, destructive = true)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfigEditDialog(
    initialConfig: SourceConfig,
    isNew: Boolean,
    onTestConnection: suspend (SourceConfig) -> Result<UiText>,
    accentColorObj: Color,
    onDismiss: () -> Unit,
    onSave: (SourceConfig) -> Unit
) {
    var type by remember { mutableStateOf(initialConfig.type) }
    var name by remember { mutableStateOf(initialConfig.name) }
    var serverUrl by remember { mutableStateOf(initialConfig.serverUrl) }
    var apiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var username by remember { mutableStateOf(initialConfig.username) }
    var password by remember { mutableStateOf(initialConfig.password) }
    var epgUrl by remember { mutableStateOf(initialConfig.epgUrl ?: "") }
    var userAgent by remember { mutableStateOf(initialConfig.customUserAgent ?: "") }

    val resources = LocalResources.current
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isTestSuccess by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var formError by remember { mutableStateOf<String?>(null) }

    /** Required fields depending on the source type; null if the form is complete. */
    fun missingField(): String? = when {
        serverUrl.isBlank() -> when (type) {
            SourceType.M3U -> resources.getString(R.string.settings_missing_playlist_url)
            else -> resources.getString(R.string.settings_missing_server_url)
        }
        type == SourceType.XTREAM && (username.isBlank() || password.isBlank()) ->
            resources.getString(R.string.settings_missing_xtream_credentials)
        else -> null
    }

    fun buildConfig(): SourceConfig {
        val url = serverUrl.trim()
        return initialConfig.copy(
            type = type,
            name = name.trim().ifBlank {
                url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
            },
            serverUrl = url,
            apiKey = if (type == SourceType.DISPATCHARR) apiKey.trim() else "",
            username = if (type == SourceType.XTREAM) username.trim() else "",
            password = if (type == SourceType.XTREAM) password else "",
            epgUrl = if (type == SourceType.M3U) epgUrl.trim().takeIf { it.isNotEmpty() } else null,
            customUserAgent = userAgent.trim().takeIf { it.isNotEmpty() }
        )
    }

    fun testConnection() {
        if (isTesting) return
        formError = missingField()
        if (formError != null) return
        val tempConfig = buildConfig()
        isTesting = true
        testStatus = resources.getString(R.string.settings_checking)
        scope.launch {
            val result = onTestConnection(tempConfig)
            isTesting = false
            result.onSuccess { message ->
                isTestSuccess = true
                testStatus = message.resolve(resources)
            }.onFailure { error ->
                isTestSuccess = false
                testStatus = error.toUiText().resolve(resources)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocusWithRetry()
        }

        BoxWithConstraints {
            Column(
                modifier = Modifier
                    .dialogWidth(660.dp)
                    .heightIn(max = maxHeight * 0.94f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, TextPrimary.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(26.dp)
            ) {
                Text(
                    text = stringResource(if (isNew) R.string.settings_add_source else R.string.settings_edit_source),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Source type
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SourceType.entries.forEach { option ->
                        OptionButton(
                            label = stringResource(option.labelRes),
                            selected = option == type,
                            onClick = {
                                type = option
                                testStatus = null
                                formError = null
                            },
                            // Initial focus on the source's type (a careless OK must not change it)
                            modifier = Modifier
                                .weight(1f)
                                .then(if (option == initialConfig.type) Modifier.focusRequester(focusRequester) else Modifier)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                TvInputField(
                    label = stringResource(R.string.settings_profile_name),
                    value = name,
                    onValueChange = { name = it },
                    placeholder = when (type) {
                        SourceType.DISPATCHARR -> stringResource(R.string.settings_profile_name_hint_dispatcharr)
                        SourceType.XTREAM -> stringResource(R.string.settings_profile_name_hint_xtream)
                        SourceType.M3U -> stringResource(R.string.settings_profile_name_hint_m3u)
                    },
                    accentColorObj = accentColorObj
                )
                Spacer(modifier = Modifier.height(12.dp))

                when (type) {
                    SourceType.DISPATCHARR -> {
                        TvInputField(
                            label = stringResource(R.string.settings_server_address),
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            placeholder = stringResource(R.string.settings_dispatcharr_url_hint),
                            accentColorObj = accentColorObj
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TvInputField(
                            label = stringResource(R.string.settings_api_key),
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = stringResource(R.string.settings_api_key_hint),
                            accentColorObj = accentColorObj,
                            secret = true
                        )
                    }
                    SourceType.XTREAM -> {
                        TvInputField(
                            label = stringResource(R.string.settings_server_address),
                            value = serverUrl,
                            onValueChange = { text ->
                                // Full link pasted (get.php?username=…&password=…): split it across the fields
                                val account = XtreamClient.splitAccountUrl(text)
                                if (account != null) {
                                    serverUrl = account.first
                                    username = account.second
                                    password = account.third
                                } else {
                                    serverUrl = text
                                }
                            },
                            placeholder = stringResource(R.string.settings_xtream_url_hint),
                            accentColorObj = accentColorObj
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvInputField(
                                label = stringResource(R.string.settings_username),
                                value = username,
                                onValueChange = { username = it },
                                placeholder = stringResource(R.string.settings_username_hint),
                                accentColorObj = accentColorObj,
                                modifier = Modifier.weight(1f)
                            )
                            TvInputField(
                                label = stringResource(R.string.settings_password),
                                value = password,
                                onValueChange = { password = it },
                                placeholder = stringResource(R.string.settings_password),
                                accentColorObj = accentColorObj,
                                secret = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    SourceType.M3U -> {
                        TvInputField(
                            label = stringResource(R.string.settings_playlist_url),
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            placeholder = stringResource(R.string.settings_playlist_url_hint),
                            accentColorObj = accentColorObj
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TvInputField(
                            label = stringResource(R.string.settings_epg_url),
                            value = epgUrl,
                            onValueChange = { epgUrl = it },
                            placeholder = stringResource(R.string.settings_epg_url_hint),
                            accentColorObj = accentColorObj
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                TvInputField(
                    label = stringResource(R.string.settings_user_agent),
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    placeholder = HttpClients.DEFAULT_USER_AGENT,
                    accentColorObj = accentColorObj
                )

                if (formError != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = formError!!,
                        color = LiveRed,
                        fontSize = 13.sp
                    )
                }

                if (testStatus != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = testStatus!!,
                        color = if (isTestSuccess) TextPrimary else LiveRed,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                val testLabel = stringResource(if (isTesting) R.string.settings_testing else R.string.settings_test_connection)
                // Phone: the test on its own line, Cancel and Save under it
                val compact = LocalScreen.current.compact
                if (compact) {
                    ActionButton(label = testLabel, icon = Icons.Filled.Refresh, onClick = ::testConnection)
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!compact) ActionButton(label = testLabel, icon = Icons.Filled.Refresh, onClick = ::testConnection)

                    Spacer(modifier = Modifier.weight(1f))

                    ActionButton(label = stringResource(R.string.settings_cancel), onClick = onDismiss)
                    Spacer(modifier = Modifier.width(10.dp))
                    ActionButton(
                        label = stringResource(R.string.settings_save),
                        icon = Icons.Filled.Check,
                        primary = true,
                        onClick = {
                            formError = missingField()
                            if (formError == null) onSave(buildConfig())
                        }
                    )
                }
            }
        }
    }
}

/**
 * Remote-friendly input field: the D-pad moves between fields, OK enters edit mode
 * (keyboard), ↑/↓ or "Done" leave it. A bare BasicTextField trapped the focus (↓ moved the
 * cursor) and opened the keyboard on every pass.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    accentColorObj: Color,
    modifier: Modifier = Modifier,
    secret: Boolean = false
) {
    var isEditing by remember { mutableStateOf(false) }
    var restoreDisplayFocus by remember { mutableStateOf(false) }
    val textFieldFocus = remember { FocusRequester() }
    val displayFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(8.dp)

    fun stopEditing(moveFocus: FocusDirection? = null) {
        isEditing = false
        if (moveFocus != null) focusManager.moveFocus(moveFocus) else restoreDisplayFocus = true
    }

    LaunchedEffect(restoreDisplayFocus) {
        if (restoreDisplayFocus) {
            displayFocus.requestFocusWithRetry()
            restoreDisplayFocus = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        if (isEditing) {
            var textValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
            var wasFocused by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                textFieldFocus.requestFocusWithRetry()
            }

            BasicTextField(
                value = textValue,
                onValueChange = {
                    textValue = it
                    onValueChange(it.text)
                },
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(accentColorObj),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri
                ),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardActions = KeyboardActions(onDone = { stopEditing() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFieldFocus)
                    .onFocusChanged {
                        if (it.isFocused) wasFocused = true else if (wasFocused) isEditing = false
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                stopEditing(FocusDirection.Down)
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                stopEditing(FocusDirection.Up)
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(SurfaceCard)
                            .border(2.dp, accentColorObj, shape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (textValue.text.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
        } else {
            Surface(
                onClick = { isEditing = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(displayFocus)
                    .touchClick({ isEditing = true }),
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, FocusBorderColor), shape = shape)
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = SurfaceCard,
                    focusedContainerColor = SurfaceCard
                )
            ) {
                Text(
                    text = when {
                        value.isEmpty() -> placeholder
                        secret -> "•".repeat(value.length.coerceAtMost(16))
                        else -> value
                    },
                    color = if (value.isEmpty()) TextSecondary.copy(alpha = 0.6f) else TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * App updates: one row with the installed version and the state; OK checks, or installs the version
 * found, its highlights listed under it. Download progress and errors are shown as its value.
 */
@Composable
private fun UpdateSection(
    installedVersion: String,
    supported: Boolean,
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (AppRelease) -> Unit,
    modifier: Modifier = Modifier
) {
    // The version found, also while it downloads in the background or after a failed installation
    val release = state.offeredRelease
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing || state is UpdateState.Checking
    val status = when (state) {
        UpdateState.Idle -> null
        UpdateState.Checking -> stringResource(R.string.settings_update_checking)
        UpdateState.UpToDate -> stringResource(R.string.settings_update_up_to_date)
        is UpdateState.Available -> null
        is UpdateState.Ready -> stringResource(R.string.settings_update_ready)
        is UpdateState.Downloading ->
            stringResource(R.string.settings_update_downloading, (state.progress * 100).toInt().coerceIn(0, 100))
        is UpdateState.Installing -> stringResource(R.string.settings_update_installing)
        is UpdateState.Failed -> state.message.resolve()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(
            label = when {
                !supported -> stringResource(R.string.settings_update_title)
                release != null -> stringResource(R.string.settings_update_install, release.version)
                else -> stringResource(R.string.settings_update_check)
            },
            description = if (supported) {
                stringResource(R.string.settings_update_description, installedVersion)
            } else {
                stringResource(R.string.settings_update_dev, installedVersion)
            },
            value = status,
            icon = Icons.Filled.Refresh,
            modifier = modifier,
            chevron = false,
            onClick = {
                when {
                    !supported || busy -> Unit
                    release != null -> onInstall(release)
                    else -> onCheck()
                }
            }
        )
        if (release != null && state !is UpdateState.Failed && release.highlights.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(start = 18.dp, top = 4.dp)
            ) {
                release.highlights.forEach { point ->
                    Text(
                        text = "·  $point",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
