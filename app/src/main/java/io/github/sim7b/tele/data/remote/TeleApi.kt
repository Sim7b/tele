package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.DispatcharrChannel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.model.StreamSlots
import io.github.sim7b.tele.data.optStringOrNull
import org.json.JSONObject

/**
 * Responses of the Télé API plugin of Dispatcharr (`/api/tele/v1/`): endpoints open to standard users that
 * replace several calls of the standard API (channels with their group and guide key in one list) or need
 * an admin key there (free provider connections). Pure parsing, every value of the server is untrusted.
 */
/**
 * What Dispatcharr is doing with a channel a TV is starting: its [state] ("initializing", "connecting",
 * "waiting_for_clients", "active", "buffering"...; null when it is not running), for how long, the [stream] it plays
 * (a change: the previous one failed) and the [chunks] received from the provider.
 */
data class ChannelStartState(val state: String?, val sinceS: Double?, val stream: Long?, val chunks: Int)

object TeleApi {
    const val API_VERSION = 1
    const val ROOT_PATH = "/api/tele/"
    const val INFO_PATH = "/api/tele/v1/info/"
    const val CHANNELS_PATH = "/api/tele/v1/channels/"
    const val SLOTS_PATH = "/api/tele/v1/slots/"
    const val VOD_LANGUAGES_PATH = "/api/tele/v1/vod/languages/"
    const val VOD_MEDIA_PATH = "/api/tele/v1/vod/media/"
    const val STREAM_HEALTH_PATH = "/api/tele/v1/streams/health/"
    const val GUIDE_PATH = "/api/tele/v1/guide/"
    const val SYNC_PATH = "/api/tele/v1/sync/"
    const val TMDB_IDS_PATH = "/api/tele/v1/vod/tmdb-ids/"
    const val VOD_GROUPS_PATH = "/api/tele/v1/vod/groups/"
    const val VOD_POPULAR_PATH = "/api/tele/v1/vod/popular/"
    const val SUBTITLES_SEARCH_PATH = "/api/tele/v1/subtitles/search/"
    const val VOD_FACTS_PATH = "/api/tele/v1/vod/facts/"
    const val VOD_CATALOG_PATH = "/api/tele/v1/vod/catalog/"
    const val VOD_POSTER_PATH = "/api/tele/v1/vod/poster/"
    const val VOD_SEGMENTS_PATH = "/api/tele/v1/vod/segments/"
    const val VOD_RELATED_PATH = "/api/tele/v1/vod/related/"
    const val CHANNEL_STATE_PATH = "/api/tele/v1/channels/state/"
    const val VOD_HOLD_PATH = "/api/tele/v1/vod/hold/"
    const val VOD_FRESH_PATH = "/api/tele/v1/vod/fresh/"
    const val DEVICES_NOW_PATH = "/api/tele/v1/devices/now/"
    const val DEVICES_SEND_PATH = "/api/tele/v1/devices/send/"

    /** TMDB ids of an answer of [VOD_RELATED_PATH] (or a TMDB list), in their order; others are skipped. */
    fun relatedIds(answer: JSONObject?): List<String> {
        val ids = answer?.optJSONArray("ids") ?: return emptyList()
        return (0 until minOf(ids.length(), 60)).mapNotNull { ids.optString(it).takeIf { id -> DIGITS.matches(id) } }.distinct()
    }
    // Width of the light posters: a card of the TV, with room for the large poster size
    const val POSTER_WIDTH = 342
    private const val MAX_FACTS = 200_000

