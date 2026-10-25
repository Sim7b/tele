package com.iptv.player.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.iptv.player.data.epg.EpgResolver
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.EpgProgram
import com.iptv.player.data.model.StreamSource
import com.iptv.player.data.optStringOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Densité de l'interface : facteur appliqué à toutes les dimensions (dp/sp). */
enum class UiDensity(val label: String, val scaleFactor: Float, val description: String) {
    COMPACT("Compact", 0.85f, "Affiche plus d'éléments simultanément"),
    NORMAL("Normal", 1.0f, "Taille d'affichage standard recommandée"),
    LARGE("Grand", 1.15f, "Éléments plus grands et très lisibles de loin")
}

/** Fond de l'application (couleurs de fond, surface et cartes). */
enum class AppThemeBackground(
    val label: String,
    val backgroundColorHex: Long,
    val surfaceColorHex: Long,
    val surfaceCardColorHex: Long,
    val isLight: Boolean = false
) {
    ORIGINAL("Original", 0xFF0B0D12, 0xFF151821, 0xFF1F232D),
    OLED_BLACK("Noir OLED", 0xFF000000, 0xFF0B0B0C, 0xFF161618),
    MIDNIGHT_BLUE("Minuit", 0xFF0F172A, 0xFF1E293B, 0xFF334155),
    DARK_PURPLE("Nuit violette", 0xFF130D24, 0xFF1E1538, 0xFF2E2152),
    ANTHRACITE("Anthracite", 0xFF18181B, 0xFF27272A, 0xFF3F3F46),
    LIGHT("Clair", 0xFFF8FAFC, 0xFFFFFFFF, 0xFFE2E8F0, isLight = true)
}

/** Couleur d'accentuation, réservée aux indicateurs (progression, sélection). */
enum class AppAccentColor(
    val label: String,
    val colorHex: Long
) {
    CYAN("Cyan", 0xFF00E5FF),
    NEON_BLUE("Bleu", 0xFF3B82F6),
    ELECTRIC_PURPLE("Violet", 0xFFA855F7),
    EMERALD("Émeraude", 0xFF10B981),
    ROSE("Rose", 0xFFF43F5E),
    AMBER("Ambre", 0xFFF59E0B)
}

