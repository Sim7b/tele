package io.github.sim7b.tele.data.parser

import android.util.Xml
import io.github.sim7b.tele.data.model.GuideProgram
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * XMLTV guide: programs and display names, indexed by XMLTV channel id.
 */
class XmltvGuide(
    val programs: Map<String, List<GuideProgram>>,
    val displayNames: Map<String, List<String>>
)

/**
 * Streaming reader of an XMLTV guide (possibly compressed as .gz or .zip).
 * Full guides often weigh tens of MB: only the programs of the [fromMillis, toMillis] window are kept,
 * and only for the wanted channels when [wantedChannel] is given
 * (called once per XMLTV channel with its id and display names, declared before the programs).
 */
object XmltvParser {

    // Limits against huge or malicious guides
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private const val MAX_TEXT_RUN = 1024 * 1024
    private const val MAX_CHANNELS = 50_000
    private const val MAX_DISPLAY_NAMES = 16
    private const val MAX_PROGRAMS_PER_CHANNEL = 2_000

    fun parse(
        input: InputStream,
        fromMillis: Long,
        toMillis: Long,
        wantedChannel: ((id: String, displayNames: List<String>) -> Boolean)? = null
    ): XmltvGuide {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        val stream = BufferedInputStream(XmlGuardInputStream(decompressed(input), MAX_BYTES, MAX_TEXT_RUN), 64 * 1024)
        // Explicit encoding: the parser must decode exactly what the byte-based guard checked
        parser.setInput(stream, asciiCompatibleEncoding(stream))

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
                            (programs[channel]?.size ?: 0) < MAX_PROGRAMS_PER_CHANNEL &&
                            (wantedChannel == null || wantedCache[channel] ?: (wantedCache.size < MAX_CHANNELS &&
                                wantedChannel(channel, displayNames[channel].orEmpty()).also { wantedCache[channel] = it }))
                        if (keep) {
                            readProgramme(parser, channel, start, stop)?.let {
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

    /**
     * Encoding declared by the guide (UTF-8 by default), checked to be ASCII-compatible, as the byte-based
     * [XmlGuardInputStream] requires: UTF-16 or UTF-32 guides are rejected.
     */
    internal fun asciiCompatibleEncoding(stream: BufferedInputStream): String {
        stream.mark(PROLOG_BYTES)
        val prolog = ByteArray(PROLOG_BYTES)
        var length = 0
        while (length < PROLOG_BYTES) {
            val read = stream.read(prolog, length, PROLOG_BYTES - length)
            if (read < 0) break
            length += read
        }
        stream.reset()

        val start = prolog.copyOf(length)
        if (start.take(4).any { it == 0.toByte() } || start.startsWith(0xFE, 0xFF) || start.startsWith(0xFF, 0xFE)) {
            throw invalidResponse()
        }
        val declared = ENCODING_REGEX.find(String(start, Charsets.ISO_8859_1).substringBefore("?>"))?.groupValues?.get(1)
            ?: return "UTF-8"
        val charset = runCatching { Charset.forName(declared) }.getOrNull() ?: throw invalidResponse()
        if (!ASCII_SAMPLE.toByteArray(charset).contentEquals(ASCII_SAMPLE.toByteArray(Charsets.US_ASCII))) {
            throw invalidResponse()
        }
        return charset.name()
    }

    private fun ByteArray.startsWith(vararg bytes: Int) = size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }

    private const val PROLOG_BYTES = 256
    private val ENCODING_REGEX = Regex("""^\s*(?:\xEF\xBB\xBF)?<\?xml[^>]*\sencoding\s*=\s*["']([A-Za-z0-9._:-]+)["']""")
    private const val ASCII_SAMPLE = "<!ENTITY tv=\"'0Az\n>"

    /** Detects gzip (1F 8B) and zip (PK) by their magic bytes, whatever the extension. */
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
        if (id !in displayNames && displayNames.size >= MAX_CHANNELS) return skip(parser)
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) return
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "display-name") {
                val name = parser.nextText().trim()
                val names = displayNames[id]
                if (name.isNotEmpty() && (names?.size ?: 0) < MAX_DISPLAY_NAMES) {
                    displayNames.getOrPut(id) { mutableListOf() }.add(name)
                }
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
     * XMLTV timestamp: "20261025183000 +0100" (optional offset, UTC by default; optional seconds).
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

/**
 * Guard of the decompressed XMLTV stream: fails beyond [maxBytes], on a text run of more than [maxTextRun] bytes
 * without markup (a single huge node would be held in memory by the parser) and on entity declarations
 * (entity expansion). Byte-based, which works for the ASCII-compatible encodings used by guides.
 */
internal class XmlGuardInputStream(input: InputStream, maxBytes: Long, private val maxTextRun: Int) :
    CappedInputStream(input, maxBytes) {
    private var textRun = 0
    private var entityMatch = 0

    override fun onRead(b: ByteArray, off: Int, len: Int) {
        super.onRead(b, off, len)
        for (i in off until off + len) {
            val byte = b[i]
            textRun = if (byte == '<'.code.toByte()) 0 else textRun + 1
            if (textRun > maxTextRun) throw responseTooLarge()
            entityMatch = when {
                byte == ENTITY_DECLARATION[entityMatch] -> entityMatch + 1
                byte == ENTITY_DECLARATION[0] -> 1
                else -> 0
            }
            if (entityMatch == ENTITY_DECLARATION.size) throw invalidResponse()
        }
    }

    private companion object {
        val ENTITY_DECLARATION = "<!ENTITY".toByteArray(Charsets.US_ASCII)
    }
}
