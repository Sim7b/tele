package io.github.sim7b.tele.data.remote

import android.util.Log
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
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
            val userAgent = config?.customUserAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT

            val requestBuilder = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
            config?.let { ApiKeyAuth.of(it) }?.let { requestBuilder.tag(ApiKeyAuth::class.java, it) }

            chain.proceed(requestBuilder.build())
        }
        .addNetworkInterceptor(apiKeyInterceptor)
        .build()

    // Without the application interceptor: the key of the active server must not replace the tested one
    private val testClient = HttpClients.base.newBuilder()
        .addNetworkInterceptor(apiKeyInterceptor)
        .build()

    // Reference tables, cached once fetched successfully (immutable maps replaced as a whole)
    @Volatile private var groupsCache: Map<Int, String>? = null
    @Volatile private var epgDataCache: Map<Int, DispatcharrEpgData>? = null
    @Volatile private var accountsCache: Map<Int, Pair<String, Boolean>>? = null

    fun setConfig(config: SourceConfig?) {
        if (config != currentConfig) clearCaches()
        this.currentConfig = config
    }

    /** Forgets the groups, EPG data and accounts (manual refresh: they may have changed on the server). */
    fun clearCaches() {
        groupsCache = null
        epgDataCache = null
        accountsCache = null
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
            .header("User-Agent", config.customUserAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT)

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
                if (!response.isSuccessful) return@executeCancellable false
                JsonStreams.forEachObject(response.body.byteStream(), action = action)
                true
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
            isRadio = item.optBoolean("effective_is_radio", item.optBoolean("is_radio", false))
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
        channelProfileId: String? = null
    ): Result<List<DispatcharrEpgChannel>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext failure(R.string.error_dispatcharr_not_configured)
        }

        val urlBuilder = "$baseUrl/api/epg/grid/".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext failure(R.string.error_invalid_address)

        urlBuilder.addQueryParameter("days", days.toString())
        channelProfileId?.let { urlBuilder.addQueryParameter("channel_profile_id", it) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        try {
            val epgMap = mutableMapOf<String, MutableList<GuideProgram>>()
            client.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) throw UserFacingException(UiText.Res(R.string.error_server_http, response.code))
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
            }

            val resultList = epgMap.map { (key, progs) ->
                progs.sortBy { it.startMillis }
                DispatcharrEpgChannel(
                    channelUuid = key,
                    channelName = key,
                    programs = progs
                )
            }

            Result.success(resultList)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
            description = obj.optStringOrNull("description"),
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
                Result.success(streams)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Proxy URL of a single Dispatcharr stream. */
    fun proxyStreamUrl(streamHash: String): String = "$baseUrl/proxy/ts/stream/$streamHash?skip_redirect=true"

    /** True when [url] is played through this Dispatcharr server (and not straight from a provider). */
    fun isProxyUrl(url: String): Boolean = baseUrl.isNotBlank() && url.startsWith("$baseUrl/proxy/")

    /**
     * Playback slots of the provider accounts (free / total), read live: never cached, since
     * [StreamSlots.used] changes with every viewer. Null when unavailable (no admin rights, error)
     * or when no account has a stream limit.
     */
    suspend fun getStreamSlots(): StreamSlots? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        val accounts = mutableListOf<JSONObject>()
        if (!fetchObjects("$baseUrl/api/m3u/accounts/") { accounts.add(it) }) return@withContext null
        StreamSlots.fromAccounts(accounts)
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
        private const val TAG = "Dispatcharr"
        const val DEFAULT_USER_AGENT = HttpClients.DEFAULT_USER_AGENT
        private const val ARTWORK_PAGE_SIZE = 500
        private const val MAX_ARTWORK_PAGES = 6
    }
}
