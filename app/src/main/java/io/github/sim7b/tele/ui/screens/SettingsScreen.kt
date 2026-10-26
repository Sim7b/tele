package io.github.sim7b.tele.ui.screens

import androidx.compose.ui.platform.LocalResources
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
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
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.groupLabel
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.*
import io.github.sim7b.tele.update.UpdateState
import io.github.sim7b.tele.data.update.AppRelease
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Settings: app updates, manual sync, appearance, density, hidden channels and categories,
 * and channel source management (add, connection test, activation, deletion).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onTestConnection: suspend (SourceConfig) -> Result<UiText>,
    categories: List<Pair<String, Int>> = emptyList(),
    onRefreshPlaylist: () -> Unit = {},
    onRefreshEpg: () -> Unit = {},
    onShowGuide: () -> Unit = {},
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
    val currentBouquet by repository.bouquet.collectAsState()
    val hiddenIds by repository.hiddenIds.collectAsState()
    val hiddenGroups by repository.hiddenGroups.collectAsState()

    val backButtonFocus = remember { FocusRequester() }
    // After a source is deleted, focus returns to "Add a source" (not to the top of the screen)
    val addSourceFocus = remember { FocusRequester() }
    var refocusAfterDelete by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocusAfterDelete) {
        if (refocusAfterDelete > 0) addSourceFocus.requestFocusWithRetry()
    }

    var editingConfig by remember { mutableStateOf<SourceConfig?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    // The server form is a Dialog: it handles the Back key itself
    BackHandler {
        onBack()
    }

    LaunchedEffect(Unit) {
        backButtonFocus.requestFocusWithRetry()
    }

    val resources = LocalResources.current
    val themeBgColor = Color(currentTheme.backgroundColorHex)
    val accentColorObj = Color(currentAccent.colorHex)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBgColor)
            .padding(start = 48.dp, end = 48.dp, top = 26.dp)
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
                        text = stringResource(R.string.settings_title),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.settings_subtitle),
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }

                ActionButton(
                    label = stringResource(R.string.settings_back),
                    icon = Icons.Filled.ArrowBack,
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backButtonFocus)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // SECTION 0: APP UPDATES (first, so an available update is in sight)
                item {
                    UpdateSection(
                        installedVersion = installedVersion,
                        supported = updatesSupported,
                        state = updateState,
                        onCheck = onCheckUpdate,
                        onInstall = onInstallUpdate
                    )
                }

                // SECTION 1: MANUAL SYNC
                item {
                    SettingsSection(
                        title = stringResource(R.string.settings_sync_title),
                        description = stringResource(R.string.settings_sync_description)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ActionButton(
                                label = stringResource(R.string.settings_refresh_channels),
                                icon = Icons.Filled.Refresh,
                                onClick = {
                                    onRefreshPlaylist()
                                    syncMessage = resources.getString(R.string.settings_refresh_channels_started)
                                }
                            )
                            ActionButton(
                                label = stringResource(R.string.settings_refresh_guide),
                                icon = Icons.Filled.DateRange,
                                onClick = {
                                    onRefreshEpg()
                                    syncMessage = resources.getString(R.string.settings_refresh_guide_started)
                                }
                            )
                            if (syncMessage != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = syncMessage!!, fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    }
                }

                // SECTION 2: THEME AND ACCENT COLOR
                item {
                    SettingsSection(
                        title = stringResource(R.string.settings_appearance_title),
                        description = stringResource(R.string.settings_appearance_description)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_background),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                        ) {
                            AppThemeBackground.entries.forEach { theme ->
                                OptionButton(
                                    label = stringResource(theme.labelRes),
                                    selected = theme == currentTheme,
                                    swatch = Color(theme.backgroundColorHex),
                                    onClick = { repository.setThemeBackground(theme) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.settings_accent_color),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AppAccentColor.entries.forEach { accent ->
                                OptionButton(
                                    label = stringResource(accent.labelRes),
                                    selected = accent == currentAccent,
                                    swatch = Color(accent.colorHex),
                                    onClick = { repository.setAccentColor(accent) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // SECTION 3: INTERFACE DENSITY
                item {
                    SettingsSection(
                        title = stringResource(R.string.settings_density_title),
                        description = stringResource(R.string.settings_density_description)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            UiDensity.entries.forEach { density ->
                                OptionButton(
                                    label = stringResource(density.labelRes),
                                    description = stringResource(density.descriptionRes),
                                    selected = density == currentDensity,
                                    onClick = { repository.setUiDensity(density) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // SECTION 3B: STREAM QUALITY (Dispatcharr automatic choice)
                item {
                    SettingsSection(
                        title = stringResource(R.string.settings_quality_title),
                        description = stringResource(R.string.settings_quality_description)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            QualityPreference.entries.forEach { preference ->
                                OptionButton(
                                    label = stringResource(preference.labelRes),
                                    description = stringResource(preference.descriptionRes),
                                    selected = preference == currentQuality,
                                    onClick = { repository.setQualityPreference(preference) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // SECTION 3C: REFERENCE BOUQUET (channel names, numbers, categories, grouped versions)
                item {
                    SettingsSection(
                        title = stringResource(R.string.settings_bouquet_title),
                        description = stringResource(R.string.settings_bouquet_description)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            BouquetPreference.entries.forEach { preference ->
                                OptionButton(
                                    label = stringResource(preference.labelRes),
                                    description = stringResource(preference.descriptionRes),
                                    selected = preference == currentBouquet,
                                    onClick = { repository.setBouquet(preference) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // SECTION 4: HIDDEN CHANNELS
                if (hiddenIds.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_hidden_channels_title),
                            description = pluralStringResource(
                                R.plurals.settings_hidden_channels_description,
                                hiddenIds.size,
                                hiddenIds.size
                            )
                        ) {
                            ActionButton(
                                label = stringResource(R.string.settings_unhide_all),
                                icon = Icons.Filled.Refresh,
                                onClick = { repository.unhideAllChannels() }
                            )
                        }
                    }
                }

                // SECTION 5: DISPLAYED CATEGORIES
                if (categories.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_categories_title),
                            description = stringResource(R.string.settings_categories_description)
                        ) {
                            // Same category name in several enabled sources: one category, or one per source
                            if (enabledConfigs.size > 1) {
                                Text(
                                    text = stringResource(R.string.settings_category_mode),
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 14.dp)
                                ) {
                                    CategoryMode.entries.forEach { mode ->
                                        OptionButton(
                                            label = stringResource(mode.labelRes),
                                            description = stringResource(mode.descriptionRes),
                                            selected = mode == categoryMode,
                                            onClick = { repository.setCategoryMode(mode) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                categories.chunked(4).forEach { row ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        row.forEach { (group, count) ->
                                            CategoryToggle(
                                                label = groupLabel(group),
                                                channelCount = count,
                                                visible = group !in hiddenGroups,
                                                onClick = { repository.setGroupHidden(group, group !in hiddenGroups) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        repeat(4 - row.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // SECTION 6: DISPATCHARR SERVERS
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel(text = stringResource(R.string.settings_sources_title), modifier = Modifier.weight(1f))
                        ActionButton(
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
                            modifier = Modifier.focusRequester(addSourceFocus)
                        )
                    }
                }

                items(configs, key = { it.id }) { config ->
                    val isActive = config.isActive
                    ServerConfigCard(
                        config = config,
                        isActive = isActive,
                        accentColorObj = accentColorObj,
                        onToggleEnabled = {
                            repository.setSourceEnabled(config.id, !config.isActive)
                        },
                        onEdit = {
                            editingConfig = config
                            isAddingNew = false
                        },
                        onDelete = {
                            repository.deleteConfig(config.id)
                            refocusAfterDelete++
                        }
                    )
                }

                // LAST SECTION: HELP
                item {
                    SettingsSection(
                        title = stringResource(R.string.settings_help_title),
                        description = stringResource(R.string.settings_help_description)
                    ) {
                        ActionButton(
                            label = stringResource(R.string.settings_show_guide),
                            icon = Icons.Filled.Info,
                            onClick = onShowGuide
                        )
                    }
                }
            }
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

/** Settings block: subtle title, description, content. */
@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        SectionLabel(text = title)
        Text(
            text = description,
            fontSize = 12.sp,
            color = TextSecondary.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
        )
        content()
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
        modifier = modifier,
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
                        maxLines = 1,
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

/**
 * Category checkbox: checked = shown, unchecked = hidden (dimmed label).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryToggle(
    label: String,
    channelCount: Int,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val focusedContent = LocalAppColors.current.background
    val labelColor = when {
        isFocused -> focusedContent
        visible -> TextPrimary
        else -> TextSecondary.copy(alpha = 0.6f)
    }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceCard,
            focusedContainerColor = TextPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Box: filled with a check mark if shown, plain outline if hidden
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (visible) AccentCyan else Color.Transparent)
                    .border(
                        1.5.dp,
                        if (visible) AccentCyan else labelColor.copy(alpha = 0.6f),
                        RoundedCornerShape(4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (visible) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = BackgroundDark,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (visible) FontWeight.Medium else FontWeight.Normal,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (visible) channelCount.toString() else stringResource(R.string.settings_category_hidden),
                fontSize = 11.sp,
                color = if (isFocused) focusedContent.copy(alpha = 0.7f) else TextSecondary
            )
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
        modifier = modifier,
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
    onDelete: () -> Unit
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    .width(660.dp)
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton(
                        label = stringResource(if (isTesting) R.string.settings_testing else R.string.settings_test_connection),
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            if (isTesting) return@ActionButton
                            formError = missingField()
                            if (formError != null) return@ActionButton
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
                    )

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
                    .focusRequester(displayFocus),
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
 * App updates: installed version, check button, then the available version with its highlights
 * and an install button; download progress and errors are shown inline.
 */
@Composable
private fun UpdateSection(
    installedVersion: String,
    supported: Boolean,
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (AppRelease) -> Unit
) {
    SettingsSection(
        title = stringResource(R.string.settings_update_title),
        description = if (supported) {
            stringResource(R.string.settings_update_description, installedVersion)
        } else {
            stringResource(R.string.settings_update_dev, installedVersion)
        }
    ) {
        if (!supported) return@SettingsSection
        val release = when (state) {
            is UpdateState.Available -> state.release
            is UpdateState.Failed -> state.release
            else -> null
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (release != null) {
                ActionButton(
                    label = stringResource(R.string.settings_update_install, release.version),
                    icon = Icons.Filled.Refresh,
                    primary = true,
                    onClick = { onInstall(release) }
                )
            } else if (state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                ActionButton(
                    label = stringResource(R.string.settings_update_check),
                    icon = Icons.Filled.Refresh,
                    onClick = onCheck
                )
            }
            val status = when (state) {
                UpdateState.Idle -> null
                UpdateState.Checking -> stringResource(R.string.settings_update_checking)
                UpdateState.UpToDate -> stringResource(R.string.settings_update_up_to_date)
                is UpdateState.Available -> stringResource(R.string.settings_update_available, state.release.version)
                is UpdateState.Downloading ->
                    stringResource(R.string.settings_update_downloading, (state.progress * 100).toInt().coerceIn(0, 100))
                is UpdateState.Installing -> stringResource(R.string.settings_update_installing)
                is UpdateState.Failed -> state.message.resolve()
            }
            if (status != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = if (state is UpdateState.Failed) LiveRed.copy(alpha = 0.85f) else TextSecondary
                )
            }
        }
        if (state is UpdateState.Available && state.release.highlights.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                state.release.highlights.forEach { point ->
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
