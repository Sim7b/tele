package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.model.EpgProgram
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Computes the current / next program from an EPG grid.
 */
object EpgResolver {

    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // Compiled once: names are normalized hundreds of thousands of times when matching a guide
    private val COUNTRY_SUFFIX = Regex("""\.[a-z]{2,3}$""", RegexOption.IGNORE_CASE)
    private val DIACRITICS = Regex("""\p{M}+""")
    private val NON_ALPHANUMERIC = Regex("""[^a-z0-9+]""")

    // Formatted times, shared by the thousands of channels whose programs start at the same instants
    private const val MAX_CLOCK_CACHE = 20_000
    private val clockCache = ConcurrentHashMap<Long, String>()
    @Volatile
    private var clockCacheZone: ZoneId? = null

    /**
     * Parses an ISO-8601 timestamp to epoch ms. Accepts "Z" and "+00:00" offsets
     * (Instant.parse of Android 8-11 rejects offsets, used by Dispatcharr "dummy" programs).
     */
    fun parseIsoToMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (e2: Exception) {
                0L
            }
        }
    }

    /** Device local time (not the raw UTC time of the ISO string). */
    fun formatClock(millis: Long): String {
        if (millis <= 0L) return ""
        val zone = ZoneId.systemDefault()
        if (zone != clockCacheZone || clockCache.size > MAX_CLOCK_CACHE) {
            clockCache.clear()
            clockCacheZone = zone
        }
        return clockCache.getOrPut(millis) { Instant.ofEpochMilli(millis).atZone(zone).format(clockFormatter) }
    }

    fun progress(startMillis: Long, endMillis: Long, now: Long): Float {
        if (startMillis <= 0L || endMillis <= startMillis) return 0f
        return ((now - startMillis).toFloat() / (endMillis - startMillis).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Program aired at [now] (with the next one), or null when no program covers that instant.
     * [programs] must be sorted by start time.
     */
    fun currentProgram(programs: List<GuideProgram>, now: Long): EpgProgram? {
        val currentIndex = currentIndex(programs, now)
        if (currentIndex == -1) return null
        val current = programs[currentIndex]
        var next: GuideProgram? = null
        for (i in currentIndex + 1 until programs.size) {
            if (programs[i].startMillis >= current.endMillis - 60_000L) {
                next = programs[i]
                break
            }
        }

        return EpgProgram(
            title = current.title,
            category = current.category ?: current.rating,
            startTime = formatClock(current.startMillis),
            endTime = formatClock(current.endMillis),
            progress = progress(current.startMillis, current.endMillis, now),
            description = current.description,
            posterUrl = current.posterUrl,
            nextTitle = next?.title,
            nextStartTime = next?.let { formatClock(it.startMillis) },
            startMillis = current.startMillis,
            endMillis = current.endMillis
        )
    }

    /**
     * Index of the program covering [now] in [programs] sorted by start time, or -1. Binary search of the
     * last program started at [now], then a short walk back for programs overlapping it.
     */
    internal fun currentIndex(programs: List<GuideProgram>, now: Long): Int {
        var low = 0
        var high = programs.lastIndex
        var lastStarted = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (programs[mid].startMillis <= now) {
                lastStarted = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        // Overlapping programs: the earliest one still running wins, as with a linear scan
        var found = -1
        var i = lastStarted
        while (i >= 0 && lastStarted - i < MAX_OVERLAP_LOOKBACK) {
            if (now < programs[i].endMillis) found = i
            i--
        }
        return found
    }

    private const val MAX_OVERLAP_LOOKBACK = 8

    /** Updates the progress of a cached program, or null when it has ended. */
    fun refreshCached(program: EpgProgram, now: Long): EpgProgram? {
        if (program.endMillis <= 0L || now >= program.endMillis) return null
        return program.copy(progress = progress(program.startMillis, program.endMillis, now))
    }

    /** Normalization for name matching ("France 2" == "France2.fr" == "FRANCE 2"). */
    fun normalizeName(name: String): String {
        val withoutCountry = name.trim().replace(COUNTRY_SUFFIX, "")
        return Normalizer.normalize(withoutCountry, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .replace(NON_ALPHANUMERIC, "")
    }
}
