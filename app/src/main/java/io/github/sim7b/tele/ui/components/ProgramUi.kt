package io.github.sim7b.tele.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.epg.GuideTimeline
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.ui.theme.AccentCyan
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Current time, refreshed every [periodMs]: progress bars and remaining times move on their own. */
@Composable
fun rememberNow(periodMs: Long = 30_000L): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** "23 min" or "1 h 10". */
@Composable
fun durationLabel(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.vod_duration_hours, minutes / 60, minutes % 60)
    else stringResource(R.string.vod_duration_minutes, minutes)

/** Day of [time] seen from [now]: "Today", "Tomorrow", "Yesterday", else the weekday and its date. */
@Composable
fun dayLabel(time: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    return when (GuideTimeline.dayOffset(time, now, zone)) {
        0 -> stringResource(R.string.epg_today)
        1 -> stringResource(R.string.epg_tomorrow)
        -1 -> stringResource(R.string.epg_yesterday)
        else -> {
            val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
            Instant.ofEpochMilli(time).atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEEE d", locale))
                .replaceFirstChar { it.titlecase(locale) }
        }
    }
}

/** "23 min left" for the program on air, "in 1 h 10" for a coming one, null once it is over. */
@Composable
fun programWhen(program: GuideProgram, now: Long): String? = when {
    program.endMillis <= now -> null
    program.startMillis <= now -> stringResource(R.string.epg_time_left, durationLabel(GuideTimeline.minutesBetween(now, program.endMillis)))
    else -> stringResource(R.string.epg_starts_in, durationLabel(GuideTimeline.minutesBetween(now, program.startMillis)))
}

/** "20:50 – 22:40", with its day first when [withDay] and it is not today ("Tomorrow · 08:00 – 09:00"). */
@Composable
fun programHours(program: GuideProgram, now: Long, withDay: Boolean): String {
    val hours = "${EpgResolver.formatClock(program.startMillis)} – ${EpgResolver.formatClock(program.endMillis)}"
    val today = GuideTimeline.dayOffset(program.startMillis, now, ZoneId.systemDefault()) == 0
    return if (withDay && !today) "${dayLabel(program.startMillis, now)}  ·  $hours" else hours
}

/**
 * Details of a program: title, day and hours, time left or before it starts, category, synopsis,
 * and its picture when the guide has one. Colors given by the caller (over the video or on the theme).
 */
@Composable
fun ProgramDetails(
    program: GuideProgram,
    now: Long,
    primary: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
    descriptionLines: Int = 4,
    showPoster: Boolean = true,
    titleSize: Int = 17,
    // Short marks before the category: replay available, reminder set
    badges: List<String> = emptyList()
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = program.title,
                fontSize = titleSize.sp,
                fontWeight = FontWeight.SemiBold,
                color = primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            val timing = listOfNotNull(programHours(program, now, withDay = true), programWhen(program, now))
            Text(
                text = timing.joinToString("  ·  "),
                fontSize = 12.sp,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (program.startMillis <= now && now < program.endMillis) {
                Spacer(modifier = Modifier.height(6.dp))
                ProgressLine(
                    progress = EpgResolver.progress(program.startMillis, program.endMillis, now),
                    trackColor = secondary.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth(0.6f)
                )
            }
            val category = program.category ?: program.rating
            if (!category.isNullOrBlank() || badges.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    badges.forEach { badge ->
                        Tag(text = badge, color = AccentCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (!category.isNullOrBlank()) {
                        Text(
                            text = category.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (!program.description.isNullOrBlank() && descriptionLines > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = program.description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = primary.copy(alpha = 0.8f),
                    maxLines = descriptionLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showPoster && !program.posterUrl.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(12.dp))
            FadeInImage(
                url = program.posterUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(128.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}
