package com.iptv.player.data

import org.json.JSONObject

/**
 * Lecture sûre d'une chaîne JSON : org.json renvoie la chaîne littérale "null"
 * pour une valeur JSON null avec optString(), ce qui polluait les tvg_id, logos et descriptions.
 */
fun JSONObject.optStringOrNull(name: String): String? {
    if (isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}
