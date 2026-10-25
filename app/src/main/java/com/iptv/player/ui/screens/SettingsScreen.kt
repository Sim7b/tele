package com.iptv.player.ui.screens

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
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.remote.HttpClients
import com.iptv.player.data.remote.XtreamClient
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.*
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.storage.AppAccentColor
import com.iptv.player.data.storage.AppThemeBackground
import com.iptv.player.data.storage.SettingsRepository
import com.iptv.player.data.storage.UiDensity
import com.iptv.player.ui.components.SectionLabel
import com.iptv.player.ui.components.requestFocusWithRetry
import com.iptv.player.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Paramètres : synchronisation manuelle, apparence, densité, chaînes et catégories masquées,
 * et gestion des sources de chaînes (ajout, test de connexion, activation, suppression).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onTestConnection: suspend (SourceConfig) -> Result<String>,
    categories: List<Pair<String, Int>> = emptyList(),
    onRefreshPlaylist: () -> Unit = {},
    onRefreshEpg: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configs by repository.configs.collectAsState()
    val activeConfig by repository.activeConfig.collectAsState()
    val currentDensity by repository.uiDensity.collectAsState()
    val currentTheme by repository.themeBackground.collectAsState()
    val currentAccent by repository.accentColor.collectAsState()
    val hiddenIds by repository.hiddenIds.collectAsState()
    val hiddenGroups by repository.hiddenGroups.collectAsState()

    val backButtonFocus = remember { FocusRequester() }
    // Après la suppression d'une source, le focus revient sur "Ajouter une source" (et non en haut de l'écran)
    val addSourceFocus = remember { FocusRequester() }
    var refocusAfterDelete by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocusAfterDelete) {
        if (refocusAfterDelete > 0) addSourceFocus.requestFocusWithRetry()
    }

    var editingConfig by remember { mutableStateOf<SourceConfig?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    // Le formulaire serveur est un Dialog : il gère lui-même la touche Retour
    BackHandler {
        onBack()
    }

    LaunchedEffect(Unit) {
        backButtonFocus.requestFocusWithRetry()
    }

    val themeBgColor = Color(currentTheme.backgroundColorHex)
    val accentColorObj = Color(currentAccent.colorHex)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBgColor)
            .padding(start = 48.dp, end = 48.dp, top = 26.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // En-tête
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Paramètres",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Apparence, synchronisation et sources de chaînes",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }

                ActionButton(
                    label = "Retour",
                    icon = Icons.Filled.ArrowBack,
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backButtonFocus)
                )
            }

            TvLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // SECTION 1 : SYNCHRONISATION MANUELLE
                item {
                    SettingsSection(
                        title = "Synchronisation",
                        description = "Mettre à jour manuellement les chaînes et le guide depuis la source active"
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ActionButton(
                                label = "Actualiser les chaînes",
                                icon = Icons.Filled.Refresh,
                                onClick = {
                                    onRefreshPlaylist()
                                    syncMessage = "Actualisation des chaînes lancée"
                                }
                            )
                            ActionButton(
                                label = "Actualiser le guide",
                                icon = Icons.Filled.DateRange,
                                onClick = {
                                    onRefreshEpg()
                                    syncMessage = "Actualisation du guide lancée"
                                }
                            )
                            if (syncMessage != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = syncMessage!!, fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    }
                }

                // SECTION 2 : THÈME ET COULEUR D'ACCENTUATION
                item {
                    SettingsSection(
                        title = "Apparence",
                        description = "Fond de l'application et couleur des indicateurs (progression, sélection)"
                    ) {
                        Text(
                            text = "Fond",
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
                                    label = theme.label,
                                    selected = theme == currentTheme,
                                    swatch = Color(theme.backgroundColorHex),
                                    onClick = { repository.setThemeBackground(theme) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text(
                            text = "Couleur d'accentuation",
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
                                    label = accent.label,
                                    selected = accent == currentAccent,
                                    swatch = Color(accent.colorHex),
                                    onClick = { repository.setAccentColor(accent) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // SECTION 3 : DENSITÉ DE L'INTERFACE
                item {
                    SettingsSection(
                        title = "Densité de l'interface",
                        description = "Taille des éléments à l'écran"
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            UiDensity.entries.forEach { density ->
                                OptionButton(
                                    label = density.label,
                                    description = density.description,
                                    selected = density == currentDensity,
                                    onClick = { repository.setUiDensity(density) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // SECTION 4 : CHAÎNES MASQUÉES
                if (hiddenIds.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = "Chaînes masquées",
                            description = "${hiddenIds.size} chaîne(s) masquée(s) de l'accueil"
                        ) {
                            ActionButton(
                                label = "Tout réafficher",
                                icon = Icons.Filled.Refresh,
                                onClick = { repository.unhideAllChannels() }
                            )
                        }
                    }
                }

                // SECTION 5 : CATÉGORIES AFFICHÉES
                if (categories.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = "Catégories",
                            description = "Catégories affichées sur l'accueil, dans le volet des chaînes et au zapping. " +
                                "Vos favoris restent visibles."
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                categories.chunked(4).forEach { row ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        row.forEach { (group, count) ->
                                            CategoryToggle(
                                                label = group,
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

                // SECTION 6 : SERVEURS DISPATCHARR
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel(text = "Sources de chaînes", modifier = Modifier.weight(1f))
                        ActionButton(
                            label = "Ajouter une source",
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
                    val isActive = config.id == activeConfig?.id
                    ServerConfigCard(
                        config = config,
                        isActive = isActive,
                        accentColorObj = accentColorObj,
                        onActivate = {
                            repository.setActiveConfig(config.id)
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

/** Bloc de réglages : intitulé discret, description, contenu. */
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
 * Option exclusive : fond discret, contour et coche d'accent si sélectionnée, pleine au focus.
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
            // Coche réservée aux options larges ; pour les pastilles, le contour suffit
            if (selected && swatch == null) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Sélectionné",
                    tint = if (isFocused) contentColor else accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Case à cocher d'une catégorie : cochée = affichée, décochée = masquée (libellé estompé).
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
            // Case : pleine avec coche si affichée, simple contour si masquée
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
                text = if (visible) channelCount.toString() else "Masquée",
                fontSize = 11.sp,
                color = if (isFocused) focusedContent.copy(alpha = 0.7f) else TextSecondary
            )
        }
    }
}

/** Bouton d'action en pilule, avec icône facultative. */
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
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    // Conteneur non focalisable : seuls les boutons d'action le sont. Une Card cliquable contenant
    // des boutons captait le focus D-pad, débordait de l'écran une fois agrandie et masquait Modifier/Supprimer.
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
                        text = config.name.ifBlank { "Sans nom" },
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
                        Text(text = "Actif", fontSize = 12.sp, color = TextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${config.type.label}  ·  ${config.displayUrl}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) {
                    ActionButton(label = "Activer", icon = Icons.Filled.Check, onClick = onActivate)
                }
                ActionButton(label = "Modifier", icon = Icons.Filled.Edit, onClick = onEdit)
                ActionButton(label = "Supprimer", icon = Icons.Filled.Delete, onClick = onDelete, destructive = true)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfigEditDialog(
    initialConfig: SourceConfig,
    isNew: Boolean,
    onTestConnection: suspend (SourceConfig) -> Result<String>,
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

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isTestSuccess by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var formError by remember { mutableStateOf<String?>(null) }

    /** Champs obligatoires selon le type de source ; null si le formulaire est complet. */
    fun missingField(): String? = when {
        serverUrl.isBlank() -> when (type) {
            SourceType.M3U -> "Saisissez le lien de la playlist."
            else -> "Saisissez l'adresse du serveur."
        }
        type == SourceType.XTREAM && (username.isBlank() || password.isBlank()) ->
            "Saisissez l'identifiant et le mot de passe du compte Xtream."
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
                    text = if (isNew) "Ajouter une source" else "Modifier la source",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Type de source
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SourceType.entries.forEach { option ->
                        OptionButton(
                            label = option.label,
                            selected = option == type,
                            onClick = {
                                type = option
                                testStatus = null
                                formError = null
                            },
                            // Focus initial sur le type de la source (un OK distrait ne doit pas le changer)
                            modifier = Modifier
                                .weight(1f)
                                .then(if (option == initialConfig.type) Modifier.focusRequester(focusRequester) else Modifier)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                TvInputField(
                    label = "Nom du profil",
                    value = name,
                    onValueChange = { name = it },
                    placeholder = when (type) {
                        SourceType.DISPATCHARR -> "Serveur maison"
                        SourceType.XTREAM -> "Mon abonnement"
                        SourceType.M3U -> "Ma playlist"
                    },
                    accentColorObj = accentColorObj
                )
                Spacer(modifier = Modifier.height(12.dp))

                when (type) {
                    SourceType.DISPATCHARR -> {
                        TvInputField(
                            label = "Adresse du serveur",
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            placeholder = "dispatcharr.mondomaine.fr (ou https://...)",
                            accentColorObj = accentColorObj
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TvInputField(
                            label = "Clé API (X-API-Key)",
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = "Entrez votre clé API",
                            accentColorObj = accentColorObj,
                            secret = true
                        )
                    }
                    SourceType.XTREAM -> {
                        TvInputField(
                            label = "Adresse du serveur",
                            value = serverUrl,
                            onValueChange = { text ->
                                // Lien complet collé (get.php?username=…&password=…) : on répartit les champs
                                val account = XtreamClient.splitAccountUrl(text)
                                if (account != null) {
                                    serverUrl = account.first
                                    username = account.second
                                    password = account.third
                                } else {
                                    serverUrl = text
                                }
                            },
                            placeholder = "http://fournisseur.tv:8080 (ou lien get.php complet)",
                            accentColorObj = accentColorObj
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvInputField(
                                label = "Nom d'utilisateur",
                                value = username,
                                onValueChange = { username = it },
                                placeholder = "Identifiant",
                                accentColorObj = accentColorObj,
                                modifier = Modifier.weight(1f)
                            )
                            TvInputField(
                                label = "Mot de passe",
                                value = password,
                                onValueChange = { password = it },
                                placeholder = "Mot de passe",
                                accentColorObj = accentColorObj,
                                secret = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    SourceType.M3U -> {
                        TvInputField(
                            label = "Lien de la playlist (M3U / M3U8)",
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            placeholder = "https://fournisseur.tv/playlist.m3u",
                            accentColorObj = accentColorObj
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TvInputField(
                            label = "Guide EPG XMLTV (facultatif)",
                            value = epgUrl,
                            onValueChange = { epgUrl = it },
                            placeholder = "https://…/guide.xml.gz · sinon celui annoncé par la playlist",
                            accentColorObj = accentColorObj
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                TvInputField(
                    label = "User-Agent personnalisé (facultatif)",
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
                        label = if (isTesting) "Test en cours…" else "Tester la connexion",
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            if (isTesting) return@ActionButton
                            formError = missingField()
                            if (formError != null) return@ActionButton
                            val tempConfig = buildConfig()
                            isTesting = true
                            testStatus = "Vérification en cours…"
                            scope.launch {
                                val result = onTestConnection(tempConfig)
                                isTesting = false
                                result.onSuccess { message ->
                                    isTestSuccess = true
                                    testStatus = message
                                }.onFailure { error ->
                                    isTestSuccess = false
                                    testStatus = error.localizedMessage ?: "Erreur de connexion"
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    ActionButton(label = "Annuler", onClick = onDismiss)
                    Spacer(modifier = Modifier.width(10.dp))
                    ActionButton(
                        label = "Enregistrer",
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
 * Champ de saisie adapté à la télécommande : le D-pad navigue entre les champs, OK passe en édition
 * (clavier), ↑/↓ ou "Terminé" en sortent. Un BasicTextField seul piégeait le focus (↓ déplaçait le
 * curseur) et ouvrait le clavier à chaque passage.
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