    /**
     * TMDB facts looked up by the server, by TMDB id, and the time to ask the changes from ("until"); malformed
     * entries are skipped.
     */
    fun vodFacts(answer: JSONObject?): Pair<Map<String, io.github.sim7b.tele.data.vod.TmdbFacts>, Double>? {
        val facts = answer?.optJSONObject("facts") ?: return null
        val result = HashMap<String, io.github.sim7b.tele.data.vod.TmdbFacts>()
        val keys = facts.keys()
        while (keys.hasNext() && result.size < MAX_FACTS) {
            val id = keys.next()
            val item = facts.optJSONObject(id) ?: continue
            if (!DIGITS.matches(id)) continue
            val countries = item.optJSONArray("c")?.let { array ->
                (0 until minOf(array.length(), 10)).mapNotNull { array.optString(it).takeIf { c -> c.length == 2 }?.uppercase() }
            }.orEmpty()
            val studios = item.optJSONArray("s")?.let { array ->
                (0 until minOf(array.length(), 6)).mapNotNull { index ->
                    val pair = array.optJSONArray(index) ?: return@mapNotNull null
                    val studioId = pair.optString(0).takeIf { DIGITS.matches(it) } ?: return@mapNotNull null
                    io.github.sim7b.tele.data.vod.Studio(studioId, pair.optString(1).take(100))
                }
            }.orEmpty()
            result[id] = io.github.sim7b.tele.data.vod.TmdbFacts(
                tmdbId = id,
                countries = countries,
                studios = studios,
                popularity = item.optDouble("p", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                vote = item.optDouble("v", 0.0).takeIf { it.isFinite() && it in 0.0..10.0 } ?: 0.0,
                votes = item.optInt("n", 0).coerceAtLeast(0),
                language = item.optStringOrNull("l")?.takeIf { LANGUAGE_CODE.matches(it) }
            )
        }
        return result to answer.optDouble("until", 0.0).takeIf { it.isFinite() && it >= 0 }.let { it ?: 0.0 }
    }
    const val SUBTITLES_DOWNLOAD_PATH = "/api/tele/v1/subtitles/download/"
    private const val MAX_POPULAR = 100

    /** The latest episode found of a followed series: when, and its season and episode numbers. */
    data class NewEpisode(val atMs: Long, val season: Int?, val episode: Int?)

    /**
     * What is new in the VOD of the server, from [VOD_FRESH_PATH]: the titles added lately, newest first
     * (("movie" | "series", Dispatcharr id)), and the latest episode of the series asked for, by Dispatcharr id.
     */
    data class VodFresh(val added: List<Pair<String, String>>, val episodes: Map<String, NewEpisode>)

    fun vodFresh(answer: JSONObject?): VodFresh? {
        answer ?: return null
        val list = answer.optJSONArray("added")
        val added = (0 until minOf(list?.length() ?: 0, MAX_POPULAR)).mapNotNull { i ->
            val item = list?.optJSONObject(i) ?: return@mapNotNull null
            val kind = item.optString("kind").takeIf { it == "movie" || it == "series" } ?: return@mapNotNull null
            val id = item.opt("id")?.toString()?.takeIf { DIGITS.matches(it) } ?: return@mapNotNull null
            kind to id
        }.distinct()
        val found = answer.optJSONObject("episodes")
        val episodes = HashMap<String, NewEpisode>()
        found?.keys()?.asSequence()?.take(MAX_POPULAR)?.forEach { id ->
            if (!DIGITS.matches(id)) return@forEach
            val item = found.optJSONObject(id) ?: return@forEach
            val at = item.optDouble("at", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: return@forEach
            episodes[id] = NewEpisode(
                (at * 1000).toLong(),
                item.optInt("season", 0).takeIf { it > 0 },
                item.optInt("episode", 0).takeIf { it > 0 }
            )
        }
        return VodFresh(added, episodes)
    }

    /** Movies and series most played on the server, most played first: ("movie" | "series", Dispatcharr id). */
    fun popularTitles(answer: JSONObject?): List<Pair<String, String>> {
        val list = answer?.optJSONArray("titles") ?: return emptyList()
        return (0 until minOf(list.length(), MAX_POPULAR)).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val kind = item.optString("kind").takeIf { it == "movie" || it == "series" } ?: return@mapNotNull null
            val id = item.opt("id")?.toString()?.takeIf { DIGITS.matches(it) } ?: return@mapNotNull null
            kind to id
        }.distinct()
    }
    private const val MAX_GROUPS = 50_000

    /** VOD groups the Dispatcharr admin chose in the plugin: [all], or the [groups] "name|movie" / "name|series". */
    data class VodGroups(val all: Boolean, val groups: Set<String> = emptySet())

    /** Groups of an answer of [VOD_GROUPS_PATH]; null when malformed (then nothing is hidden). */
    fun vodGroups(answer: JSONObject?): VodGroups? {
        if (answer == null) return null
        return when (answer.optString("mode")) {
            "all" -> VodGroups(all = true)
            "selected" -> {
                val list = answer.optJSONArray("groups") ?: return null
                val groups = HashSet<String>()
                for (i in 0 until minOf(list.length(), MAX_GROUPS)) {
                    val item = list.optJSONObject(i) ?: continue
                    val name = item.optStringOrNull("name") ?: continue
                    val type = item.optString("type").takeIf { it == "movie" || it == "series" } ?: continue
                    groups.add("$name|$type")
                }
                VodGroups(all = false, groups = groups)
            }
            else -> null
        }
    }
    private val DIGITS = Regex("^[0-9]{1,12}$")

    /** Dispatcharr id -> TMDB id of an answer of [TMDB_IDS_PATH]; ids that are not numbers are skipped. */
    fun tmdbIds(answer: JSONObject?): Map<String, String> {
        val ids = answer?.optJSONObject("ids") ?: return emptyMap()
        val result = HashMap<String, String>()
        ids.keys().forEach { key ->
            val value = ids.optString(key)
            if (DIGITS.matches(key) && DIGITS.matches(value) && value != "0") result[key] = value
        }
        return result
    }
    const val MAX_HEALTH_IDS = 100
    const val RELEASE_PATH = "/api/tele/v1/sessions/release/"
    const val CHANNELS_PAGE_SIZE = 2000

    // Pages read at most: 2000 channels each, far more than any server
    const val MAX_CHANNEL_PAGES = 100

    /** Features of the installed plugin; null when [info] is not an answer of a compatible plugin. */
    fun features(info: JSONObject?): Set<String>? {
        if (info == null || info.optInt("api", -1) != API_VERSION) return null
        val list = info.optJSONArray("features") ?: return null
        return (0 until minOf(list.length(), 64)).mapNotNull { list.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    /** A channel of the plugin, like [DispatcharrClient] builds it from the standard API; null when malformed. */
    fun parseChannel(item: JSONObject, baseUrl: String): DispatcharrChannel? {
        val uuid = item.optStringOrNull("uuid") ?: return null
        val id = item.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: uuid
        val number = item.optDouble("number", Double.NaN).takeIf { !it.isNaN() }?.toInt()?.takeIf { it > 0 }
        val logoId = item.optInt("logo_id", -1)
        val group = item.optJSONObject("group")?.optStringOrNull("name")
        return DispatcharrChannel(
            id = id,
            uuid = uuid,
            channelNumber = number,
            name = item.optStringOrNull("name") ?: "#${number ?: uuid.take(8)}",
            group = group ?: Channel.NO_GROUP,
            logoUrl = if (logoId > 0) "$baseUrl/api/channels/logos/$logoId/cache/" else null,
            streamUrl = "$baseUrl/proxy/ts/stream/$uuid",
            tvgId = item.optStringOrNull("tvg_id") ?: "",
            isRadio = item.optBoolean("is_radio", false),
            programs = listOfNotNull(program(item.optJSONObject("now"), uuid), program(item.optJSONObject("next"), uuid)),
            catchupDays = item.optInt("catchup_days", 0).coerceIn(0, io.github.sim7b.tele.data.epg.Catchup.MAX_DAYS)
        )
    }

    /** A current or next program of the plugin ("title", "start", "end" in ISO 8601); null when malformed. */
    internal fun program(item: JSONObject?, channelUuid: String): GuideProgram? {
        if (item == null) return null
        val title = item.optStringOrNull("title") ?: return null
        val start = item.optStringOrNull("start") ?: return null
        val end = item.optStringOrNull("end") ?: return null
        val startMillis = isoMillis(start) ?: return null
        val endMillis = isoMillis(end)?.takeIf { it > startMillis } ?: return null
        return GuideProgram(
            id = "tele:$channelUuid:$startMillis",
            channelUuid = channelUuid,
            title = title.take(MAX_TITLE),
            description = item.optStringOrNull("sub_title")?.take(MAX_TITLE),
            startTimeIso = start,
            endTimeIso = end,
            startMillis = startMillis,
            endMillis = endMillis
        )
    }

    private fun isoMillis(text: String): Long? = try {
        java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }

    private const val MAX_TITLE = 512

    /** What Dispatcharr is doing with a channel being started, from the answer of [CHANNEL_STATE_PATH]. */
    fun channelState(answer: JSONObject?): ChannelStartState? {
        answer ?: return null
        val state = answer.optStringOrNull("state")?.takeIf { it.length <= 32 }
        return ChannelStartState(
            state = state,
            sinceS = answer.optDouble("since", Double.NaN).takeIf { it.isFinite() && it >= 0 },
            stream = answer.optLong("stream", 0L).takeIf { it > 0 },
            chunks = answer.optInt("chunks", 0).coerceAtLeast(0)
        )
    }

    /** Ids of the streams that failed lately, from the answer of [STREAM_HEALTH_PATH]. */
    fun unhealthyStreams(answer: JSONObject?): Set<String> {
        val streams = answer?.optJSONObject("streams") ?: return emptySet()
        return streams.keys().asSequence().take(MAX_HEALTH_IDS)
            .filter { streams.optJSONObject(it)?.optBoolean("unhealthy", false) == true }
            .toSet()
    }

    /** Body asking to close every session of this TV (same address and User-Agent as the request). */
    fun releaseThisDeviceBody(): String = JSONObject().put("this_device", true).toString()

    /** Number of sessions closed, from the answer of [RELEASE_PATH]; 0 when malformed. */
    fun releasedCount(answer: JSONObject?): Int = answer?.optJSONArray("released")?.length() ?: 0

    private val LANGUAGE_CODE = Regex("^[a-z]{2,3}$")

    /**
     * Audio and subtitle languages measured by the plugin (ffprobe on one file of each category), by
     * "movie:<category name>" / "series:<category name>". Malformed entries are skipped.
     */
    fun parseVodLanguages(answer: JSONObject?): Map<String, Pair<Set<String>, Set<String>>> {
        val list = answer?.optJSONArray("categories") ?: return emptyMap()
        val measured = HashMap<String, Pair<Set<String>, Set<String>>>()
        for (i in 0 until minOf(list.length(), MAX_MEASURED_CATEGORIES)) {
            val item = list.optJSONObject(i) ?: continue
            val name = item.optStringOrNull("name") ?: continue
            val kind = if (item.optString("type") == "series") "series" else "movie"
            measured["$kind:$name"] = codes(item.optJSONArray("audio")) to codes(item.optJSONArray("subtitles"))
        }
        return measured
    }

    private fun codes(array: org.json.JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return (0 until minOf(array.length(), 16)).mapNotNull { array.optString(it).takeIf(LANGUAGE_CODE::matches) }.toSet()
    }

    private const val MAX_MEASURED_CATEGORIES = 20_000

    /** Message of the Dispatcharr admin for the TVs (plugin setting), null when there is none. */
    fun message(info: JSONObject?): String? =
        info?.optStringOrNull("message")?.trim()?.take(MAX_MESSAGE)?.takeIf { it.isNotEmpty() }

    private const val MAX_MESSAGE = 500

    /** Free provider connections; null when no account has a limit or the answer is malformed. */
    fun parseSlots(slots: JSONObject?): StreamSlots? {
        if (slots == null || !slots.optBoolean("limited", false)) return null
        val total = slots.optInt("total", 0)
        if (total <= 0) return null
        return StreamSlots(used = slots.optInt("used", 0).coerceIn(0, total), total = total)
    }
}
