package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.model.EpgProgram
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Computes the current / next program from an EPG grid.
 */
object EpgResolver {

    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(clockFormatter)
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
        val currentIndex = programs.indexOfFirst { now >= it.startMillis && now < it.endMillis }
        if (currentIndex == -1) return null
        val current = programs[currentIndex]
        val next = programs.drop(currentIndex + 1).firstOrNull { it.startMillis >= current.endMillis - 60_000L }

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

    /** Updates the progress of a cached program, or null when it has ended. */
    fun refreshCached(program: EpgProgram, now: Long): EpgProgram? {
        if (program.endMillis <= 0L || now >= program.endMillis) return null
        return program.copy(progress = progress(program.startMillis, program.endMillis, now))
    }

    /** Normalization for name matching ("France 2" == "France2.fr" == "FRANCE 2"). */
    fun normalizeName(name: String): String {
        val withoutCountry = name.trim().replace(Regex("""\.[a-z]{2,3}$""", RegexOption.IGNORE_CASE), "")
        return Normalizer.normalize(withoutCountry, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9+]"""), "")
    }
}
