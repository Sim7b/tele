package io.github.sim7b.tele.data.search

import io.github.sim7b.tele.data.epg.GuideTimeline
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Search of the programs of the guide by title or genre: same rules as [ChannelSearch] (case, accents and
 * punctuation ignored, each word of the query starting a word of the title or of the genre). Words of time
 * narrow it ("ce soir", "demain", "maintenant", "21h": see [parse]); past programs come when they can be
 * replayed.
 */
object ProgramSearch {

    const val MAX_RESULTS = 60

    /** A program found, with the channel showing it. */
    data class Hit(val channel: Channel, val program: GuideProgram)

    /** Program with its normalized title and genre, computed once per guide (not on every keystroke). */
    class Entry internal constructor(val channel: Channel, val program: GuideProgram) {
        internal val words = ChannelSearch.words(program.title)
        internal val genreWords = program.category?.let { ChannelSearch.words(it) }.orEmpty()
    }

    /**
     * What is searched: the [words] left once the words of time are taken out, and the time the programs
     * must be on air in ([window], null: any time).
     */
    data class Query(val words: List<String>, val window: LongRange?)

    /**
     * Programs of [channels] not over at [now], and the past ones [replayable] says can be watched again,
     * ready to be searched.
     */
    fun index(
        channels: List<Channel>,
        schedules: Map<String, List<GuideProgram>>,
        now: Long,
        replayable: (Channel, GuideProgram) -> Boolean = { _, _ -> false }
    ): List<Entry> {
        if (schedules.isEmpty()) return emptyList()
        val entries = ArrayList<Entry>()
        channels.forEach { channel ->
            GuideTimeline.programsOf(channel, schedules).forEach { program ->
                if (program.title.isNotBlank() && (program.endMillis > now || replayable(channel, program))) entries.add(Entry(channel, program))
            }
        }
        return entries
    }

    /**
     * The words of time of [text] (French or English) turned into a window, the others kept:
     * "maintenant" / "en ce moment" / "now" (on air), "ce soir" / "tonight" (19:00 to 2:00), "demain" /
     * "tomorrow" (the whole day), "aujourd'hui" / "today", "21h", "21h30", "21:30", "9pm" (on air then,
     * today or tomorrow when that hour is past). Combined: "demain soir", "demain 21h".
     */
    fun parse(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Query {
        val words = ChannelSearch.words(text.replace(TYPED_HOUR, " $1h$2 ")).toMutableList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var day = today
        var dayGiven = false
        var evening = false
        var onAirNow = false
        var hour: LocalTime? = null

        fun take(vararg sequence: String): Boolean {
            for (start in 0..words.size - sequence.size) {
                if (sequence.indices.all { words[start + it] == sequence[it] }) {
                    repeat(sequence.size) { words.removeAt(start) }
                    return true
                }
            }
            return false
        }

        if (take("en", "ce", "moment") || take("maintenant") || take("now") || take("right", "now")) onAirNow = true
        if (take("demain") || take("tomorrow")) {
            day = today.plusDays(1)
            dayGiven = true
        }
        if (take("aujourd", "hui") || take("aujourdhui") || take("today")) dayGiven = true
        // "soir" alone may be a word of a title: only after a day ("demain soir")
        if (take("ce", "soir") || take("tonight") || (dayGiven && (take("soir") || take("evening")))) evening = true
        // "21h", "21h30", "9pm"
        // "21h", "21h30", "9pm" (a word that only looks like one, "24h", stays a word of the title)
        fun hourOf(word: String): LocalTime? = HOUR.matchEntire(word)?.let { match ->
            val h = match.groupValues[1].toInt()
            val m = match.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
        } ?: PM.matchEntire(word)?.let { match ->
            val h = match.groupValues[1].toInt()
            if (h in 1..12) LocalTime.of((h % 12) + if (match.groupValues[2] == "pm") 12 else 0, 0) else null
        }
        val hourIndex = words.indexOfFirst { hourOf(it) != null }
        if (hourIndex >= 0) hour = hourOf(words.removeAt(hourIndex))

        val window = when {
            hour != null -> {
                var at = day.atTime(hour).atZone(zone).toInstant().toEpochMilli()
                // An hour past today: the next one
                if (!dayGiven && at < now - HOUR_PAST_MS) at = day.plusDays(1).atTime(hour).atZone(zone).toInstant().toEpochMilli()
                at..at
            }
            evening -> day.atTime(EVENING_START).atZone(zone).toInstant().toEpochMilli()..
                day.plusDays(1).atTime(EVENING_END).atZone(zone).toInstant().toEpochMilli()
            onAirNow -> now..now
            dayGiven -> day.atStartOfDay(zone).toInstant().toEpochMilli()..day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            else -> null
        }
        return Query(words, window)
    }

    /**
     * Programs whose title (or genre) holds every word of [query], within its time: on air first, then the
     * coming ones by start time, then the past ones (replays), the latest first; a program shown by several
     * channels at the same time (two versions of a channel) comes once. Words of time alone list what is on
     * then.
     */
    fun search(entries: List<Entry>, query: String, now: Long, limit: Int = MAX_RESULTS, zone: ZoneId = ZoneId.systemDefault()): List<Hit> {
        val parsed = parse(query, now, zone)
        val queryWords = parsed.words
        val window = parsed.window
        if (queryWords.isEmpty() && window == null) return emptyList()
        val seen = HashSet<Pair<String, Long>>()
        return entries.asSequence()
            .filter { entry -> window == null || (entry.program.startMillis <= window.last && entry.program.endMillis > window.first) }
            .filter { entry ->
                queryWords.all { word -> entry.words.any { it.startsWith(word) } } ||
                    (queryWords.isNotEmpty() && queryWords.all { word -> entry.genreWords.any { it.startsWith(word) } })
            }
            .sortedWith(
                compareBy<Entry>(
                    { rank(it.program, now) },
                    { if (it.program.endMillis <= now) -it.program.startMillis else it.program.startMillis }
                )
            )
            .filter { seen.add(it.program.title.lowercase() to it.program.startMillis) }
            .take(limit)
            .map { Hit(it.channel, it.program) }
            .toList()
    }

    // On air, to come, past
    private fun rank(program: GuideProgram, now: Long) = when {
        program.endMillis <= now -> 2
        program.startMillis > now -> 1
        else -> 0
    }

    // "21:30", "21 h 30", "21h" typed (not the digits of a name: "TF1 HD")
    private val TYPED_HOUR = Regex("""(?<![\p{L}\d])(\d{1,2})\s*[:hH]\s*(\d{2})?(?![\p{L}\d])""")
    private val HOUR = Regex("""(\d{1,2})h(\d{2})?""")
    private val PM = Regex("""(\d{1,2})(am|pm)""")
    private val EVENING_START: LocalTime = LocalTime.of(19, 0)
    private val EVENING_END: LocalTime = LocalTime.of(2, 0)
    // "21h" asked at 21:20: still tonight's
    private const val HOUR_PAST_MS = 60 * 60_000L
}
