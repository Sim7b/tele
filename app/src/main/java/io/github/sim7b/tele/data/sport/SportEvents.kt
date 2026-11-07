package io.github.sim7b.tele.data.sport

import io.github.sim7b.tele.data.epg.GuideTimeline
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.search.ChannelSearch

/** A program of a followed team or competition, on the channel showing it. */
data class SportEvent(val channel: Channel, val program: GuideProgram)

/**
 * Sport followed by the user (teams, competitions: "PSG", "Stade Toulousain", "Ligue 1") found in the guide:
 * a program whose title or genre holds every word of one of them, as whole words ("PSG" is not in "PSGX",
 * accents and case aside).
 */
object SportEvents {

    const val MAX_FOLLOWED = 30
    const val MAX_LENGTH = 40
    const val MAX_EVENTS = 30
    // Coming ones shown up to two days ahead, past ones (with a replay) of the last day
    const val AHEAD_MS = 48 * 60 * 60_000L
    const val BEHIND_MS = 24 * 60 * 60_000L

    /** The followed names typed (one per line or comma): trimmed, without duplicates nor empty ones. */
    fun clean(typed: String): List<String> =
        typed.split('\n', ',', ';')
            .map { it.trim().take(MAX_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinctBy { ChannelSearch.words(it).joinToString(" ") }
            .take(MAX_FOLLOWED)

    /** [program] is about one of [followed] (its words, see [ChannelSearch.words]). */
    fun matches(followed: List<List<String>>, program: GuideProgram): Boolean {
        if (followed.isEmpty()) return false
        val words = (ChannelSearch.words(program.title) + program.category?.let { ChannelSearch.words(it) }.orEmpty()).toHashSet()
        return followed.any { term -> term.isNotEmpty() && term.all { it in words } }
    }

    /**
     * Programs of [followed] in the guide: on air first, then the coming ones by start (within [AHEAD_MS]),
     * then the past ones [replayable] says can be watched again (within [BEHIND_MS], the latest first). A program
     * shown by two versions of a channel comes once.
     */
    fun find(
        followed: List<String>,
        channels: List<Channel>,
        schedules: Map<String, List<GuideProgram>>,
        now: Long,
        replayable: (Channel, GuideProgram) -> Boolean = { _, _ -> false },
        limit: Int = MAX_EVENTS
    ): List<SportEvent> {
        val terms = followed.map { ChannelSearch.words(it) }.filter { it.isNotEmpty() }
        if (terms.isEmpty() || schedules.isEmpty()) return emptyList()
        val found = ArrayList<SportEvent>()
        channels.forEach { channel ->
            GuideTimeline.programsOf(channel, schedules).forEach { program ->
                val inWindow = when {
                    program.endMillis <= now -> program.endMillis > now - BEHIND_MS && replayable(channel, program)
                    else -> program.startMillis < now + AHEAD_MS
                }
                if (inWindow && program.endMillis > program.startMillis && matches(terms, program)) found.add(SportEvent(channel, program))
            }
        }
        val seen = HashSet<Pair<String, Long>>()
        return found
            .sortedWith(
                compareBy<SportEvent>(
                    { rank(it.program, now) },
                    { if (it.program.endMillis <= now) -it.program.startMillis else it.program.startMillis }
                )
            )
            .filter { seen.add(it.program.title.lowercase() to it.program.startMillis) }
            .take(limit)
    }

    /** On air, to come, past. */
    fun rank(program: GuideProgram, now: Long) = when {
        program.endMillis <= now -> 2
        program.startMillis > now -> 1
        else -> 0
    }
}
