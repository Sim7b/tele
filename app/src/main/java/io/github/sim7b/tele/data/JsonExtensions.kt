package io.github.sim7b.tele.data

import org.json.JSONObject

/**
 * Safe read of a JSON string: org.json optString() returns the literal string "null"
 * for a JSON null value, which polluted tvg_ids, logos and descriptions.
 */
fun JSONObject.optStringOrNull(name: String): String? {
    if (isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}
