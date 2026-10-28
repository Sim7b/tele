package io.github.sim7b.tele.data.vod

import io.github.sim7b.tele.data.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class PosterSize(val widthDp: Int) { SMALL(96), NORMAL(112), LARGE(140) }

/**
 * How the movies and series module looks: rows of the home, poster size, theater recordings, and the
 * filters the Movies and Series tabs open with.
 */
data class VodDisplay(
    val hiddenRows: Set<VodRow.Kind> = emptySet(),
    val posterSize: PosterSize = PosterSize.NORMAL,
    val hideTheater: Boolean = false,
    val defaults: Map<VodKind, VodFilter> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hiddenRows", JSONArray(hiddenRows.map { it.name }))
        put("posterSize", posterSize.name)
        put("hideTheater", hideTheater)
        put("defaults", JSONObject().apply {
            defaults.forEach { (kind, filter) ->
                put(kind.name, JSONObject().put("country", filter.country).put("sort", filter.sort.name))
            }
        })
    }

    companion object {
        /** Settings saved by [toJson]; unknown values are ignored. */
        fun fromJson(json: JSONObject?): VodDisplay {
            if (json == null) return VodDisplay()
            val hidden = json.optJSONArray("hiddenRows")?.let { array ->
                (0 until array.length()).mapNotNull { index -> VodRow.Kind.entries.firstOrNull { it.name == array.optString(index) } }
            }.orEmpty().toSet()
            val defaults = json.optJSONObject("defaults")?.let { obj ->
                VodKind.entries.mapNotNull { kind ->
                    val filter = obj.optJSONObject(kind.name) ?: return@mapNotNull null
                    kind to VodFilter(
                        country = filter.optStringOrNull("country"),
                        sort = VodSort.entries.firstOrNull { it.name == filter.optString("sort") } ?: VodSort.POPULARITY
                    )
                }.toMap()
            }.orEmpty()
            return VodDisplay(
                hiddenRows = hidden,
                posterSize = PosterSize.entries.firstOrNull { it.name == json.optString("posterSize") } ?: PosterSize.NORMAL,
                hideTheater = json.optBoolean("hideTheater", false),
                defaults = defaults
            )
        }

        /** Quality tags of a recording made in a theater. */
        private val THEATER = setOf("CAM", "TS", "SCR")

        /** True when every file of [title] is a theater recording. */
        fun isTheaterCopy(title: VodTitle): Boolean =
            title.kind == VodKind.MOVIE && title.entries.isNotEmpty() && title.entries.all { entry -> entry.qualityTags.any { it in THEATER } }
    }
}
