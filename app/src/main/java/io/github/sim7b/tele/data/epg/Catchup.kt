package io.github.sim7b.tele.data.epg

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Replay of past programs (Xtream "catch-up"): which programs can be watched again, and the
 * timeshift URL of the provider for one of them.
 */
object Catchup {

    /** Longest replay a provider may announce (beyond, the value is treated as broken). */
    const val MAX_DAYS = 31

    /**
     * How far back the guide keeps the programs of a source with replay: past programs cost memory,
     * a day covers what is usually watched again.
     */
    const val GUIDE_PAST_WINDOW_MS = 24 * 60 * 60 * 1000L

    private val startFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    /** [program] of [channel] is over and still kept by the provider. */
    fun isReplayable(channel: Channel, program: GuideProgram, now: Long): Boolean =
        channel.catchupDays > 0 && program.endMillis <= now && program.endMillis > program.startMillis &&
            program.startMillis >= now - channel.catchupDays * 24L * 60 * 60 * 1000

    /**
     * Timeshift URL of a program from the live URL of an Xtream channel
     * (`…/live/user/password/1234.ts` → `…/timeshift/user/password/<minutes>/<yyyy-MM-dd:HH-mm>/1234.ts`).
     * The start is written in the time zone of the server ([zone], else the device's one). Null when the
     * URL is not an Xtream live URL.
     */
    fun xtreamReplayUrl(liveUrl: String, startMillis: Long, endMillis: Long, zone: String?): String? {
        val url = liveUrl.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments
        val live = segments.lastIndexOf("live")
        if (live == -1 || segments.size != live + 4 || endMillis <= startMillis) return null
        val streamId = segments[live + 3].substringBeforeLast('.').takeIf { it.isNotEmpty() } ?: return null
        val serverZone = zone?.let {
            try {
                ZoneId.of(it)
            } catch (e: DateTimeException) {
                null
            }
        } ?: ZoneId.systemDefault()
        val minutes = (endMillis - startMillis + 59_999L) / 60_000L
        val start = Instant.ofEpochMilli(startMillis).atZone(serverZone).format(startFormat)
        val builder = url.newBuilder().encodedPath("/")
        segments.take(live).filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        return builder
            .addPathSegment("timeshift")
            .addPathSegment(segments[live + 1])
            .addPathSegment(segments[live + 2])
            .addPathSegment(minutes.toString())
            .addPathSegment(start)
            .addPathSegment("$streamId.ts")
            .build()
            .toString()
    }

    /**
     * Replay URL of a program of [channel] from [startMillis] to [endMillis], or null when its provider does
     * not offer one: the M3U template of the channel ([Channel.catchupMode]), else the Xtream timeshift of its
     * live URL.
     */
    fun replayUrl(channel: Channel, startMillis: Long, endMillis: Long, now: Long): String? = when (val mode = channel.catchupMode) {
        null, "xc" -> xtreamReplayUrl(channel.streamUrl, startMillis, endMillis, channel.catchupZone)
        else -> m3uReplayUrl(mode, channel.catchupSource, channel.streamUrl, startMillis, endMillis, now)
    }

    /**
     * Replay URL of an M3U channel, following the conventions of the usual IPTV players:
     * - "default": [source] is the whole URL, its placeholders filled;
     * - "append": [source] is added to the live URL;
     * - "shift": "utc" and "lutc" parameters added to the live URL;
     * - "flussonic" / "fs": the archive path of a Flussonic server.
     * Placeholders: see [fill]. Null when the URL cannot be made.
     */
    fun m3uReplayUrl(mode: String, source: String?, liveUrl: String, startMillis: Long, endMillis: Long, now: Long): String? {
        if (endMillis <= startMillis) return null
        val template = when (mode) {
            "default" -> source ?: return null
            "append" -> liveUrl + (source ?: return null)
            "shift" -> liveUrl + (if ('?' in liveUrl) "&" else "?") + "utc={utc}&lutc={lutc}"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonicTemplate(liveUrl) ?: return null
            else -> return null
        }
        return fill(template, startMillis, endMillis, now)?.takeIf { it.toHttpUrlOrNull() != null }
    }

    // .../index.m3u8 -> .../index-{utc}-{duration}.m3u8 ; .../mpegts -> .../timeshift_abs-{utc}.ts
    private fun flussonicTemplate(liveUrl: String): String? {
        val path = liveUrl.substringBefore('?')
        val query = liveUrl.substring(path.length)
        val name = path.substringAfterLast('/')
        val base = path.substringBeforeLast('/')
        return when {
            name.endsWith(".m3u8") -> base + "/" + name.removeSuffix(".m3u8") + "-{utc}-{duration}.m3u8" + query
            name == "mpegts" || name.endsWith(".ts") -> "$base/timeshift_abs-{utc}.ts$query"
            else -> null
        }
    }

    private val PLACEHOLDER = Regex("""\$?\{([A-Za-z]+)(?::([^}]*))?\}""")

    /**
     * [template] with its placeholders filled, null when one cannot be (broken date pattern):
     * {utc} / {start} / {timestamp} and {utcend} / {end} (seconds), {lutc} / {now} (seconds), {duration}
     * (seconds, {duration:60} in minutes), {offset} (seconds before now, {offset:60} in minutes),
     * {Y} {m} {d} {H} {M} {S} (start, device time), {utc:YmdHMS} / {end:...} (in a date pattern, Java or one
     * letter per field). Each also written ${...}.
     */
    internal fun fill(template: String, startMillis: Long, endMillis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val start = startMillis / 1000
        val end = endMillis / 1000
        val local = Instant.ofEpochMilli(startMillis).atZone(zone)
        var broken = false
        val filled = PLACEHOLDER.replace(template) { match ->
            val name = match.groupValues[1]
            val argument = match.groups[2]?.value
            val divider = argument?.toLongOrNull()?.takeIf { it > 0 }
            fun date(millis: Long): String = try {
                Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern(datePattern(argument.orEmpty())))
            } catch (e: IllegalArgumentException) {
                broken = true
                ""
            }
            when (name) {
                "utc", "start", "timestamp" -> if (argument != null && divider == null) date(startMillis) else start.toString()
                "utcend", "end" -> if (argument != null && divider == null) date(endMillis) else end.toString()
                "lutc", "now" -> (now / 1000).toString()
                "duration" -> ((end - start) / (divider ?: 1)).toString()
                "offset" -> ((now / 1000 - start).coerceAtLeast(0) / (divider ?: 1)).toString()
                "Y" -> "%04d".format(local.year)
                "m" -> "%02d".format(local.monthValue)
                "d" -> "%02d".format(local.dayOfMonth)
                "H" -> "%02d".format(local.hour)
                "M" -> "%02d".format(local.minute)
                "S" -> "%02d".format(local.second)
                else -> match.value
            }
        }
        return filled.takeIf { !broken }
    }

    // "YmdHMS" (one letter per field, as in the playlists) -> "yyyyMMddHHmmss"; a Java pattern is kept as it is
    private fun datePattern(pattern: String): String {
        if (pattern.isEmpty() || pattern.any { it !in "YmdHMS-:_. /" }) return pattern
        return buildString {
            pattern.forEach { char ->
                append(
                    when (char) {
                        'Y' -> "yyyy"
                        'm' -> "MM"
                        'd' -> "dd"
                        'H' -> "HH"
                        'M' -> "mm"
                        'S' -> "ss"
                        else -> char.toString()
                    }
                )
            }
        }
    }
}
