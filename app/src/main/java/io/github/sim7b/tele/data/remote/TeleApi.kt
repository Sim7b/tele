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
object TeleApi {
    const val API_VERSION = 1
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
    private const val MAX_POPULAR = 100

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
