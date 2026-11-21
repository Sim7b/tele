package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.epg.Catchup
import io.github.sim7b.tele.data.epg.EpgText
import android.util.Log
import io.github.sim7b.tele.BuildConfig
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UserFacingException
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.failure
import io.github.sim7b.tele.data.model.*
import io.github.sim7b.tele.data.optStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class DispatcharrClient(
    @Volatile private var currentConfig: SourceConfig? = null
) {
    // The API key travels as a request tag and is only added for the origin of its server
    // (not on redirects to another host, nor for a request built before a source change).
    private val apiKeyInterceptor = ApiKeyInterceptor { it.tag(ApiKeyAuth::class.java) }

    // Derived from the shared client: same connection pool as the player and the images
    private val client = HttpClients.base.newBuilder()
        .addInterceptor { chain ->
            val config = currentConfig
            val userAgent = DeviceUserAgent.forDispatcharr(config?.customUserAgent)

            val requestBuilder = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                // Shown with the TV in the admin page of the Télé API plugin
                .header(DeviceUserAgent.VERSION_HEADER, BuildConfig.VERSION_NAME)
            config?.let { ApiKeyAuth.of(it) }?.let { requestBuilder.tag(ApiKeyAuth::class.java, it) }

            chain.proceed(requestBuilder.build())
        }
        // A call of the plugin that fails sets it aside for a while ([PluginHealth]); not a call cancelled here
        .addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.encodedPath.startsWith(TeleApi.ROOT_PATH)) return@addInterceptor chain.proceed(request)
            val response = try {
                chain.proceed(request)
            } catch (e: java.io.IOException) {
                if (!chain.call().isCanceled()) pluginFailed(request)
                throw e
            }
            if (PluginHealth.isFailure(response.code, response.header("Content-Type"))) pluginFailed(request)
            response
        }
        .addNetworkInterceptor(apiKeyInterceptor)
        .build()

    // Short answers of the plugin (its features, the free connections, the health of the streams, the segments of a
    // movie): a plugin that does not answer must not hold the start, nor a channel, more than a few seconds
    private val quickClient = client.newBuilder()
        .callTimeout(QUICK_CALL_S, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Without the application interceptor: the key of the active server must not replace the tested one
    private val testClient = HttpClients.base.newBuilder()
        .addNetworkInterceptor(apiKeyInterceptor)
        .build()

    // Reference tables, cached once fetched successfully (immutable maps replaced as a whole)
    @Volatile private var groupsCache: Map<Int, String>? = null
    @Volatile private var epgDataCache: Map<Int, DispatcharrEpgData>? = null
    @Volatile private var accountsCache: Map<Int, Pair<String, Boolean>>? = null
    // Last guide of the plugin, with its URL and ETag: given again when the server answers 304
    @Volatile private var guideCache: Triple<String, String, List<DispatcharrEpgChannel>>? = null
    // Features of the Télé API plugin of the server, probed again from time to time, set aside when it fails
    private val pluginHealth = PluginHealth()
    private val probeLock = kotlinx.coroutines.sync.Mutex()
    // The live stats of the server refused this key (admin only): not asked again until a refresh
    @Volatile private var statsRefused = false

    fun setConfig(config: SourceConfig?) {
        if (config != currentConfig) clearCaches()
        this.currentConfig = config
    }

    /** Forgets the groups, EPG data and accounts (manual refresh: they may have changed on the server). */
    fun clearCaches() {
        groupsCache = null
        epgDataCache = null
        accountsCache = null
        pluginHealth.reset()
        statsRefused = false
        guideCache = null
    }

    /**
     * Features of the Télé API plugin (`/api/tele/v1/`), probed once per server and manual refresh: its
     * endpoints work with a standard user key. Empty when the plugin is missing, disabled or incompatible
     * (without it, the web interface of Dispatcharr answers the path with an HTML page).
     */
    private suspend fun teleApi(): Set<String> {
        pluginHealth.current()?.let { return it }
        if (baseUrl.isBlank()) return emptySet()
        // One probe at a time: the calls made meanwhile wait for its answer
        return probeLock.withLock {
            pluginHealth.current() ?: run {
                val features = TeleApi.features(getPluginInfo()) ?: emptySet()
                // A probe that failed already set the plugin aside: kept so
                if (pluginHealth.current() == null) pluginHealth.probed(features)
                if (features.isNotEmpty()) Log.i(TAG, "Télé API plugin: $features")
                pluginHealth.known()
            }
        }
    }

    private fun pluginFailed(request: Request) {
        if (pluginHealth.known().isNotEmpty()) Log.w(TAG, "Télé API plugin set aside after a failure: ${request.url.encodedPath}")
        pluginHealth.failed()
    }

    /** A failure while reading an answer (after its headers): the plugin is set aside as for a failed call. */
    private fun readFailed(request: Request, e: Exception) {
        if (e !is CancellationException && request.url.encodedPath.startsWith(TeleApi.ROOT_PATH)) pluginFailed(request)
    }

    /**
     * Message of the Dispatcharr admin for the TVs (Télé API plugin), read again at each call; null without the
     * plugin or a message.
     */
    suspend fun getServerMessage(): String? {
        if (baseUrl.isBlank() || teleApi().isEmpty()) return null
        return TeleApi.message(getPluginInfo())
    }

    /** Answer of the plugin info endpoint; null, silently, when it is not a JSON object (plugin missing). */
    private suspend fun getPluginInfo(): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl${TeleApi.INFO_PATH}").get().build()
        try {
            quickClient.newCall(request).executeCancellable { response ->
                // Without the plugin, the web interface of Dispatcharr answers with its HTML page: not an error
                if (!response.isSuccessful) null
                else runCatching { JSONObject(HttpClients.jsonString(response.body)) }.getOrNull()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /** Channels of the plugin, read by pages; null when a page failed (the standard API takes over). */
    private suspend fun channelsFromPlugin(): List<DispatcharrChannel>? {
        val channels = mutableListOf<DispatcharrChannel>()
        val seenUuids = HashSet<String>()
        for (page in 1..TeleApi.MAX_CHANNEL_PAGES) {
            var read = 0
            // With the current and next program of each channel: shown before the guide is loaded
            val url = "$baseUrl${TeleApi.CHANNELS_PATH}?programs=1&page_size=${TeleApi.CHANNELS_PAGE_SIZE}&page=$page"
            val fetched = fetchObjects(url) { item ->
                read++
                TeleApi.parseChannel(item, baseUrl)?.takeIf { seenUuids.add(it.uuid) }?.let { channels.add(it) }
            }
            if (!fetched) return null
            if (read < TeleApi.CHANNELS_PAGE_SIZE) break
        }
        return channels.apply { sortBy { it.channelNumber ?: Int.MAX_VALUE } }
    }

    private val baseUrl: String
        get() = currentConfig?.normalizedUrl ?: ""

    /**
     * Tests the connection to a Dispatcharr server; returns the number of channels.
     */
    suspend fun testConnection(config: SourceConfig): Result<Int> = withContext(Dispatchers.IO) {
        val testUrl = "${config.normalizedUrl}/api/channels/channels/"
        val requestBuilder = Request.Builder()
            .url(testUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", DeviceUserAgent.forDispatcharr(config.customUserAgent))

        ApiKeyAuth.of(config)?.let { requestBuilder.tag(ApiKeyAuth::class.java, it) }

        try {
            testClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    return@withContext when (code) {
                        401, 403 -> failure(R.string.error_dispatcharr_api_key_invalid, code)
                        404 -> failure(R.string.error_dispatcharr_not_found)
                        else -> failure(R.string.error_server_http, code)
                    }
                }

                val count = try {
                    JsonStreams.forEachObject(response.body.byteStream()) {}
                } catch (e: Exception) {
                    return@withContext failure(R.string.error_not_dispatcharr_api)
                }

                Result.success(count)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams the objects of a list endpoint (raw array or paginated object) to [action];
     * false when the request failed. [action] may have received part of the objects then.
     */
    private suspend fun fetchObjects(url: String, action: (JSONObject) -> Unit): Boolean {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) {
                    // Path only: the query may hold search parameters, never logged
                    Log.w(TAG, "HTTP ${response.code}: ${request.url.encodedPath}")
                    return@executeCancellable false
                }
                JsonStreams.forEachObject(response.body.byteStream(), action = action)
                true
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            readFailed(request, e)
            // Exception type only: parsing errors may carry part of the response
            Log.w(TAG, "Request failed: ${request.url.encodedPath} (${e.javaClass.simpleName})")
            false
        }
    }

    private suspend fun fetchGroupsMap(): Map<Int, String> = withContext(Dispatchers.IO) {
        groupsCache?.let { return@withContext it }
        if (baseUrl.isBlank()) return@withContext emptyMap()

        val groups = HashMap<Int, String>()
        val fetched = fetchObjects("$baseUrl/api/channels/groups/") { item ->
            val id = item.optInt("id", -1)
            val name = item.optStringOrNull("name")
            if (id != -1 && name != null) {
                groups[id] = name
            }
        }
        if (!fetched) return@withContext emptyMap()
        groups.also { groupsCache = it }
    }

    /**
     * epg_data_id -> tvg_id table: Dispatcharr channels reference their guide through epg_data_id,
     * while the EPG grid is indexed by the tvg_id of the EPGData.
     */
    private suspend fun fetchEpgDataMap(): Map<Int, DispatcharrEpgData> = withContext(Dispatchers.IO) {
        epgDataCache?.let { return@withContext it }
        if (baseUrl.isBlank()) return@withContext emptyMap()

        val epgData = HashMap<Int, DispatcharrEpgData>()
        val fetched = fetchObjects("$baseUrl/api/epg/epgdata/") { item ->
            val id = item.optInt("id", -1)
            val tvgId = item.optStringOrNull("tvg_id")
            if (id != -1 && tvgId != null) {
                epgData[id] = DispatcharrEpgData(id, tvgId, item.optStringOrNull("name") ?: tvgId)
            }
        }
        if (!fetched) return@withContext emptyMap()
        epgData.also { epgDataCache = it }
    }

    /** EPGData list (to match by name the channels without epg_data_id). */
    suspend fun getEpgData(): List<DispatcharrEpgData> = fetchEpgDataMap().values.toList()

    suspend fun getChannels(): Result<List<DispatcharrChannel>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext failure(R.string.error_dispatcharr_not_configured)
        }

        // One list with the group and guide key of each channel, in place of the three tables below
        if ("channels" in teleApi()) {
            channelsFromPlugin()?.let { return@withContext Result.success(it) }
        }

        try {
            coroutineScope {
                val groupsDeferred = async { fetchGroupsMap() }
                val epgDataDeferred = async { fetchEpgDataMap() }

                val request = Request.Builder()
                    .url("$baseUrl/api/channels/channels/")
                    .get()
                    .build()

                // Channels streamed while the reference tables load; group and EPG key resolved afterwards
                val parsed = mutableListOf<ParsedChannel>()
                // The uuid is the channel id in the app: it must stay unique (lazy list keys)
                val seenUuids = HashSet<String>()
                client.newCall(request).executeCancellable { response ->
                    if (!response.isSuccessful) {
                        throw UserFacingException(
                            when (response.code) {
                                401, 403 -> UiText.Res(R.string.error_dispatcharr_api_key_refused, response.code)
                                else -> UiText.Res(R.string.error_server_http, response.code)
                            }
                        )
                    }
                    JsonStreams.forEachObject(response.body.byteStream()) { item ->
                        parseChannel(item)?.takeIf { seenUuids.add(it.channel.uuid) }?.let { parsed.add(it) }
                    }
                }

                val groupsMap = groupsDeferred.await()
                val epgDataMap = epgDataDeferred.await()
                val channelList = parsed.map { (channel, groupId, epgDataId) ->
                    channel.copy(
                        group = groupsMap[groupId] ?: Channel.NO_GROUP,
                        tvgId = epgDataMap[epgDataId]?.tvgId ?: channel.tvgId
                    )
                }.toMutableList()

                channelList.sortBy { it.channelNumber ?: Int.MAX_VALUE }

                Result.success(channelList)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class ParsedChannel(val channel: DispatcharrChannel, val groupId: Int, val epgDataId: Int)

    /** Channel of the API, before its group and EPG data are resolved from their tables. */
    private fun parseChannel(item: JSONObject): ParsedChannel? {
        val uuid = item.optStringOrNull("uuid") ?: item.optStringOrNull("id") ?: return null
        val logoId = item.optInt("effective_logo_id", item.optInt("logo_id", -1))
        val channelNumber = item.optDouble("effective_channel_number", item.optDouble("channel_number", 0.0))
            .takeIf { !it.isNaN() }?.toInt()?.takeIf { it > 0 }
        val channel = DispatcharrChannel(
            id = item.optStringOrNull("id") ?: uuid,
            uuid = uuid,
            channelNumber = channelNumber,
            // Language-neutral fallback: the data layer has no access to the string resources
            name = item.optStringOrNull("effective_name") ?: item.optStringOrNull("name") ?: "#${channelNumber ?: uuid.take(8)}",
            group = "",
            logoUrl = if (logoId > 0) "$baseUrl/api/channels/logos/$logoId/cache/" else item.optStringOrNull("logo_url"),
            streamUrl = "$baseUrl/proxy/ts/stream/$uuid",
            // Replaced by the tvg_id of the linked EPGData when there is one
            tvgId = item.optStringOrNull("effective_tvg_id") ?: item.optStringOrNull("tvg_id") ?: "",
            isRadio = item.optBoolean("effective_is_radio", item.optBoolean("is_radio", false)),
            catchupDays = if (item.optBoolean("is_catchup", false)) item.optInt("catchup_days", 0).coerceIn(0, Catchup.MAX_DAYS) else 0
        )
        return ParsedChannel(
            channel = channel,
            groupId = item.optInt("effective_channel_group_id", item.optInt("channel_group_id", -1)),
            epgDataId = item.optInt("effective_epg_data_id", item.optInt("epg_data_id", -1))
        )
    }

    /**
     * Fetches the full EPG grid (flat array of programs or channels with nested programs).
     * Programs are indexed by tvg_id (or channel uuid for "dummy" programs).
     */
    suspend fun getEpgGrid(
        days: Int = 1,
        channelProfileId: String? = null,
        // Hours of past programs: a day for the channels with catch-up, else the hour of the server grid
        pastHours: Int = 1
    ): Result<List<DispatcharrEpgChannel>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext failure(R.string.error_dispatcharr_not_configured)
        }

        // Télé API plugin: the programs of the user's channels only, and nothing sent again while unchanged (304)
        if ("guide" in teleApi()) {
            val url = "$baseUrl${TeleApi.GUIDE_PATH}?hours=${days.coerceIn(1, 2) * 24}&past=${pastHours.coerceIn(1, 24)}"
            val cached = guideCache?.takeIf { it.first == url }
            val request = Request.Builder().url(url).get()
                .apply { cached?.second?.let { header("If-None-Match", it) } }
                .build()
            try {
                val (code, etag, channels) = readGrid(request)
                if (code == 304 && cached != null) return@withContext Result.success(cached.third)
                if (code in 200..299) {
                    guideCache = etag?.let { Triple(url, it, channels) }
                    return@withContext Result.success(channels)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Guide of the plugin unavailable (${e.javaClass.simpleName}), Dispatcharr grid used")
            }
        }

        val urlBuilder = "$baseUrl/api/epg/grid/".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext failure(R.string.error_invalid_address)

        urlBuilder.addQueryParameter("days", days.toString())
        if (pastHours > 1) urlBuilder.addQueryParameter("prev_days", "1")
        channelProfileId?.let { urlBuilder.addQueryParameter("channel_profile_id", it) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        try {
            val (code, _, channels) = readGrid(request)
            if (code !in 200..299) throw UserFacingException(UiText.Res(R.string.error_server_http, code))
            Result.success(channels)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** HTTP code, ETag and channels of a grid answer (the Dispatcharr grid, or the guide of the plugin). */
    private suspend fun readGrid(request: Request): Triple<Int, String?, List<DispatcharrEpgChannel>> {
        val epgMap = mutableMapOf<String, MutableList<GuideProgram>>()
        val (code, etag) = client.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) return@executeCancellable response.code to null
            var index = 0
            JsonStreams.forEachObject(response.body.byteStream()) { item ->
                val i = index++
                // Case 1: channel object with a nested "programs" array
                if (item.has("programs")) {
                    val channelKey = item.optStringOrNull("tvg_id")
                        ?: item.optStringOrNull("channel_uuid")
                        ?: item.optStringOrNull("uuid")
                        ?: return@forEachObject
                    val programsArray = item.optJSONArray("programs") ?: JSONArray()
                    val progList = epgMap.getOrPut(channelKey) { mutableListOf() }

                    for (j in 0 until programsArray.length()) {
                        val progObj = programsArray.optJSONObject(j) ?: continue
                        parseProgram(progObj, channelKey, "$channelKey-$j")?.let { progList.add(it) }
                    }
                }
                // Case 2: flat array of individual programs (format of /api/epg/grid/)
                else if (item.has("title")) {
                    val key = item.optStringOrNull("tvg_id")
                        ?: item.optStringOrNull("channel_uuid")
                        ?: item.optStringOrNull("channel_id")
                        ?: return@forEachObject
                    parseProgram(item, key, "$key-$i")?.let { epgMap.getOrPut(key) { mutableListOf() }.add(it) }
                }
            }
            response.code to response.header("ETag")
        }
        val channels = epgMap.map { (key, progs) ->
            progs.sortBy { it.startMillis }
            DispatcharrEpgChannel(channelUuid = key, channelName = key, programs = progs)
        }
        return Triple(code, etag, channels)
    }

    private fun parseProgram(obj: JSONObject, channelKey: String, fallbackId: String): GuideProgram? {
        // A program without title is dropped, as in XMLTV guides
        val title = obj.optStringOrNull("title") ?: return null
        val startIso = obj.optStringOrNull("start_time") ?: obj.optStringOrNull("start") ?: return null
        val endIso = obj.optStringOrNull("end_time") ?: obj.optStringOrNull("end") ?: return null
        val startMillis = EpgResolver.parseIsoToMillis(startIso)
        val endMillis = EpgResolver.parseIsoToMillis(endIso)
        if (startMillis == 0L || endMillis <= startMillis) return null

        val rawPoster = obj.optStringOrNull("poster") ?: obj.optStringOrNull("icon") ?: obj.optStringOrNull("image")
        val posterUrl = if (rawPoster != null && rawPoster.startsWith("/")) "$baseUrl$rawPoster" else rawPoster

        return GuideProgram(
            id = obj.optStringOrNull("id") ?: fallbackId,
            channelUuid = channelKey,
            title = title,
            description = EpgText.clean(obj.optStringOrNull("description")),
            startTimeIso = startIso,
            endTimeIso = endIso,
            posterUrl = posterUrl,
            rating = obj.optStringOrNull("rating"),
            startMillis = startMillis,
            endMillis = endMillis
        )
    }

    /**
     * Artwork and categories of the programs aired between [fromMillis] and [toMillis], indexed by program id.
     * The EPG grid does not contain them; the program search exposes them in custom_properties.
     */
    suspend fun getProgramArtwork(fromMillis: Long, toMillis: Long): Map<String, ProgramArtwork> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext emptyMap()

        val artwork = mutableMapOf<String, ProgramArtwork>()
        for (page in 1..MAX_ARTWORK_PAGES) {
            val url = "$baseUrl/api/epg/programs/search/".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("end_after", isoInstant(fromMillis))
                ?.addQueryParameter("start_before", isoInstant(toMillis))
                ?.addQueryParameter("fields", "id,custom_properties")
                ?.addQueryParameter("page_size", ARTWORK_PAGE_SIZE.toString())
                ?.addQueryParameter("page", page.toString())
                ?.build()
                ?: break

            var count = 0
            val fetched = fetchObjects(url.toString()) { item ->
                count++
                val id = item.optStringOrNull("id") ?: return@fetchObjects
                val props = item.optJSONObject("custom_properties") ?: return@fetchObjects
                val image = props.optStringOrNull("icon")
                val category = props.optJSONArray("categories")?.let { array ->
                    (0 until array.length()).firstNotNullOfOrNull { array.optString(it).takeIf { c -> c.isNotBlank() } }
                }
                if (image != null || category != null) {
                    artwork[id] = ProgramArtwork(imageUrl = image, category = category)
                }
            }
            if (!fetched || count < ARTWORK_PAGE_SIZE) break
        }
        artwork
    }

    private fun isoInstant(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    /** M3U accounts (providers): display name and active state, cached. */
    private suspend fun fetchAccounts(): Map<Int, Pair<String, Boolean>> = withContext(Dispatchers.IO) {
        accountsCache?.let { return@withContext it }
        if (baseUrl.isBlank()) return@withContext emptyMap()

        val accounts = HashMap<Int, Pair<String, Boolean>>()
        val fetched = fetchObjects("$baseUrl/api/m3u/accounts/") { item ->
            val id = item.optInt("id", -1)
            if (id != -1) {
                accounts[id] = (item.optStringOrNull("name") ?: "#$id") to item.optBoolean("is_active", true)
            }
        }
        if (!fetched) return@withContext emptyMap()
        accounts.also { accountsCache = it }
    }

    /**
     * Streams available for a channel, in Dispatcharr priority order.
     * Each stream is played through the Dispatcharr proxy (by stream hash), so Dispatcharr counts the
     * viewer, shows its stats and applies the provider connection limits. skip_redirect asks Dispatcharr
     * to proxy it even when the stream profile is Redirect (ignored for users without that right).
     */
    suspend fun getChannelStreams(channelServerId: String): Result<List<StreamSource>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext failure(R.string.error_dispatcharr_not_configured)
        }

        try {
            coroutineScope {
                val accountsDeferred = async { fetchAccounts() }
                val items = mutableListOf<JSONObject>()
                if (!fetchObjects("$baseUrl/api/channels/channels/$channelServerId/streams/") { items.add(it) }) {
                    throw UserFacingException(UiText.Res(R.string.error_channel_streams))
                }
                val accounts = accountsDeferred.await()

                val streams = mutableListOf<StreamSource>()
                for ((i, item) in items.withIndex()) {
                    if (item.optBoolean("is_stale", false)) continue

                    val accountId = item.optInt("m3u_account", -1)
                    val account = accounts[accountId]
                    if (account != null && !account.second) continue

                    val url = item.optStringOrNull("stream_hash")?.let { proxyStreamUrl(it) }
                        ?: item.optStringOrNull("url")
                        ?: continue
                    val rawName = item.optStringOrNull("name") ?: "#${i + 1}"

                    streams.add(
                        StreamSource(
                            name = StreamNames.clean(rawName),
                            url = url,
                            id = item.optStringOrNull("id"),
                            provider = account?.first,
                            qualityTags = StreamNames.qualityTags(rawName),
                            details = item.optJSONObject("stream_stats")?.let { StreamNames.describeStats(it) },
                            measuredHeight = StreamQuality.parseHeight(item.optJSONObject("stream_stats")?.optStringOrNull("resolution"))
                        )
                    )
                }
                Result.success(withHealth(streams))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** [streams] with the ones that failed lately on the server marked (Télé API plugin); unchanged without it. */
    private suspend fun withHealth(streams: List<StreamSource>): List<StreamSource> {
        val ids = streams.mapNotNull { it.id?.takeIf { id -> id.toIntOrNull() != null } }.take(TeleApi.MAX_HEALTH_IDS)
        if (ids.isEmpty() || "stream_health" !in teleApi()) return streams
        val failing = TeleApi.unhealthyStreams(getObject("$baseUrl${TeleApi.STREAM_HEALTH_PATH}?ids=${ids.joinToString(",")}", quick = true))
        if (failing.isEmpty()) return streams
        return streams.map { if (it.id in failing) it.copy(recentFailures = true) else it }
    }

    /**
     * Streams the items of a paginated VOD list ("movies", "series") to [action] (called by one page at
     * a time). The server serves at most 100 items a page: libraries of 50,000 titles take 500 pages,
     * fetched [VOD_PARALLEL_PAGES] at a time. Fails when the first page is not available (no VOD module).
     */
    suspend fun forEachVodItem(list: String, maxItems: Int, action: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val lock = Any()
        var total = 0
        val lastPage = maxItems / VOD_PAGE_SIZE + 1
        var first = 1
        while (first <= lastPage) {
            val pages = (first..minOf(first + VOD_PARALLEL_PAGES - 1, lastPage)).toList()
            // Each page collected apart, then handed over whole: [action] never runs concurrently
            val results = coroutineScope {
                pages.map { page ->
                    async {
                        val items = ArrayList<JSONObject>(VOD_PAGE_SIZE)
                        val fetched = fetchObjects("$baseUrl/api/vod/$list/?page_size=$VOD_PAGE_SIZE&page=$page") { items.add(it) }
                        if (fetched) items else null
                    }
                }.map { it.await() }
            }
            // Past the last page the server answers 404
            if (results.first() == null && first == 1) throw UserFacingException(UiText.Res(R.string.error_vod_unavailable))
            var complete = true
            for (items in results) {
                if (items == null) {
                    complete = false
                    break
                }
                synchronized(lock) {
                    items.forEach { if (total++ < maxItems) action(it) }
                }
                if (items.size < VOD_PAGE_SIZE) {
                    complete = false
                    break
                }
            }
            if (!complete || total >= maxItems) break
            first += VOD_PARALLEL_PAGES
        }
        Log.i(TAG, "VOD $list: $total items")
    }

    /** Objects of a VOD list endpoint (the episodes of a series); false when the request failed. */
    suspend fun forEachVodObject(path: String, action: (JSONObject) -> Unit): Boolean =
        withContext(Dispatchers.IO) { fetchObjects("$baseUrl/api/vod/$path", action) }

    /** A VOD object (provider information of a movie or a series), null when unavailable. */
    suspend fun getVodObject(path: String): JSONObject? = getObject("$baseUrl/api/vod/$path")

    /**
     * A JSON object of the server, null when unavailable (no admin rights, older server, error). [quick]: a short
     * answer of the plugin, given up after a few seconds.
     */
    private suspend fun getObject(url: String, quick: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        try {
            (if (quick) quickClient else client).newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) return@executeCancellable null
                JSONObject(HttpClients.jsonString(response.body))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            readFailed(request, e)
            Log.w(TAG, "Request failed: ${request.url.encodedPath} (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Poster of a VOD logo: made light by the Télé API plugin (a JPEG the width of a card, kept on the server) when
     * it does, else the cached image of Dispatcharr. Both are served without authentication.
     */
    fun posterUrl(logoId: String): String =
        if ("vod_posters" in pluginHealth.known()) "$baseUrl${TeleApi.VOD_POSTER_PATH}$logoId/?w=${TeleApi.POSTER_WIDTH}"
        else vodImageUrl("vodlogos/$logoId/cache/")

    /** Artwork of a VOD object ("movies/12/image/"), served without authentication. */
    fun vodImageUrl(path: String): String = "$baseUrl/api/vod/$path"

    /** Proxy URL of a movie or an episode ("movie" / "episode"), played with the API key like the live streams. */
    fun vodStreamUrl(type: String, uuid: String): String = "$baseUrl/proxy/vod/$type/$uuid"

    /** Proxy URL of a single Dispatcharr stream. */
    fun proxyStreamUrl(streamHash: String): String = "$baseUrl/proxy/ts/stream/$streamHash?skip_redirect=true"

    /** True when [url] is played through this Dispatcharr server (and not straight from a provider). */
    fun isProxyUrl(url: String): Boolean = baseUrl.isNotBlank() && url.startsWith("$baseUrl/proxy/")

    /**
     * Playback slots of the provider accounts (free / total), read live: never cached, since
     * [StreamSlots.used] changes with every viewer. Null when unavailable (no Télé API plugin and no admin
     * rights, error) or when no account has a stream limit.
     */
    suspend fun getStreamSlots(): StreamSlots? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        // The plugin reads the counters the server checks itself, with a standard user key
        if ("slots" in teleApi()) {
            getObject("$baseUrl${TeleApi.SLOTS_PATH}", quick = true)?.let { return@withContext TeleApi.parseSlots(it) }
        }
        // Connections in use: only the live stats of the server are right (admin rights needed); the profiles
        // of the accounts keep a count the server does not update. Without them, no count rather than a wrong one.
        // Refused once (a standard user key): not asked again until a refresh
        if (statsRefused) return@withContext null
        val stats = getAdminStats() ?: return@withContext null
        val accounts = mutableListOf<JSONObject>()
        if (!fetchObjects("$baseUrl/api/m3u/accounts/") { accounts.add(it) }) return@withContext null
        StreamSlots.fromAccounts(accounts, stats)
    }

    /** The live stats of the server (admin only); a refusal is remembered ([statsRefused]). */
    private suspend fun getAdminStats(): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/proxy/stats/").get().build()
        try {
            client.newCall(request).executeCancellable { response ->
                if (response.code == 401 || response.code == 403) statsRefused = true
                if (!response.isSuccessful) return@executeCancellable null
                JSONObject(HttpClients.jsonString(response.body))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Request failed: ${request.url.encodedPath} (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Closes the Dispatcharr sessions this TV left open (the previous channel, the home preview), through the
     * Télé API plugin: called when a stream finds no free provider connection. Only when the User-Agent carries
     * the install id ([DeviceUserAgent]): with a custom one, another TV behind the same address would look the
     * same. Returns the number of sessions closed (0 without the plugin or on error).
     */
    suspend fun releaseThisDevice(): Int = withContext(Dispatchers.IO) {
        val config = currentConfig ?: return@withContext 0
        if (baseUrl.isBlank() || !config.customUserAgent.isNullOrBlank() || DeviceUserAgent.installId == null) {
            return@withContext 0
        }
        if ("sessions" !in teleApi()) return@withContext 0
        val body = TeleApi.releaseThisDeviceBody().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl${TeleApi.RELEASE_PATH}").post(body).build()
        val released = try {
            client.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code}: ${request.url.encodedPath}")
                    return@executeCancellable 0
                }
                TeleApi.releasedCount(JSONObject(HttpClients.jsonString(response.body)))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Request failed: ${request.url.encodedPath} (${e.javaClass.simpleName})")
            0
        }
        if (released > 0) Log.i(TAG, "Sessions of this TV closed: $released")
        released
    }

    /**
     * VOD languages measured by the Télé API plugin, by "movie:<category>" / "series:<category>" (audio to
     * subtitles); empty without the plugin or on error.
     */
    suspend fun getVodLanguages(): Map<String, Pair<Set<String>, Set<String>>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || "vod_languages" !in teleApi()) return@withContext emptyMap()
        TeleApi.parseVodLanguages(getObject("$baseUrl${TeleApi.VOD_LANGUAGES_PATH}"))
    }

    /**
     * Sync between the TVs of the user (Télé API plugin): sends [changes] ({collection: {key: {"v", "t", "d"}}})
     * and answers every item of the user. Null without the plugin, when the sync is off on the server, or on error.
     */
    suspend fun syncTvs(changes: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || "sync" !in teleApi()) return@withContext null
        val body = JSONObject().put("changes", changes).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl${TeleApi.SYNC_PATH}").post(body).build()
        try {
            client.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code}: ${request.url.encodedPath}")
                    return@executeCancellable null
                }
                JSONObject(HttpClients.jsonString(response.body))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Request failed: ${request.url.encodedPath} (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * TMDB ids the Télé API plugin found for the movies ("movie") or series ("series") Dispatcharr has none for,
     * by Dispatcharr id; empty without the plugin or a TMDB key on the server.
     */
    /**
     * TMDB facts of the [kind] ("movie" / "series") titles looked up by the server (Télé API plugin), by TMDB id,
     * the changed ones only after [since]; null without the plugin or on error. Gzipped by the server.
     */
    suspend fun getVodFacts(kind: String, since: Double): Pair<Map<String, io.github.sim7b.tele.data.vod.TmdbFacts>, Double>? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || "vod_facts" !in teleApi()) return@withContext null
            val since = if (since > 0) "&since=$since" else ""
            TeleApi.vodFacts(getObject("$baseUrl${TeleApi.VOD_FACTS_PATH}?kind=$kind$since"))
        }

    /** Whether the plugin knows the intro and credits of the titles ([getVodSegments]). */
    suspend fun hasVodSegments(): Boolean = baseUrl.isNotBlank() && "vod_segments" in teleApi()

    /** Intro and credits of a movie (no [season]) or an episode, from TheIntroDB through the plugin; null on error. */
    suspend fun getVodSegments(tmdbId: String, season: Int?, episode: Int?): io.github.sim7b.tele.data.vod.VodSegments? =
        withContext(Dispatchers.IO) {
            if (!hasVodSegments()) return@withContext null
            val url = "$baseUrl${TeleApi.VOD_SEGMENTS_PATH}".toHttpUrlOrNull()?.newBuilder() ?: return@withContext null
            url.addQueryParameter("tmdb_id", tmdbId)
            if (season != null && episode != null) {
                url.addQueryParameter("season", season.toString())
                url.addQueryParameter("episode", episode.toString())
            }
            getObject(url.build().toString(), quick = true)?.let { io.github.sim7b.tele.data.vod.VodSegments.parse(it.optJSONObject("segments")) }
        }

    /** TMDB ids of the titles that may also please, looked up by the plugin with its TMDB key; null without it. */
    suspend fun getVodRelated(kind: String, tmdbId: String): List<String>? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || "vod_related" !in teleApi()) return@withContext null
        getObject("$baseUrl${TeleApi.VOD_RELATED_PATH}?kind=$kind&tmdb_id=$tmdbId", quick = true)?.let { TeleApi.relatedIds(it) }
    }

    /** Whether the plugin serves the VOD catalog ready for Télé ([forEachCatalogItem]). */
    suspend fun hasVodCatalog(): Boolean = baseUrl.isNotBlank() && "vod_catalog" in teleApi()

    /**
     * The [kind] ("movie" / "series") titles of the catalog of the plugin, the ones changed after [since] only
     * when given (> 0), streamed one at a time to [action] (items of the VOD API with their "category"): the
     * time to ask the next changes from, null when unavailable (then nothing was given to [action]).
     */
    suspend fun forEachCatalogItem(kind: String, since: Double, action: (JSONObject) -> Unit): Double? = withContext(Dispatchers.IO) {
        if (!hasVodCatalog()) return@withContext null
        val url = "$baseUrl${TeleApi.VOD_CATALOG_PATH}?kind=$kind" + if (since > 0) "&since=$since" else ""
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code}: ${request.url.encodedPath}")
                    return@executeCancellable null
                }
                val until = response.header("X-Tele-Until")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0
                JsonStreams.forEachObject(response.body.byteStream(), action = action)
                until
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            readFailed(request, e)
            Log.w(TAG, "Request failed: ${request.url.encodedPath} (${e.javaClass.simpleName})")
            null
        }
    }

    /** Whether the plugin searches and downloads subtitles with an OpenSubtitles key of the server. */
    suspend fun hasServerSubtitles(): Boolean = baseUrl.isNotBlank() && "subtitles" in teleApi()

    /**
     * OpenSubtitles search through the plugin, with the parameters of [search] (an OpenSubtitles search URL):
     * its answer in the OpenSubtitles format. Errors of OpenSubtitles come back as [UserFacingException].
     */
    suspend fun searchSubtitles(search: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        val url = "$baseUrl${TeleApi.SUBTITLES_SEARCH_PATH}".toHttpUrlOrNull()?.newBuilder()
            ?: throw UserFacingException(UiText.Res(R.string.error_invalid_response))
        search.queryParameterNames.forEach { name -> search.queryParameter(name)?.let { url.addQueryParameter(name, it) } }
        val request = Request.Builder().url(url.build()).get().build()
        client.newCall(request).executeCancellable { response ->
            subtitleError(response)
            JSONObject(HttpClients.jsonString(response.body))
        }
    }

    /** A subtitle file downloaded by the plugin: (file, extension, downloads left today). */
    suspend fun downloadSubtitle(fileId: Long): Triple<ByteArray, String, Int?> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("file_id", fileId).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl${TeleApi.SUBTITLES_DOWNLOAD_PATH}").post(body).build()
        client.newCall(request).executeCancellable { response ->
            subtitleError(response)
            val source = response.body.source()
            if (source.request(MAX_SUBTITLE_BYTES + 1)) throw UserFacingException(UiText.Res(R.string.subtitles_error_download))
            val extension = response.header("X-Subtitle-Extension")?.takeIf { it in SUBTITLE_EXTENSIONS } ?: "srt"
            Triple(source.readByteArray(), extension, response.header("X-Subtitle-Remaining")?.toIntOrNull())
        }
    }

    private fun subtitleError(response: Response) {
        if (response.isSuccessful) return
        throw UserFacingException(
            when (response.code) {
                401, 403 -> UiText.Res(R.string.subtitles_error_key)
                429 -> UiText.Res(R.string.subtitles_error_quota)
                else -> UiText.Res(R.string.subtitles_error_server, response.code)
            }
        )
    }

    /** Movies and series most played on the server (Télé API plugin, adult ones left out); empty without it. */
    suspend fun getVodPopular(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || "vod_popular" !in teleApi()) return@withContext emptyList()
        TeleApi.popularTitles(getObject("$baseUrl${TeleApi.VOD_POPULAR_PATH}"))
    }

    /** VOD groups served to the TVs (Télé API plugin); null without the plugin or on error (then every group). */
    suspend fun getVodGroups(): TeleApi.VodGroups? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || "vod_groups" !in teleApi()) return@withContext null
        TeleApi.vodGroups(getObject("$baseUrl${TeleApi.VOD_GROUPS_PATH}"))
    }

    suspend fun getTmdbIds(kind: String): Map<String, String> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || "tmdb_ids" !in teleApi()) return@withContext emptyMap()
        TeleApi.tmdbIds(getObject("$baseUrl${TeleApi.TMDB_IDS_PATH}?kind=$kind"))
    }

    /** What the plugin knows of a file: measured ([info] in the Xtream "info" format), being measured, or not. */
    data class VodMedia(val pending: Boolean, val info: JSONObject?)

    /**
     * Technical description of a movie or an episode ("movie" / "episode", its uuid) measured with ffprobe by
     * the Télé API plugin; asking for an unknown one queues it on the server. Null without the plugin.
     */
    suspend fun getVodMedia(kind: String, uuid: String): VodMedia? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || "vod_media" !in teleApi()) return@withContext null
        val url = "$baseUrl${TeleApi.VOD_MEDIA_PATH}".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("kind", kind)?.addQueryParameter("uuid", uuid)?.build() ?: return@withContext null
        val answer = getObject(url.toString()) ?: return@withContext null
        when (answer.optString("status")) {
            "ok" -> VodMedia(pending = false, info = answer.optJSONObject("info"))
            "queued" -> VodMedia(pending = true, info = null)
            else -> VodMedia(pending = false, info = null)
        }
    }

    /**
     * First name and username of the account linked to the API key (null if unavailable).
     */
    suspend fun getCurrentUser(): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        val request = Request.Builder().url("$baseUrl/api/accounts/users/me/").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val user = JSONObject(HttpClients.jsonString(response.body))
                user.optStringOrNull("first_name") to user.optStringOrNull("username")
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_SUBTITLE_BYTES = 4L * 1024 * 1024
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml")
        private const val TAG = "Dispatcharr"
        private const val QUICK_CALL_S = 6L
        const val DEFAULT_USER_AGENT = HttpClients.DEFAULT_USER_AGENT
        private const val ARTWORK_PAGE_SIZE = 500
        private const val MAX_ARTWORK_PAGES = 6
        private const val VOD_PAGE_SIZE = 100
        private const val VOD_PARALLEL_PAGES = 8
    }
}
