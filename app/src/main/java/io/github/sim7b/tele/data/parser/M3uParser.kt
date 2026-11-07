package io.github.sim7b.tele.data.parser

import java.io.InputStream
import java.io.InputStreamReader

/**
 * M3U playlist entry (live channel).
 */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val number: Int?,
    // Replay announced by the playlist (catchup, catchup-days, catchup-source)
    val catchupMode: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null
)

/**
 * M3U playlist: channels and XMLTV guides announced in the header (url-tvg / x-tvg-url).
 */
class M3uPlaylist(
    val entries: List<M3uEntry>,
    val epgUrls: List<String>
)

object M3uParser {

    private val ATTRIBUTE_REGEX = """([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""".toRegex()

    // Movies and series of Xtream playlists (type=m3u_plus): the app only handles live TV
    private val VOD_PATH_REGEX = """/(movie|series)/""".toRegex(RegexOption.IGNORE_CASE)
    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v")

    // Limits against huge or malicious playlists
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MAX_LINE_LENGTH = 64 * 1024
    private const val MAX_ENTRIES = 200_000

    /**
     * Reads the playlist line by line (provider playlists often exceed 50,000 entries).
     * Lines are cut to [MAX_LINE_LENGTH] characters and the reading stops at [maxEntries] live channels,
     * or movies and episodes with [vod].
     */
    fun parse(input: InputStream, maxBytes: Long = MAX_BYTES, maxEntries: Int = MAX_ENTRIES, vod: Boolean = false): M3uPlaylist {
        val reader = BoundedLineReader(InputStreamReader(CappedInputStream(input, maxBytes), Charsets.UTF_8), MAX_LINE_LENGTH)
        val entries = ArrayList<M3uEntry>()
        val epgUrls = mutableListOf<String>()

        var attributes: Map<String, String> = emptyMap()
        var title: String? = null
        var extGroup: String? = null

        while (entries.size < maxEntries) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val header = parseAttributes(line)
                    listOfNotNull(header["url-tvg"], header["x-tvg-url"]).forEach { value ->
                        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(epgUrls::add)
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val commaIndex = commaOutsideQuotes(line)
                    attributes = parseAttributes(if (commaIndex >= 0) line.substring(0, commaIndex) else line)
                    title = if (commaIndex >= 0) line.substring(commaIndex + 1).trim() else null
                    extGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    // URL line: ends the current entry
                    if (isVod(line) == vod) {
                        val name = title?.takeIf { it.isNotEmpty() }
                            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
                            // Language-neutral fallback: the data layer has no access to the string resources
                            ?: "#${entries.size + 1}"
                        entries.add(
                            M3uEntry(
                                name = name,
                                url = line,
                                tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
                                tvgName = attributes["tvg-name"]?.takeIf { it.isNotBlank() },
                                logo = attributes["tvg-logo"]?.takeIf { it.isNotBlank() },
                                // Multiple categories ("News;Sports"): the first one is enough for grouping
                                group = (attributes["group-title"] ?: extGroup)?.substringBefore(";")?.trim()?.takeIf { it.isNotBlank() },
                                number = (attributes["tvg-chno"] ?: attributes["channel-number"])?.trim()?.toIntOrNull(),
                                catchupMode = catchupMode(attributes),
                                catchupDays = if (catchupMode(attributes) != null) catchupDays(attributes) else 0,
                                catchupSource = attributes["catchup-source"]?.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    attributes = emptyMap()
                    title = null
                    extGroup = null
                }
            }
        }
        return M3uPlaylist(entries, epgUrls.distinct())
    }

    /**
     * How the playlist makes the replay URLs of a channel ("catchup", or the older "timeshift" / "tvg-rec" with a
     * number of days, which mean "shift"): lowercase, null without replay.
     */
    private fun catchupMode(attributes: Map<String, String>): String? {
        val mode = (attributes["catchup"] ?: attributes["catchup-type"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "disabled" }
        if (mode != null) return if (mode == "timeshift") "shift" else mode
        return "shift".takeIf { (attributes["timeshift"] ?: attributes["tvg-rec"])?.trim()?.toIntOrNull()?.let { it > 0 } == true }
    }

    /** Days of replay announced (catchup-days, timeshift, tvg-rec), [DEFAULT_CATCHUP_DAYS] when not said. */
    private fun catchupDays(attributes: Map<String, String>): Int {
        val days = listOf("catchup-days", "timeshift", "tvg-rec").firstNotNullOfOrNull { attributes[it]?.trim()?.toIntOrNull() }
        return (days ?: DEFAULT_CATCHUP_DAYS).coerceIn(0, MAX_CATCHUP_DAYS)
    }

    // As the usual players do when the playlist gives no number of days
    private const val DEFAULT_CATCHUP_DAYS = 5
    private const val MAX_CATCHUP_DAYS = 31

    private fun parseAttributes(text: String): Map<String, String> =
        ATTRIBUTE_REGEX.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

    /** Comma separating the attributes from the title (quoted values may contain commas). */
    private fun commaOutsideQuotes(line: String): Int {
        var inQuotes = false
        line.forEachIndexed { index, char ->
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> return index
            }
        }
        return -1
    }

    /** True for a series episode (Xtream /series/ path, or "S01E02" in the name), else a movie. */
    fun isSeriesEpisode(url: String, name: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        if ("/series/" in path) return true
        if ("/movie/" in path) return false
        return EPISODE_REGEX.containsMatchIn(name)
    }

    private val EPISODE_REGEX = """(?i)\bS\d{1,2}\s*[ ._-]?\s*E\d{1,3}\b""".toRegex()

    private fun isVod(url: String): Boolean {
        val path = url.substringBefore('?')
        if (VOD_PATH_REGEX.containsMatchIn(path)) return true
        return path.substringAfterLast('.', "").lowercase() in VOD_EXTENSIONS
    }
}
