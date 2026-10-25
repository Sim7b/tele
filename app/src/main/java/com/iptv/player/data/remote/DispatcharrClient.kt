package com.iptv.player.data.remote

import com.iptv.player.data.epg.EpgResolver
import com.iptv.player.data.model.*
import com.iptv.player.data.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DispatcharrClient(
    @Volatile private var currentConfig: SourceConfig? = null
) {
    // Dérivé du client partagé : même pool de connexions que le lecteur et les images
    private val client = HttpClients.base.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            val userAgent = currentConfig?.customUserAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT

            val requestBuilder = original.newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")

            currentConfig?.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                requestBuilder.header("X-API-Key", key)
            }

            chain.proceed(requestBuilder.build())
        }
        .build()

    private val testClient = client.newBuilder()
        .apply { interceptors().clear() }
        .build()

    private val groupsCache = ConcurrentHashMap<Int, String>()
    private val epgDataCache = ConcurrentHashMap<Int, DispatcharrEpgData>()
    private val accountsCache = ConcurrentHashMap<Int, Pair<String, Boolean>>()

    fun setConfig(config: SourceConfig?) {
        if (config != currentConfig) {
            groupsCache.clear()
            epgDataCache.clear()
            accountsCache.clear()
        }
        this.currentConfig = config
    }

    private val baseUrl: String
        get() = currentConfig?.normalizedUrl ?: ""

    /**
     * Teste la connexion à un serveur Dispatcharr.
     */
    suspend fun testConnection(config: SourceConfig): Result<Int> = withContext(Dispatchers.IO) {
        val testUrl = "${config.normalizedUrl}/api/channels/channels/"
        val requestBuilder = Request.Builder()
            .url(testUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", config.customUserAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT)

        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("X-API-Key", config.apiKey)
        }

        try {
            // Client sans intercepteur : la clé du serveur actif ne doit pas se substituer à celle testée
            testClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val msg = when (code) {
                        401, 403 -> "Clé API non valide ou non autorisée (HTTP $code)"
                        404 -> "Serveur introuvable ou chemin API incorrect (HTTP 404)"
                        else -> "Erreur serveur : HTTP $code"
                    }
                    return@withContext Result.failure(IOException(msg))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Réponse vide"))
                val count = try {
                    extractArray(body).length()
                } catch (e: Exception) {
                    return@withContext Result.failure(IOException("Réponse inattendue : ce serveur n'est pas une API Dispatcharr"))
                }

                Result.success(count)
            }
        } catch (e: Exception) {
            val friendlyMsg = when (e) {
                is java.net.UnknownHostException -> "Nom de domaine introuvable : vérifiez l'adresse saisie."
                is java.net.ConnectException -> "Connexion refusée : vérifiez que le serveur et le port sont ouverts."
                is java.net.SocketTimeoutException -> "Délai d'attente dépassé (Timeout) : le serveur ne répond pas."
                is IllegalArgumentException -> "Adresse du serveur invalide."
                else -> e.localizedMessage ?: "Erreur réseau inconnue"
            }
            Result.failure(IOException(friendlyMsg, e))
        }
    }

    /**
     * Accepte un tableau JSON brut ou un objet paginé ("results" / "data").
     */
    private fun extractArray(body: String): JSONArray {
        return if (body.trimStart().startsWith("[")) {
            JSONArray(body)
        } else {
            val root = JSONObject(body)
            root.optJSONArray("data") ?: root.optJSONArray("results") ?: JSONArray()
        }
    }

    private fun fetchArray(url: String): JSONArray? {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                extractArray(body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun fetchGroupsMap(): Map<Int, String> = withContext(Dispatchers.IO) {
        if (groupsCache.isNotEmpty()) return@withContext groupsCache
        if (baseUrl.isBlank()) return@withContext emptyMap()

        val array = fetchArray("$baseUrl/api/channels/groups/") ?: return@withContext groupsCache
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            val name = item.optStringOrNull("name")
            if (id != -1 && name != null) {
                groupsCache[id] = name
            }
        }
        groupsCache
    }

    /**
     * Table epg_data_id -> tvg_id : les chaînes Dispatcharr référencent leur guide via epg_data_id,
     * alors que la grille EPG est indexée par le tvg_id de l'EPGData.
     */
    private suspend fun fetchEpgDataMap(): Map<Int, DispatcharrEpgData> = withContext(Dispatchers.IO) {
        if (epgDataCache.isNotEmpty()) return@withContext epgDataCache
        if (baseUrl.isBlank()) return@withContext emptyMap()

        val array = fetchArray("$baseUrl/api/epg/epgdata/") ?: return@withContext epgDataCache
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            val tvgId = item.optStringOrNull("tvg_id") ?: continue
            if (id != -1) {
                epgDataCache[id] = DispatcharrEpgData(id, tvgId, item.optStringOrNull("name") ?: tvgId)
            }
        }
        epgDataCache
    }

    /** Liste EPGData (pour la correspondance par nom des chaînes sans epg_data_id). */
    suspend fun getEpgData(): List<DispatcharrEpgData> = fetchEpgDataMap().values.toList()

    suspend fun getChannels(): Result<List<DispatcharrChannel>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IOException("Aucun serveur Dispatcharr configuré"))
        }

        try {
            coroutineScope {
                val groupsDeferred = async { fetchGroupsMap() }
                val epgDataDeferred = async { fetchEpgDataMap() }

                val request = Request.Builder()
                    .url("$baseUrl/api/channels/channels/")
                    .get()
                    .build()

                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            when (response.code) {
                                401, 403 -> "Clé API refusée par le serveur (HTTP ${response.code})"
                                else -> "Erreur HTTP ${response.code} : ${response.message}"
                            }
                        )
                    }
                    response.body?.string() ?: throw IOException("Corps de réponse vide")
                }

                val groupsMap = groupsDeferred.await()
                val epgDataMap = epgDataDeferred.await()
                val channelsArray = extractArray(body)
                val channelList = mutableListOf<DispatcharrChannel>()

                for (i in 0 until channelsArray.length()) {
                    val item = channelsArray.optJSONObject(i) ?: continue
                    val uuid = item.optStringOrNull("uuid") ?: item.optStringOrNull("id") ?: continue
                    val name = item.optStringOrNull("effective_name") ?: item.optStringOrNull("name") ?: "Chaîne sans nom"

                    val groupId = item.optInt("effective_channel_group_id", item.optInt("channel_group_id", -1))
                    val group = groupsMap[groupId] ?: "Général"

                    val logoId = item.optInt("effective_logo_id", item.optInt("logo_id", -1))
                    val logoUrl = if (logoId > 0) {
                        "$baseUrl/api/channels/logos/$logoId/cache/"
                    } else {
                        item.optStringOrNull("logo_url")
                    }

                    val epgDataId = item.optInt("effective_epg_data_id", item.optInt("epg_data_id", -1))
                    val tvgId = epgDataMap[epgDataId]?.tvgId
                        ?: item.optStringOrNull("effective_tvg_id")
                        ?: item.optStringOrNull("tvg_id")
                        ?: ""
                    val streamUrl = "$baseUrl/proxy/ts/stream/$uuid"
                    val channelNumber = item.optDouble("effective_channel_number", item.optDouble("channel_number", 0.0))
                        .takeIf { !it.isNaN() }?.toInt()?.takeIf { it > 0 }
                    val isRadio = item.optBoolean("effective_is_radio", item.optBoolean("is_radio", false))

                    channelList.add(
                        DispatcharrChannel(
                            id = item.optStringOrNull("id") ?: uuid,
                            uuid = uuid,
                            channelNumber = channelNumber,
                            name = name,
                            group = group,
                            logoUrl = logoUrl,
                            streamUrl = streamUrl,
                            tvgId = tvgId,
                            isRadio = isRadio
                        )
                    )
                }

                channelList.sortBy { it.channelNumber ?: Int.MAX_VALUE }

                Result.success(channelList)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Récupère la grille EPG complète optimisée (Supporte le tableau plat de programmes ou les canaux imbriqués).
     * Les programmes sont indexés par tvg_id (ou uuid de chaîne pour les programmes "dummy").
     */
    suspend fun getEpgGrid(
        days: Int = 1,
        channelProfileId: String? = null
    ): Result<List<DispatcharrEpgChannel>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IOException("Aucun serveur Dispatcharr configuré"))
        }

        val urlBuilder = "$baseUrl/api/epg/grid/".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext Result.failure(IOException("URL invalide"))

        urlBuilder.addQueryParameter("days", days.toString())
        channelProfileId?.let { urlBuilder.addQueryParameter("channel_profile_id", it) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Erreur HTTP ${response.code}"))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Réponse vide"))
                val epgMap = mutableMapOf<String, MutableList<GuideProgram>>()
                val dataArray = extractArray(body)

                for (i in 0 until dataArray.length()) {
                    val item = dataArray.optJSONObject(i) ?: continue

                    // CAS 1 : Objet Canal avec tableau "programs" imbriqué
                    if (item.has("programs")) {
                        val channelKey = item.optStringOrNull("tvg_id")
                            ?: item.optStringOrNull("channel_uuid")
                            ?: item.optStringOrNull("uuid")
                            ?: continue
                        val programsArray = item.optJSONArray("programs") ?: JSONArray()
                        val progList = epgMap.getOrPut(channelKey) { mutableListOf() }

                        for (j in 0 until programsArray.length()) {
                            val progObj = programsArray.optJSONObject(j) ?: continue
                            parseProgram(progObj, channelKey, "$channelKey-$j")?.let { progList.add(it) }
                        }
                    }
                    // CAS 2 : Tableau plat de programmes individuels (format de /api/epg/grid/)
                    else if (item.has("title")) {
                        val key = item.optStringOrNull("tvg_id")
                            ?: item.optStringOrNull("channel_uuid")
                            ?: item.optStringOrNull("channel_id")
                            ?: continue
                        parseProgram(item, key, "$key-$i")?.let { epgMap.getOrPut(key) { mutableListOf() }.add(it) }
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
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseProgram(obj: JSONObject, channelKey: String, fallbackId: String): GuideProgram? {
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
            title = obj.optStringOrNull("title") ?: "Programme",
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
     * Visuels et catégories des programmes diffusés entre [fromMillis] et [toMillis], indexés par id de programme.
     * La grille EPG ne les contient pas ; la recherche de programmes les expose dans custom_properties.
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

            val results = fetchArray(url.toString()) ?: break
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optStringOrNull("id") ?: continue
                val props = item.optJSONObject("custom_properties") ?: continue
                val image = props.optStringOrNull("icon")
                val category = props.optJSONArray("categories")?.let { array ->
                    (0 until array.length()).firstNotNullOfOrNull { array.optString(it).takeIf { c -> c.isNotBlank() } }
                }
                if (image != null || category != null) {
                    artwork[id] = ProgramArtwork(imageUrl = image, category = category)
                }
            }
            if (results.length() < ARTWORK_PAGE_SIZE) break
        }
        artwork
    }

    private fun isoInstant(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    /** Comptes M3U (fournisseurs) : nom affiché et état actif, mis en cache. */
    private suspend fun fetchAccounts(): Map<Int, Pair<String, Boolean>> = withContext(Dispatchers.IO) {
        if (accountsCache.isNotEmpty()) return@withContext accountsCache
        if (baseUrl.isBlank()) return@withContext emptyMap()

        val array = fetchArray("$baseUrl/api/m3u/accounts/") ?: return@withContext accountsCache
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            if (id != -1) {
                accountsCache[id] = (item.optStringOrNull("name") ?: "Source $id") to item.optBoolean("is_active", true)
            }
        }
        accountsCache
    }

    /**
     * Flux disponibles pour une chaîne, dans l'ordre de priorité Dispatcharr.
     * Le flux est lu à son URL d'origine, comme le fait Dispatcharr en profil "Redirect".
     */
    suspend fun getChannelStreams(channelServerId: String): Result<List<StreamSource>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IOException("Aucun serveur Dispatcharr configuré"))
        }

        try {
            coroutineScope {
                val accountsDeferred = async { fetchAccounts() }
                val array = fetchArray("$baseUrl/api/channels/channels/$channelServerId/streams/")
                    ?: throw IOException("Impossible de récupérer les flux de la chaîne")
                val accounts = accountsDeferred.await()

                val streams = mutableListOf<StreamSource>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optBoolean("is_stale", false)) continue

                    val accountId = item.optInt("m3u_account", -1)
                    val account = accounts[accountId]
                    if (account != null && !account.second) continue

                    val url = item.optStringOrNull("url")
                        ?: item.optStringOrNull("stream_hash")?.let { "$baseUrl/proxy/ts/stream/$it" }
                        ?: continue
                    val rawName = item.optStringOrNull("name") ?: "Flux ${i + 1}"

                    streams.add(
                        StreamSource(
                            name = StreamNames.clean(rawName),
                            url = url,
                            id = item.optStringOrNull("id"),
                            provider = account?.first,
                            qualityTags = StreamNames.qualityTags(rawName),
                            details = item.optJSONObject("stream_stats")?.let { StreamNames.describeStats(it) }
                        )
                    )
                }
                Result.success(streams)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Prénom et identifiant du compte associé à la clé API (null si indisponible).
     */
    suspend fun getCurrentUser(): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        val request = Request.Builder().url("$baseUrl/api/accounts/users/me/").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val user = JSONObject(response.body?.string() ?: return@withContext null)
                user.optStringOrNull("first_name") to user.optStringOrNull("username")
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = HttpClients.DEFAULT_USER_AGENT
        private const val ARTWORK_PAGE_SIZE = 500
        private const val MAX_ARTWORK_PAGES = 6
    }
}
