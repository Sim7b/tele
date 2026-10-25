package com.iptv.player.data.epg

import com.iptv.player.data.model.GuideProgram
import com.iptv.player.data.model.EpgProgram
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Calcul du programme en cours / suivant à partir de la grille EPG Dispatcharr.
 */
object EpgResolver {

    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Parse un horodatage ISO-8601 en epoch ms. Accepte "Z" et les décalages "+00:00"
     * (Instant.parse d'Android 8-11 refuse les décalages, utilisés par les programmes "dummy" Dispatcharr).
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

    /** Heure locale de l'appareil (et non l'heure UTC brute de la chaîne ISO). */
    fun formatClock(millis: Long): String {
        if (millis <= 0L) return ""
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(clockFormatter)
    }

    fun progress(startMillis: Long, endMillis: Long, now: Long): Float {
        if (startMillis <= 0L || endMillis <= startMillis) return 0f
        return ((now - startMillis).toFloat() / (endMillis - startMillis).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Retourne le programme diffusé à [now] (avec le suivant), ou null si aucun programme ne couvre cet instant.
     * [programs] doit être trié par heure de début.
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

    /** Met à jour la progression d'un programme mis en cache, ou null s'il est terminé. */
    fun refreshCached(program: EpgProgram, now: Long): EpgProgram? {
        if (program.endMillis <= 0L || now >= program.endMillis) return null
        return program.copy(progress = progress(program.startMillis, program.endMillis, now))
    }

    /** Normalisation pour la correspondance par nom ("France 2" == "France2.fr" == "FRANCE 2"). */
    fun normalizeName(name: String): String {
        val withoutCountry = name.trim().replace(Regex("""\.[a-z]{2,3}$""", RegexOption.IGNORE_CASE), "")
        return Normalizer.normalize(withoutCountry, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9+]"""), "")
    }
}
