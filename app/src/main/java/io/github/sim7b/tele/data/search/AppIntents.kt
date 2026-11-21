package io.github.sim7b.tele.data.search

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.vod.VodBrowse
import io.github.sim7b.tele.data.vod.VodTitle
import java.net.URI
import java.net.URLDecoder

/**
 * What the app is opened for from outside: a movie or a series from the "Play next" row of the TV home
 * ([io.github.sim7b.tele.data.vod.WatchNext], tele://vod?key=…), a channel from the notification of a reminder
 * (tele://channel?key=…), or a voice request of the Assistant ("Mets France 2 sur Télé").
 */
sealed interface AppRequest {
    data class OpenTitle(val titleKey: String) : AppRequest
    data class OpenChannel(val channelId: String) : AppRequest
    data class Play(val query: String) : AppRequest
}

object AppIntents {

    const val SCHEME = "tele"
    const val VOD_HOST = "vod"
    const val CHANNEL_HOST = "channel"
    const val HOME_HOST = "home"
    const val KEY_PARAM = "key"
    const val ACTION_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    const val ACTION_SEARCH = "android.intent.action.SEARCH"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val MAX_QUERY = 200

    /** The request of an intent ([action], its data URI, its search [query]); null for a plain launch. */
    fun parse(action: String?, data: String?, query: String?): AppRequest? = when (action) {
        ACTION_VIEW -> data?.let { link ->
            linkParam(link, VOD_HOST)?.let { AppRequest.OpenTitle(it) } ?: linkParam(link, CHANNEL_HOST)?.let { AppRequest.OpenChannel(it) }
        }
        ACTION_PLAY_FROM_SEARCH, ACTION_SEARCH -> query?.trim()?.take(MAX_QUERY)?.takeIf { it.isNotEmpty() }?.let { AppRequest.Play(it) }
        else -> null
    }

    /** Link of a title for the TV home: tele://vod?key=… */
    fun titleLink(titleKey: String): String = "$SCHEME://$VOD_HOST?$KEY_PARAM=" + java.net.URLEncoder.encode(titleKey, "UTF-8")

    /** Link of the app itself (the row of the favorites on the TV home): tele://home, nothing more to open */
    fun homeLink(): String = "$SCHEME://$HOME_HOST"

    /** Link of a channel (notification of a reminder): tele://channel?key=… */
    fun channelLink(channelId: String): String = "$SCHEME://$CHANNEL_HOST?$KEY_PARAM=" + java.net.URLEncoder.encode(channelId, "UTF-8")

    private fun linkParam(link: String, host: String): String? = try {
        val uri = URI(link)
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(host, ignoreCase = true)) {
            null
        } else {
            uri.rawQuery?.split('&')
                ?.firstOrNull { it.startsWith("$KEY_PARAM=") }
                ?.removePrefix("$KEY_PARAM=")
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    /** A voice request made plain: what to look for, and the season and episode asked for ("saison 2 épisode 3"). */
    data class VoiceQuery(val text: String, val season: Int? = null, val episode: Int? = null)

    // "regarde", "mets la chaîne", "lance le film"...: the words around what is asked
    private val LEADING = Regex(
        """^(?:(?:je veux |je voudrais )?(?:regarder?|mets|mettre|lance[rz]?|joue[rz]?|ouvre|affiche|voir|watch|play|put on|open|start)\s+)?""" +
            // "la chaîne", "le film": the article only with the kind ("Le Journal" stays whole)
            """(?:(?:(?:la|le|l'|the)\s*)?(?:chaine|chaîne|film|série|serie|channel|movie|series)\s+)?""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING = Regex("""\s+(?:sur|on|dans|in)\s+t[ée]l[ée]\s*$""", RegexOption.IGNORE_CASE)
    private val SEASON_EPISODE = Regex(
        """\b(?:(?:saison|season)\s*(\d{1,2})(?:\s*(?:,|et|and)?\s*(?:[ée]pisode|episode)\s*(\d{1,3}))?|s(\d{1,2})\s*e(\d{1,3})|(?:[ée]pisode|episode)\s*(\d{1,3}))\b""",
        RegexOption.IGNORE_CASE
    )

    /** What a voice request asks for: [VoiceQuery.text] without the verbs nor "sur Télé", the season and episode apart. */
    fun voiceQuery(raw: String): VoiceQuery {
        var text = raw.trim().take(MAX_QUERY)
        val found = SEASON_EPISODE.find(text)
        var season: Int? = null
        var episode: Int? = null
        if (found != null) {
            val groups = found.groupValues
            season = (groups[1].ifEmpty { groups[3] }).toIntOrNull()
            episode = (groups[2].ifEmpty { groups[4] }.ifEmpty { groups[5] }).toIntOrNull()
            text = text.removeRange(found.range)
        }
        text = text.replace(TRAILING, "").replace(LEADING, "").replace(Regex("\\s+"), " ").trim()
        return VoiceQuery(text.ifEmpty { raw.trim().take(MAX_QUERY) }, season?.takeIf { it > 0 }, episode?.takeIf { it > 0 })
    }

    /**
     * The channel asked by voice: its name said exactly (accents, case and "HD" aside), else its number,
     * else a name starting with the request; the shortest name wins ("France 2" over "France 24").
     */
    fun channelFor(query: String, channels: List<Channel>): Channel? {
        val wanted = clean(query)
        if (wanted.isEmpty()) return null
        val named = channels.map { it to clean(it.name) }
        named.firstOrNull { it.second == wanted }?.let { return it.first }
        wanted.toIntOrNull()?.let { number -> channels.firstOrNull { it.number == number }?.let { return it } }
        return named.filter { (_, name) -> name.startsWith("$wanted ") }.minByOrNull { it.second.length }?.first
    }

    /** The movie or series asked by voice: its title exactly, else the best-known title starting with it. */
    fun titleFor(query: String, titles: List<VodTitle>): VodTitle? {
        val wanted = VodBrowse.normalize(query)
        if (wanted.isEmpty()) return null
        titles.firstOrNull { VodBrowse.normalize(it.title) == wanted }?.let { return it }
        return titles.filter { VodBrowse.normalize(it.title).startsWith("$wanted ") }.maxByOrNull { it.rating ?: 0.0 }
    }

    // Quality marks left out of the names: "TF1 HD" is asked as "TF1"
    private val QUALITY = Regex("""\b(uhd|fhd|hd|sd|4k|hevc|h265|1080p|720p)\b""")

    private fun clean(text: String): String = VodBrowse.normalize(text).replace(QUALITY, " ").replace(Regex("\\s+"), " ").trim()
}
