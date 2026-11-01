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
}
