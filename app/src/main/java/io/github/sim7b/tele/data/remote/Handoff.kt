package io.github.sim7b.tele.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a TV plays, for another TV of the user to resume it: a channel ([LIVE], [key]: its id) or a movie or episode
 * ([VOD], [key]: its progress key, "<title key>#S1E3" for an episode) at [positionMs].
 */
data class HandoffItem(val kind: String, val key: String, val title: String, val positionMs: Long = 0, val durationMs: Long = 0) {
    /** The title of a movie or episode: its key without the episode. */
    val titleKey: String get() = key.substringBefore('#')

    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("key", key).put("title", title)
        .put("position_ms", positionMs).put("duration_ms", durationMs)

    companion object {
        const val LIVE = "live"
        const val VOD = "vod"

        fun fromJson(json: JSONObject?): HandoffItem? {
            json ?: return null
            val kind = json.optString("kind").takeIf { it == LIVE || it == VOD } ?: return null
            val key = json.optString("key").takeIf { it.isNotBlank() && it.length <= 300 } ?: return null
            return HandoffItem(
                kind, key, json.optString("title").take(200),
                json.optLong("position_ms", 0).coerceAtLeast(0), json.optLong("duration_ms", 0).coerceAtLeast(0)
            )
        }
    }
}

/** Another TV of the user: its install id, and the name it gives itself ("Salon"). */
data class HandoffTv(val device: String, val name: String?)

/**
 * What the other TVs of the user play now ([playing]), those TVs ([tvs], to send to one of them), and what one of
 * them sent here ([incoming], read once).
 */
data class HandoffState(
    val playing: List<Pair<HandoffTv, HandoffItem>>,
    val tvs: List<HandoffTv>,
    val incoming: Pair<HandoffTv, HandoffItem>?
) {
    companion object {
        private val DEVICE = Regex("^[0-9a-f]{12}$")

        fun fromJson(json: JSONObject?): HandoffState? {
            json ?: return null
            fun tv(item: JSONObject): HandoffTv? =
                item.optString("device").takeIf { DEVICE.matches(it) }?.let { HandoffTv(it, item.optString("name").takeIf { n -> n.isNotBlank() }?.take(60)) }
            fun list(array: JSONArray?): List<JSONObject> = (0 until minOf(array?.length() ?: 0, 20)).mapNotNull { array?.optJSONObject(it) }
            return HandoffState(
                playing = list(json.optJSONArray("playing")).mapNotNull { item -> tv(item)?.let { t -> HandoffItem.fromJson(item)?.let { t to it } } },
                tvs = list(json.optJSONArray("tvs")).mapNotNull(::tv),
                incoming = json.optJSONObject("incoming")?.let { item -> tv(item)?.let { t -> HandoffItem.fromJson(item)?.let { t to it } } }
            )
        }
    }
}
