package io.github.sim7b.tele.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.StringRes
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Interface density: factor applied to every dimension (dp/sp). */
enum class UiDensity(@param:StringRes val labelRes: Int, val scaleFactor: Float, @param:StringRes val descriptionRes: Int) {
    COMPACT(R.string.density_compact, 0.85f, R.string.density_compact_description),
    NORMAL(R.string.density_normal, 1.0f, R.string.density_normal_description),
    LARGE(R.string.density_large, 1.15f, R.string.density_large_description)
}

/** App background (background, surface and card colors). */
enum class AppThemeBackground(
    @param:StringRes val labelRes: Int,
    val backgroundColorHex: Long,
    val surfaceColorHex: Long,
    val surfaceCardColorHex: Long,
    val isLight: Boolean = false
) {
    ORIGINAL(R.string.theme_original, 0xFF0B0D12, 0xFF151821, 0xFF1F232D),
    OLED_BLACK(R.string.theme_oled_black, 0xFF000000, 0xFF0B0B0C, 0xFF161618),
    MIDNIGHT_BLUE(R.string.theme_midnight_blue, 0xFF0F172A, 0xFF1E293B, 0xFF334155),
    DARK_PURPLE(R.string.theme_dark_purple, 0xFF130D24, 0xFF1E1538, 0xFF2E2152),
    ANTHRACITE(R.string.theme_anthracite, 0xFF18181B, 0xFF27272A, 0xFF3F3F46),
    LIGHT(R.string.theme_light, 0xFFF8FAFC, 0xFFFFFFFF, 0xFFE2E8F0, isLight = true)
}

/** Accent color, reserved for indicators (progress, selection). */
enum class AppAccentColor(
    @param:StringRes val labelRes: Int,
    val colorHex: Long
) {
    CYAN(R.string.accent_cyan, 0xFF00E5FF),
    NEON_BLUE(R.string.accent_blue, 0xFF3B82F6),
    ELECTRIC_PURPLE(R.string.accent_purple, 0xFFA855F7),
    EMERALD(R.string.accent_emerald, 0xFF10B981),
    ROSE(R.string.accent_rose, 0xFFF43F5E),
    AMBER(R.string.accent_amber, 0xFFF59E0B)
}

/**
 * Persistent settings (SharedPreferences): channel sources, appearance, ordered favorites,
 * hidden channels and categories, streams selected per channel and channel cache of the active source.
 *
 * The keys and the preferences file name are kept as is so that existing installs keep their settings.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val channelCache = ChannelCache(File(context.noBackupFilesDir, CHANNEL_CACHE_FILE))

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

    // Favorites in the order chosen by the user (reorderable on the home grid)
    private val _favoriteIds = MutableStateFlow<List<String>>(emptyList())
    val favoriteIds: StateFlow<List<String>> = _favoriteIds.asStateFlow()

    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()

    // Categories (source groups) hidden from the home screen, the channel panel and channel switching
    private val _hiddenGroups = MutableStateFlow<Set<String>>(emptySet())
    val hiddenGroups: StateFlow<Set<String>> = _hiddenGroups.asStateFlow()

    // Favorites migrated from the legacy storage (unordered set): to be sorted once by channel order.
    // Declared before init {} so that it is not reset after the preferences are loaded.
    private var legacyFavoritesUnordered = false

    // Stream selected manually per channel (absent = Dispatcharr automatic mode)
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
                Log.w(TAG, "Unable to read the sources: ${e.javaClass.simpleName}")
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
                    name = obj.optString("name", "Stream"),
                    url = url,
                    id = obj.optStringOrNull("id"),
                    provider = obj.optStringOrNull("provider")
                )
            }.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read the selected streams: ${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    /** First name shown in the welcome message, saved per server to show it right away at startup. */
    fun getUserDisplayName(configId: String): String? =
        prefs.getString(KEY_USER_NAME_PREFIX + configId, null)?.takeIf { it.isNotBlank() }

    fun saveUserDisplayName(configId: String, name: String) {
        prefs.edit().putString(KEY_USER_NAME_PREFIX + configId, name).apply()
    }

    /** Saves the stream selected for a channel (null [stream] = back to automatic mode). */
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

    /** Favorites order; migrates the legacy unordered storage when it does not exist yet. */
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

    /** Sorts the migrated favorites once in channel order (as they were displayed before). */
    fun applyLegacyFavoriteOrder(channelOrder: List<String>) {
        if (!legacyFavoritesUnordered) return
        legacyFavoritesUnordered = false
        val rank = channelOrder.withIndex().associate { (index, id) -> id to index }
        persistFavorites(_favoriteIds.value.sortedBy { rank[it] ?: Int.MAX_VALUE })
    }

    /** Appends the channel to the favorites, or removes it. */
    fun toggleFavoriteChannel(channelId: String) {
        val current = _favoriteIds.value
        persistFavorites(if (channelId in current) current - channelId else current + channelId)
    }

    /** Moves a favorite to position [toIndex] (clamped to the favorites list). */
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

    /** Saves the channels and current programs of the active source (to call off the main thread). */
    fun saveCachedChannels(configId: String, channels: List<Channel>) {
        try {
            channelCache.save(configId, channels)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to save the channel cache: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Cached channels of server [configId] (empty when the cache belongs to another server), to call off
     * the main thread. Programs that ended since caching are dropped.
     */
    fun getCachedChannels(configId: String, now: Long = System.currentTimeMillis()): List<Channel> {
        // Cache of the previous versions, kept in the preferences: dropped, the next sync rebuilds it
        if (prefs.contains(KEY_CACHED_CHANNELS)) prefs.edit().remove(KEY_CACHED_CHANNELS).apply()
        return try {
            channelCache.load(configId, now)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read the channel cache: ${e.javaClass.simpleName}")
            emptyList()
        }
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
     * Clears what belongs to a deleted source: saved first name and, for Xtream / M3U, the favorites
     * and selected streams of its channels (ids prefixed by the source; Dispatcharr ones are UUIDs).
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
        private const val TAG = "Settings"
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
        private const val CHANNEL_CACHE_FILE = "channel_cache.jsonl"
    }
}
