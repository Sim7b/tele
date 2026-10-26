package io.github.sim7b.tele.data.search

import io.github.sim7b.tele.data.model.Channel
import java.text.Normalizer

/**
 * Channel search, typed or spoken: case, accents and punctuation are ignored ("tf 1" finds "TF1",
 * "France televisions" finds "France Télévisions"). Every word of the query must appear in the channel
 * name or in its current program; name matches come first, then channel number matches, then programs.
 */
object ChannelSearch {

    const val MAX_RESULTS = 200

    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

    /** Channel with its normalized name, computed once per channel list (not on every keystroke). */
    class Entry internal constructor(val channel: Channel) {
        internal val nameWords = words(channel.name)
        internal val compactName = nameWords.joinToString("")
    }

    fun index(channels: List<Channel>): List<Entry> = channels.map(::Entry)

    /** Lowercase words without accents. */
    internal fun words(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }

    fun search(channels: List<Channel>, query: String, limit: Int = MAX_RESULTS): List<Channel> =
        searchIndex(index(channels), query, limit)

    fun searchIndex(entries: List<Entry>, query: String, limit: Int = MAX_RESULTS): List<Channel> {
        val queryWords = words(query)
        if (queryWords.isEmpty()) return emptyList()
        // Spaces ignored too: a spoken "M 6" or "tf 1" matches "M6" / "TF1"
        val compactQuery = queryWords.joinToString("")

        return entries.asSequence()
            .mapNotNull { entry ->
                val channel = entry.channel
                val rank = when {
                    entry.compactName.startsWith(compactQuery) -> 0
                    queryWords.all { word -> entry.nameWords.any { it.startsWith(word) } } -> 1
                    entry.compactName.contains(compactQuery) -> 2
                    channel.number?.toString() == compactQuery -> 3
                    channel.currentProgram?.title?.let { title ->
                        val compactTitle = words(title).joinToString("")
                        queryWords.all { it in compactTitle }
                    } == true -> 4
                    else -> return@mapNotNull null
                }
                rank to channel
            }
            // Stable sort: same rank keeps the channel order of the source
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
            .toList()
    }
}
