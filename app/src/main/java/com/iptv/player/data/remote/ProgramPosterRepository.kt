package com.iptv.player.data.remote

import com.iptv.player.data.epg.EpgResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Service d'enrichissement EPG externe (TVMaze API - Widescreen Backgrounds & Banners).
 * Récupère prioritairement des visuels au format HORIZONTAL (16:9 / Banner) adaptés aux vignettes TV.
 */
object ProgramPosterRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Cache mémoire (y compris des absences de résultat, valeur vide) pour éviter les requêtes répétées
    private val posterCache = ConcurrentHashMap<String, String>()

    /**
     * Récupère l'URL d'un visuel horizontal (16:9 / Backdrop) pour un titre de programme donné.
     */
    suspend fun getPosterUrl(rawTitle: String): String? = withContext(Dispatchers.IO) {
        if (rawTitle.isBlank() || rawTitle == "Programme") return@withContext null

        val cleanTitle = cleanProgramTitle(rawTitle)
        if (cleanTitle.isBlank()) return@withContext null

        // Vérification dans le cache
        posterCache[cleanTitle]?.let { return@withContext it.ifBlank { null } }

        var result: String? = null
        var definitive = true
        try {
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val url = "https://api.tvmaze.com/singlesearch/shows?q=$encodedTitle&embed=images"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.DEFAULT_USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string()
                        if (body != null) result = extractLandscapeImage(JSONObject(body), cleanTitle)
                    }
                    // 404 = aucune émission correspondante ; les autres codes (429, 5xx) sont transitoires
                    response.code != 404 -> definitive = false
                }
            }
        } catch (e: Exception) {
            // Erreur réseau : ne pas mémoriser l'absence de résultat
            definitive = false
        }

        if (result != null || definitive) {
            posterCache[cleanTitle] = result ?: ""
        }
        result
    }

    private fun extractLandscapeImage(json: JSONObject, cleanTitle: String): String? {
        // TVMaze renvoie toujours "le plus proche" : on refuse une émission dont le nom ne correspond pas,
        // sinon des visuels sans rapport s'affichent sur les vignettes.
        val showName = json.optString("name", "")
        if (EpgResolver.normalizeName(showName) != EpgResolver.normalizeName(cleanTitle)) return null

        // 1. Recherche prioritaire d'une image HORIZONTALE (background / banner 16:9)
        val imagesArray = json.optJSONObject("_embedded")?.optJSONArray("images")
        if (imagesArray != null) {
            for (i in 0 until imagesArray.length()) {
                val imgObj = imagesArray.optJSONObject(i) ?: continue
                val type = imgObj.optString("type", "")
                if (type.equals("background", ignoreCase = true) || type.equals("banner", ignoreCase = true)) {
                    val resObj = imgObj.optJSONObject("resolutions")
                    val origObj = resObj?.optJSONObject("original") ?: resObj?.optJSONObject("medium")
                    val landscapeUrl = origObj?.optString("url", "")?.takeIf { it.isNotBlank() }
                    if (landscapeUrl != null) return landscapeUrl
                }
            }
        }

        // 2. Fallback sur l'image standard si aucun fond 16:9 disponible
        val imageObj = json.optJSONObject("image")
        return imageObj?.optString("medium", "")?.takeIf { it.isNotBlank() }
            ?: imageObj?.optString("original", "")?.takeIf { it.isNotBlank() }
    }

    private fun cleanProgramTitle(title: String): String {
        return title.substringBefore(",")
            .substringBefore(":")
            .substringBefore(" -")
            .substringBefore(" (")
            .trim()
    }
}
