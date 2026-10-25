package com.iptv.player.data.parser

import android.util.Xml
import com.iptv.player.data.model.GuideProgram
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Guide XMLTV : programmes et noms d'affichage, indexés par identifiant de chaîne XMLTV.
 */
class XmltvGuide(
    val programs: Map<String, List<GuideProgram>>,
    val displayNames: Map<String, List<String>>
)

/**
 * Lecture en flux d'un guide XMLTV (éventuellement compressé .gz ou .zip).
 * Les guides complets pèsent souvent des dizaines de Mo : seuls les programmes de la fenêtre
 * [fromMillis, toMillis] sont conservés, et uniquement pour les chaînes demandées si [wantedChannel] est fourni
 * (appelé une fois par chaîne XMLTV avec son identifiant et ses noms d'affichage, déclarés avant les programmes).
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        fromMillis: Long,
        toMillis: Long,
        wantedChannel: ((id: String, displayNames: List<String>) -> Boolean)? = null
    ): XmltvGuide {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(decompressed(input), null)

        val programs = HashMap<String, MutableList<GuideProgram>>()
        val displayNames = HashMap<String, MutableList<String>>()
        val wantedCache = HashMap<String, Boolean>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser, displayNames)
                    "programme" -> {
                        val channel = parser.getAttributeValue(null, "channel")
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        val keep = channel != null && start > 0 && stop > start &&
                            stop > fromMillis && start < toMillis &&
                            (wantedChannel == null || wantedCache.getOrPut(channel) {
                                wantedChannel(channel, displayNames[channel].orEmpty())
                            })
                        if (keep) {
                            readProgramme(parser, channel!!, start, stop)?.let {
                                programs.getOrPut(channel) { mutableListOf() }.add(it)
                            }
                        } else {
                            skip(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }

        programs.values.forEach { list -> list.sortBy { it.startMillis } }
        return XmltvGuide(programs, displayNames)
    }

    /** Détecte gzip (1F 8B) et zip (PK) par leurs octets magiques, quelle que soit l'extension. */
    private fun decompressed(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return when {
            b0 == 0x1F && b1 == 0x8B -> BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024)
            b0 == 0x50 && b1 == 0x4B -> ZipInputStream(buffered).also { it.nextEntry }
            else -> buffered
        }
    }

    private fun readChannel(parser: XmlPullParser, displayNames: MutableMap<String, MutableList<String>>) {
        val id = parser.getAttributeValue(null, "id") ?: return skip(parser)
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) return
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "display-name") {
                val name = parser.nextText().trim()
                if (name.isNotEmpty()) displayNames.getOrPut(id) { mutableListOf() }.add(name)
            }
        }
    }

    private fun readProgramme(parser: XmlPullParser, channel: String, start: Long, stop: Long): GuideProgram? {
        var title: String? = null
        var description: String? = null
        var category: String? = null
        var icon: String? = null
        var rating: String? = null

        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> if (title == null) title = parser.nextText().trim()
                "desc" -> if (description == null) description = parser.nextText().trim()
                "category" -> if (category == null) category = parser.nextText().trim()
                "icon" -> if (icon == null) icon = parser.getAttributeValue(null, "src")
                "value" -> if (rating == null) rating = parser.nextText().trim()
            }
        }

        val programTitle = title?.takeIf { it.isNotEmpty() } ?: return null
        return GuideProgram(
            id = "$channel-$start",
            channelUuid = channel,
            title = programTitle,
            description = description?.takeIf { it.isNotEmpty() },
            startTimeIso = "",
            endTimeIso = "",
            posterUrl = icon?.takeIf { it.isNotBlank() },
            rating = rating?.takeIf { it.isNotEmpty() },
            startMillis = start,
            endMillis = stop,
            category = category?.takeIf { it.isNotEmpty() }
        )
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Horodatage XMLTV : "20261025183000 +0100" (décalage facultatif, UTC par défaut ; secondes facultatives).
     */
    fun parseTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val trimmed = value.trim()
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.length < 12) return 0L
        return try {
            val dateTime = LocalDateTime.of(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt(),
                digits.substring(6, 8).toInt(),
                digits.substring(8, 10).toInt(),
                digits.substring(10, 12).toInt(),
                if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
            )
            val offsetText = trimmed.substring(digits.length).trim()
            val offset = if (offsetText.length >= 5 && (offsetText[0] == '+' || offsetText[0] == '-')) {
                val sign = if (offsetText[0] == '-') -1 else 1
                ZoneOffset.ofHoursMinutes(
                    sign * offsetText.substring(1, 3).toInt(),
                    sign * offsetText.substring(3, 5).toInt()
                )
            } else {
                ZoneOffset.UTC
            }
            dateTime.toInstant(offset).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }
}
