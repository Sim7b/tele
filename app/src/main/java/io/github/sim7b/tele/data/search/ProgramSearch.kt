package io.github.sim7b.tele.data.search

import io.github.sim7b.tele.data.epg.GuideTimeline
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram

/**
 * Search of the programs of the guide, on air or to come, by title: same rules as [ChannelSearch] (case,
 * accents and punctuation ignored, each word of the query starting a word of the title).
 */
object ProgramSearch {

    const val MAX_RESULTS = 60

    /** A program found, with the channel showing it. */
    data class Hit(val channel: Channel, val program: GuideProgram)

    /** Program with its normalized title, computed once per guide (not on every keystroke). */
    class Entry internal constructor(val channel: Channel, val program: GuideProgram) {
        internal val words = ChannelSearch.words(program.title)
    }

    /** Programs of [channels] not over at [now], ready to be searched. */
    fun index(channels: List<Channel>, schedules: Map<String, List<GuideProgram>>, now: Long): List<Entry> {
        if (schedules.isEmpty()) return emptyList()
        val entries = ArrayList<Entry>()
        channels.forEach { channel ->
            GuideTimeline.programsOf(channel, schedules).forEach { program ->
                if (program.endMillis > now && program.title.isNotBlank()) entries.add(Entry(channel, program))
            }
        }
        return entries
    }

    /**
     * Programs whose title holds every word of [query]: on air first, then by start time; a program
     * shown by several channels at the same time (two versions of a channel) comes once.
     */
    fun search(entries: List<Entry>, query: String, now: Long, limit: Int = MAX_RESULTS): List<Hit> {
        val queryWords = ChannelSearch.words(query)
        if (queryWords.isEmpty()) return emptyList()
        val seen = HashSet<Pair<String, Long>>()
        return entries.asSequence()
            .filter { entry -> queryWords.all { word -> entry.words.any { it.startsWith(word) } } }
            .sortedWith(compareBy<Entry>({ it.program.startMillis > now }, { it.program.startMillis }))
            .filter { seen.add(it.program.title.lowercase() to it.program.startMillis) }
            .take(limit)
            .map { Hit(it.channel, it.program) }
            .toList()
    }
}
