package io.github.sim7b.tele.data.model

import org.json.JSONArray
import org.json.JSONObject

/** Look of the live TV home: the usual one, or the program of the focused channel over the whole screen. */
enum class HomeStyle { CLASSIC, IMMERSIVE }

/** Rows of the immersive home besides the favorites (always first), in this order. */
enum class HomeRow { RESUME, SPORT, TRAKT, CATEGORIES }

/** The home chosen in the settings: its style and the rows of the immersive one. */
data class HomeLayout(
    val style: HomeStyle = HomeStyle.CLASSIC,
    val rows: Set<HomeRow> = HomeRow.entries.toSet()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("style", style.name)
        .put("rows", JSONArray(HomeRow.entries.filter { it in rows }.map { it.name }))

    companion object {
        /** Saved by [toJson]; unknown values are ignored. */
        fun fromJson(json: JSONObject): HomeLayout = HomeLayout(
            style = HomeStyle.entries.firstOrNull { it.name == json.optString("style") } ?: HomeStyle.CLASSIC,
            rows = json.optJSONArray("rows")?.let { array ->
                (0 until array.length()).mapNotNull { index -> HomeRow.entries.firstOrNull { it.name == array.optString(index) } }.toSet()
            } ?: HomeRow.entries.toSet()
        )
    }
}