/**
 * Réglages persistants (SharedPreferences) : sources de chaînes, apparence, favoris ordonnés,
 * chaînes et catégories masquées, flux choisis par chaîne et cache des chaînes de la source active.
 *
 * Les clés et le nom du fichier de préférences sont conservés tels quels pour ne pas perdre
 * les réglages des installations existantes.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _configs = MutableStateFlow<List<SourceConfig>>(emptyList())
    val configs: StateFlow<List<SourceConfig>> = _configs.asStateFlow()

    private val _activeConfig = MutableStateFlow<SourceConfig?>(null)
    val activeConfig: StateFlow<SourceConfig?> = _activeConfig.asStateFlow()

    private val _uiDensity = MutableStateFlow<UiDensity>(UiDensity.NORMAL)
    val uiDensity: StateFlow<UiDensity> = _uiDensity.asStateFlow()

    private val _themeBackground = MutableStateFlow<AppThemeBackground>(AppThemeBackground.ORIGINAL)
    val themeBackground: StateFlow<AppThemeBackground> = _themeBackground.asStateFlow()

    private val _accentColor = MutableStateFlow<AppAccentColor>(AppAccentColor.CYAN)
    val accentColor: StateFlow<AppAccentColor> = _accentColor.asStateFlow()

    // Favoris dans l'ordre choisi par l'utilisateur (réorganisables sur la grille de l'accueil)
    private val _favoriteIds = MutableStateFlow<List<String>>(emptyList())
    val favoriteIds: StateFlow<List<String>> = _favoriteIds.asStateFlow()

    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()

    // Catégories (groupes Dispatcharr) masquées de l'accueil, du volet des chaînes et du zapping
    private val _hiddenGroups = MutableStateFlow<Set<String>>(emptySet())
    val hiddenGroups: StateFlow<Set<String>> = _hiddenGroups.asStateFlow()

    // Favoris repris de l'ancien stockage (ensemble sans ordre) : à classer une fois selon l'ordre des chaînes.
    // Déclaré avant init {} pour ne pas être réinitialisé après le chargement des préférences.
    private var legacyFavoritesUnordered = false

    // Flux choisi manuellement par chaîne (absent = mode automatique Dispatcharr)
    private val _selectedStreams = MutableStateFlow<Map<String, StreamSource>>(emptyMap())
    val selectedStreams: StateFlow<Map<String, StreamSource>> = _selectedStreams.asStateFlow()

    init {
        loadConfigs()
        loadPreferences()
    }

    private fun loadConfigs() {
        val rawJson = prefs.getString(KEY_CONFIGS, null)
        val list = mutableListOf<SourceConfig>()

        if (!rawJson.isNullOrBlank()) {
            try {
                val array = JSONArray(rawJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(SourceConfig.fromJson(obj))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        _configs.value = list
        _activeConfig.value = list.firstOrNull { it.isActive } ?: list.firstOrNull()
    }

    private fun loadPreferences() {
        val savedDensity = prefs.getString(KEY_UI_DENSITY, UiDensity.NORMAL.name)
        _uiDensity.value = try { UiDensity.valueOf(savedDensity ?: UiDensity.NORMAL.name) } catch (e: Exception) { UiDensity.NORMAL }

        val savedTheme = prefs.getString(KEY_THEME_BG, AppThemeBackground.ORIGINAL.name)
        _themeBackground.value = try { AppThemeBackground.valueOf(savedTheme ?: AppThemeBackground.ORIGINAL.name) } catch (e: Exception) { AppThemeBackground.ORIGINAL }

        val savedAccent = prefs.getString(KEY_ACCENT_COLOR, AppAccentColor.CYAN.name)
        _accentColor.value = try { AppAccentColor.valueOf(savedAccent ?: AppAccentColor.CYAN.name) } catch (e: Exception) { AppAccentColor.CYAN }

        _favoriteIds.value = loadFavoriteOrder()
        _hiddenIds.value = prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
        _hiddenGroups.value = prefs.getStringSet(KEY_HIDDEN_GROUPS, emptySet()) ?: emptySet()
        _selectedStreams.value = loadSelectedStreams()
    }

    private fun loadSelectedStreams(): Map<String, StreamSource> {
        val raw = prefs.getString(KEY_SELECTED_STREAMS, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            root.keys().asSequence().mapNotNull { channelId ->
                val obj = root.optJSONObject(channelId) ?: return@mapNotNull null
                val url = obj.optStringOrNull("url") ?: return@mapNotNull null
                channelId to StreamSource(
                    name = obj.optString("name", "Flux"),
                    url = url,
                    id = obj.optStringOrNull("id"),
                    provider = obj.optStringOrNull("provider")
                )
            }.toMap()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    /** Prénom affiché dans le message d'accueil, mémorisé par serveur pour un affichage immédiat au démarrage. */
    fun getUserDisplayName(configId: String): String? =
        prefs.getString(KEY_USER_NAME_PREFIX + configId, null)?.takeIf { it.isNotBlank() }

    fun saveUserDisplayName(configId: String, name: String) {
        prefs.edit().putString(KEY_USER_NAME_PREFIX + configId, name).apply()
    }

    /** Mémorise le flux choisi pour une chaîne ([stream] null = retour au mode automatique). */
    fun setSelectedStream(channelId: String, stream: StreamSource?) {
        val current = _selectedStreams.value.toMutableMap()
        if (stream == null) current.remove(channelId) else current[channelId] = stream

        val root = JSONObject()
        current.forEach { (id, s) ->
            root.put(id, JSONObject().apply {
                put("name", s.name)
                put("url", s.url)
                put("id", s.id)
                put("provider", s.provider)
            })
        }
        prefs.edit().putString(KEY_SELECTED_STREAMS, root.toString()).apply()
        _selectedStreams.value = current
    }

    fun setUiDensity(density: UiDensity) {
        prefs.edit().putString(KEY_UI_DENSITY, density.name).apply()
        _uiDensity.value = density
    }

    fun setThemeBackground(theme: AppThemeBackground) {
        prefs.edit().putString(KEY_THEME_BG, theme.name).apply()
        _themeBackground.value = theme
    }

    fun setAccentColor(accent: AppAccentColor) {
        prefs.edit().putString(KEY_ACCENT_COLOR, accent.name).apply()
        _accentColor.value = accent
    }

    /** Ordre des favoris ; reprend l'ancien stockage non ordonné s'il n'existe pas encore. */
    private fun loadFavoriteOrder(): List<String> {
        val raw = prefs.getString(KEY_FAVORITE_ORDER, null)
        if (raw == null) {
            val legacy = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toList() ?: emptyList()
            legacyFavoritesUnordered = legacy.isNotEmpty()
            return legacy
        }
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotBlank() } }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistFavorites(order: List<String>) {
        prefs.edit()
            .putString(KEY_FAVORITE_ORDER, JSONArray(order).toString())
            .remove(KEY_FAVORITES)
            .apply()
        _favoriteIds.value = order
    }

    /** Classe une seule fois les favoris migrés dans l'ordre des chaînes (comme ils s'affichaient avant). */
    fun applyLegacyFavoriteOrder(channelOrder: List<String>) {
        if (!legacyFavoritesUnordered) return
        legacyFavoritesUnordered = false
        val rank = channelOrder.withIndex().associate { (index, id) -> id to index }
        persistFavorites(_favoriteIds.value.sortedBy { rank[it] ?: Int.MAX_VALUE })
    }

    /** Ajoute la chaîne en fin de favoris, ou la retire. */
    fun toggleFavoriteChannel(channelId: String) {
        val current = _favoriteIds.value
        persistFavorites(if (channelId in current) current - channelId else current + channelId)
    }

    /** Déplace un favori à la position [toIndex] (bornée à la liste des favoris). */
    fun moveFavorite(channelId: String, toIndex: Int) {
        val current = _favoriteIds.value.toMutableList()
        val fromIndex = current.indexOf(channelId)
        if (fromIndex == -1) return
        val target = toIndex.coerceIn(0, current.lastIndex)
        if (target == fromIndex) return
        current.removeAt(fromIndex)
        current.add(target, channelId)
        persistFavorites(current)
    }

    fun setGroupHidden(group: String, hidden: Boolean) {
        val current = if (hidden) _hiddenGroups.value + group else _hiddenGroups.value - group
        prefs.edit().putStringSet(KEY_HIDDEN_GROUPS, current).apply()
        _hiddenGroups.value = current
    }

    fun hideChannel(channelId: String) {
        val current = _hiddenIds.value.toMutableSet()
        current.add(channelId)
        prefs.edit().putStringSet(KEY_HIDDEN, current).apply()
        _hiddenIds.value = current
    }

    fun unhideChannel(channelId: String) {
        val current = _hiddenIds.value.toMutableSet()
        current.remove(channelId)
        prefs.edit().putStringSet(KEY_HIDDEN, current).apply()
        _hiddenIds.value = current
    }

    fun unhideAllChannels() {
        prefs.edit().putStringSet(KEY_HIDDEN, emptySet()).apply()
        _hiddenIds.value = emptySet()
    }

    /**
     * Stockage hors-ligne en dur des chaînes & EPG pour un démarrage instantané sans spinner.
     */
    fun saveCachedChannels(configId: String, channels: List<Channel>) {
        try {
            val array = JSONArray()
            channels.forEach { ch ->
                val obj = JSONObject().apply {
                    put("id", ch.id)
                    put("name", ch.name)
                    put("group", ch.group)
                    put("logoUrl", ch.logoUrl)
                    put("streamUrl", ch.streamUrl)
                    put("tvgId", ch.tvgId)
                    put("isFavorite", ch.isFavorite)
                    put("serverId", ch.serverId)
                    ch.number?.let { put("number", it) }

                    ch.currentProgram?.let { prog ->
                        val progObj = JSONObject().apply {
                            put("title", prog.title)
                            put("category", prog.category)
                            put("startTime", prog.startTime)
                            put("endTime", prog.endTime)
                            put("progress", prog.progress.toDouble())
                            put("posterUrl", prog.posterUrl)
                            put("description", prog.description)
                            put("nextTitle", prog.nextTitle)
                            put("nextStartTime", prog.nextStartTime)
                            put("startMillis", prog.startMillis)
                            put("endMillis", prog.endMillis)
                        }
                        put("currentProgram", progObj)
                    }
                }
                array.put(obj)
            }
            val root = JSONObject().apply {
                put("configId", configId)
                put("channels", array)
            }
            prefs.edit().putString(KEY_CACHED_CHANNELS, root.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Chaînes en cache du serveur [configId] (vide si le cache appartient à un autre serveur).
     * Les programmes terminés depuis la mise en cache sont écartés.
     */
    fun getCachedChannels(configId: String, now: Long = System.currentTimeMillis()): List<Channel> {
        val rawJson = prefs.getString(KEY_CACHED_CHANNELS, null) ?: return emptyList()
        val list = mutableListOf<Channel>()
        try {
            if (!rawJson.trimStart().startsWith("{")) return emptyList()
            val root = JSONObject(rawJson)
            if (root.optString("configId") != configId) return emptyList()
            val array = root.optJSONArray("channels") ?: return emptyList()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val progObj = obj.optJSONObject("currentProgram")
                val program = if (progObj != null) {
                    EpgProgram(
                        title = progObj.optString("title", ""),
                        category = progObj.optStringOrNull("category"),
                        startTime = progObj.optString("startTime", ""),
                        endTime = progObj.optString("endTime", ""),
                        progress = progObj.optDouble("progress", 0.0).toFloat(),
                        posterUrl = progObj.optStringOrNull("posterUrl"),
                        description = progObj.optStringOrNull("description"),
                        nextTitle = progObj.optStringOrNull("nextTitle"),
                        nextStartTime = progObj.optStringOrNull("nextStartTime"),
                        startMillis = progObj.optLong("startMillis", 0L),
                        endMillis = progObj.optLong("endMillis", 0L)
                    ).let { EpgResolver.refreshCached(it, now) }
                } else null

                list.add(
                    Channel(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        group = obj.getString("group"),
                        logoUrl = obj.optStringOrNull("logoUrl"),
                        streamUrl = obj.getString("streamUrl"),
                        tvgId = obj.optStringOrNull("tvgId"),
                        isFavorite = obj.optBoolean("isFavorite", false),
                        currentProgram = program,
                        serverId = obj.optStringOrNull("serverId"),
                        number = obj.optInt("number", -1).takeIf { it > 0 }
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        return list
    }

    private fun persistConfigs(list: List<SourceConfig>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CONFIGS, array.toString()).apply()

        _configs.value = list
        _activeConfig.value = list.firstOrNull { it.isActive } ?: list.firstOrNull()
    }

    fun saveOrUpdateConfig(config: SourceConfig) {
        val current = _configs.value.toMutableList()
        val index = current.indexOfFirst { it.id == config.id }

        val shouldBeActive = if (current.isEmpty()) true else config.isActive
        val finalConfig = config.copy(isActive = shouldBeActive)

        if (shouldBeActive) {
            for (i in 0 until current.size) {
                current[i] = current[i].copy(isActive = false)
            }
        }

        if (index != -1) {
            current[index] = finalConfig
        } else {
            current.add(finalConfig)
        }

        persistConfigs(current)
    }

    fun setActiveConfig(configId: String) {
        val current = _configs.value.map {
            it.copy(isActive = (it.id == configId))
        }
        persistConfigs(current)
    }

    fun deleteConfig(configId: String) {
        val current = _configs.value.filterNot { it.id == configId }
        val updated = if (current.isNotEmpty() && current.none { it.isActive }) {
            current.mapIndexed { idx, item -> item.copy(isActive = (idx == 0)) }
        } else {
            current
        }
        persistConfigs(updated)
        forgetSourceData(configId)
    }

    /**
     * Efface ce qui appartient à une source supprimée : prénom mémorisé, et pour Xtream / M3U les favoris
     * et flux choisis de ses chaînes (identifiants préfixés par la source ; ceux de Dispatcharr sont des UUID).
     */
    private fun forgetSourceData(configId: String) {
        prefs.edit().remove(KEY_USER_NAME_PREFIX + configId).apply()
        val prefixes = listOf("xt-${configId.take(8)}-", "m3u-${configId.take(8)}-")
        fun ownedBySource(channelId: String) = prefixes.any { channelId.startsWith(it) }

        _favoriteIds.value.filter(::ownedBySource).takeIf { it.isNotEmpty() }?.let { owned ->
            persistFavorites(_favoriteIds.value - owned.toSet())
        }
        _selectedStreams.value.keys.filter(::ownedBySource).forEach { setSelectedStream(it, null) }
    }

    companion object {
        private const val PREFS_NAME = "iptv_dispatcharr_prefs"
        private const val KEY_CONFIGS = "dispatcharr_configs_list"
        private const val KEY_UI_DENSITY = "iptv_ui_density"
        private const val KEY_THEME_BG = "iptv_theme_bg"
        private const val KEY_ACCENT_COLOR = "iptv_accent_color"
        private const val KEY_FAVORITES = "iptv_favorite_ids"
        private const val KEY_FAVORITE_ORDER = "iptv_favorite_order"
        private const val KEY_HIDDEN_GROUPS = "iptv_hidden_groups"
        private const val KEY_HIDDEN = "iptv_hidden_ids"
        private const val KEY_CACHED_CHANNELS = "iptv_cached_channels_json"
        private const val KEY_SELECTED_STREAMS = "iptv_selected_streams"
        private const val KEY_USER_NAME_PREFIX = "iptv_user_name_"
    }
}
