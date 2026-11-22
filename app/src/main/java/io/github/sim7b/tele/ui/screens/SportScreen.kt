package io.github.sim7b.tele.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.sport.SportBoard
import io.github.sim7b.tele.data.sport.SportCompetition
import io.github.sim7b.tele.data.sport.SportEvent
import io.github.sim7b.tele.data.sport.SportFixture
import io.github.sim7b.tele.data.sport.SportSide
import io.github.sim7b.tele.data.sport.SportStanding
import io.github.sim7b.tele.ui.components.FadeInImage
import io.github.sim7b.tele.ui.components.GlassClock
import io.github.sim7b.tele.ui.components.GlassNavBar
import io.github.sim7b.tele.ui.components.LocalClock
import io.github.sim7b.tele.ui.components.NavSection
import io.github.sim7b.tele.ui.components.ScreenNav
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId

/**
 * The Sport section: a tab per followed competition (its logo), its calendar on the left (on now, coming, the last
 * results) with the channel showing each match, its standings on the right, the followed teams marked. OK on a match
 * of the guide watches it, sets its reminder or plays its replay ([onOpenEvent]). Without the Télé API plugin, the
 * matches found in the guide only.
 */
@Composable
fun SportScreen(
    competitions: List<SportCompetition>,
    loading: Boolean,
    available: Boolean,
    guideEvents: List<SportEvent>,
    followedTeams: List<List<String>>,
    hasReminder: (SportEvent) -> Boolean,
    onOpenEvent: (SportEvent) -> Unit,
    onBack: () -> Unit,
    nav: ScreenNav? = null
) {
    BackHandler(onBack = onBack)
    val colors = LocalAppColors.current
    val now = LocalClock.current.millis()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val current = competitions.getOrNull(selected.coerceAtMost((competitions.size - 1).coerceAtLeast(0)))
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(competitions.isNotEmpty()) { tabFocus.requestFocusWithRetry() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = screenMargin(48.dp), vertical = 24.dp)
    ) {
        if (nav != null) {
            Box(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                GlassNavBar(nav = nav, current = NavSection.SPORT, modifier = Modifier.align(Alignment.Center))
                GlassClock(modifier = Modifier.align(Alignment.CenterEnd))
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Text(stringResource(R.string.sport_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // The competitions, by their logos
        if (competitions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                itemsIndexed(competitions, key = { _, c -> c.topic }) { index, competition ->
                    CompetitionTab(
                        competition = competition,
                        selected = current?.topic == competition.topic,
                        onClick = { selected = index },
                        modifier = if (current?.topic == competition.topic) Modifier.focusRequester(tabFocus) else Modifier
                    )
                }
            }
        }

        when {
            current != null -> Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Calendar(current, guideEvents, followedTeams, now, hasReminder, onOpenEvent, Modifier.weight(1.15f))
                Standings(current, followedTeams, Modifier.weight(1f))
            }
            loading -> Message(stringResource(R.string.sport_loading))
            else -> Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                // Without the plugin (or before it answers): the followed matches of the guide
                GuideMatches(guideEvents, now, hasReminder, onOpenEvent, Modifier.weight(1.15f).focusRequester(tabFocus))
                Message(
                    stringResource(if (available) R.string.sport_no_data else R.string.sport_needs_plugin),
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CompetitionTab(competition: SportCompetition, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAppColors.current.accent
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
            focusedContainerColor = Color.White,
            contentColor = TextPrimary,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            competition.logo?.let { Logo(it, competition.name, 26) }
            Spacer(modifier = Modifier.width(8.dp))
            Text(competition.name, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun Logo(url: String, name: String?, size: Int) {
    FadeInImage(url = url, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.size(size.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
}

@Composable
private fun Calendar(
    competition: SportCompetition,
    guideEvents: List<SportEvent>,
    followedTeams: List<List<String>>,
    now: Long,
    hasReminder: (SportEvent) -> Boolean,
    onOpenEvent: (SportEvent) -> Unit,
    modifier: Modifier
) {
    val live = competition.fixtures.filter { it.state == SportFixture.State.LIVE }
    val coming = competition.fixtures.filter { it.state == SportFixture.State.UPCOMING && it.startMillis >= now - 3 * 3600_000L }
    val results = competition.fixtures.filter { it.state == SportFixture.State.FINISHED }.sortedByDescending { it.startMillis }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.sport_live)) }
            items(live, key = { "l" + it.id }) { FixtureRow(it, SportBoard.programFor(it, guideEvents), followedTeams, now, hasReminder, onOpenEvent) }
        }
        if (coming.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.sport_coming)) }
            items(coming, key = { "c" + it.id }) { FixtureRow(it, SportBoard.programFor(it, guideEvents), followedTeams, now, hasReminder, onOpenEvent) }
        }
        if (results.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.sport_results)) }
            items(results, key = { "r" + it.id }) { FixtureRow(it, SportBoard.programFor(it, guideEvents), followedTeams, now, hasReminder, onOpenEvent) }
        }
        if (live.isEmpty() && coming.isEmpty() && results.isEmpty()) item { Message(stringResource(R.string.sport_no_fixture)) }
    }
}

/** A match: its time (or score), its teams with their logos, and the channel showing it when the guide has it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FixtureRow(
    fixture: SportFixture,
    program: SportEvent?,
    followedTeams: List<List<String>>,
    now: Long,
    hasReminder: (SportEvent) -> Boolean,
    onOpenEvent: (SportEvent) -> Unit
) {
    val followed = SportBoard.isFollowed(followedTeams, fixture.home?.name, fixture.away?.name, fixture.home?.short, fixture.away?.short)
    val accent = LocalAppColors.current.accent
    Surface(
        onClick = { program?.let(onOpenEvent) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (followed) accent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f),
            focusedContainerColor = Color.White.copy(alpha = 0.95f),
            contentColor = TextPrimary,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column(modifier = Modifier.width(92.dp)) {
                when (fixture.state) {
                    SportFixture.State.LIVE -> Text(stringResource(R.string.player_live), color = LiveRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    else -> Text(whenLabel(fixture.startMillis, now), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
                fixture.session?.let { Text(it, fontSize = 12.sp, color = TextSecondary.copy(alpha = 0.9f)) }
            }
            if (fixture.home != null && fixture.away != null) {
                Column(modifier = Modifier.weight(1f)) {
                    TeamLine(fixture.home, fixture.state != SportFixture.State.UPCOMING)
                    TeamLine(fixture.away, fixture.state != SportFixture.State.UPCOMING)
                }
            } else {
                Text(fixture.name.orEmpty(), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            program?.let { event ->
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 10.dp).width(120.dp)) {
                    Text(event.channel.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    if (fixture.state == SportFixture.State.UPCOMING) {
                        Text(
                            stringResource(if (hasReminder(event)) R.string.sport_reminder_set else R.string.sport_reminder_hint),
                            fontSize = 11.sp, color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamLine(side: SportSide, showScore: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Box(modifier = Modifier.size(22.dp)) { side.logo?.let { Logo(it, side.name, 22) } }
        Spacer(modifier = Modifier.width(8.dp))
        Text(side.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (showScore && side.score != null) Text(side.score, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Standings(competition: SportCompetition, followedTeams: List<List<String>>, modifier: Modifier) {
    var tableIndex by rememberSaveable(competition.topic) { mutableIntStateOf(0) }
    val table = competition.tables.getOrNull(tableIndex) ?: competition.tables.firstOrNull()
    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.sport_standings))
        if (competition.tables.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                itemsIndexed(competition.tables) { index, item ->
                    SmallChip(item.name ?: (index + 1).toString(), index == tableIndex) { tableIndex = index }
                }
            }
        }
        if (table == null) {
            Message(stringResource(R.string.sport_no_standings))
            return@Column
        }
        val withRecord = table.rows.any { it.record != null }
        StandingHeader(withRecord)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(table.rows, key = { it.rank.toString() + it.name }) { row ->
                StandingRow(row, withRecord, SportBoard.isFollowed(followedTeams, row.name, row.short))
            }
        }
    }
}

@Composable
private fun RowScope.Cell(text: String?, width: Int, bold: Boolean = false, color: Color = Color.Unspecified) {
    Text(text ?: "", fontSize = 13.sp, textAlign = TextAlign.End, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color, maxLines = 1, modifier = Modifier.width(width.dp))
}

@Composable
private fun StandingHeader(withRecord: Boolean) {
    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text("#", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(28.dp))
        Spacer(modifier = Modifier.width(30.dp))
        Text(stringResource(R.string.sport_team), fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
        Cell(stringResource(R.string.sport_played_short), 34, color = TextSecondary)
        if (withRecord) Cell(stringResource(R.string.sport_record_short), 70, color = TextSecondary)
        Cell(stringResource(R.string.sport_points_short), 48, color = TextSecondary)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StandingRow(row: SportStanding, withRecord: Boolean, followed: Boolean) {
    val accent = LocalAppColors.current.accent
    // Focusable: the remote scrolls the table row by row
    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (followed) accent.copy(alpha = 0.22f) else Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.95f),
            contentColor = TextPrimary,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(row.rank.takeIf { it > 0 }?.toString().orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Box(modifier = Modifier.size(22.dp)) { row.logo?.let { Logo(it, row.name, 22) } }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                row.name + if (followed) " ★" else "",
                fontSize = 14.sp, fontWeight = if (followed) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Cell(row.played, 34)
            if (withRecord) Cell(row.record, 70)
            Cell(row.points, 48, bold = true)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAppColors.current.accent
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
            focusedContainerColor = Color.White,
            contentColor = TextPrimary,
            focusedContentColor = Color.Black
        )
    ) {
        Text(label, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

/** The followed matches found in the guide (without the plugin). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideMatches(events: List<SportEvent>, now: Long, hasReminder: (SportEvent) -> Boolean, onOpenEvent: (SportEvent) -> Unit, modifier: Modifier) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionTitle(stringResource(R.string.sport_in_guide)) }
        if (events.isEmpty()) item { Message(stringResource(R.string.sport_no_fixture)) }
        items(events, key = { it.channel.id + it.program.startMillis }) { event ->
            Surface(
                onClick = { onOpenEvent(event) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.05f),
                    focusedContainerColor = Color.White.copy(alpha = 0.95f),
                    contentColor = TextPrimary,
                    focusedContentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val program = event.program
                    Text(
                        if (program.startMillis <= now && program.endMillis > now) stringResource(R.string.player_live) else whenLabel(program.startMillis, now),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(92.dp)
                    )
                    Text(program.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(130.dp)) {
                        Text(event.channel.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (hasReminder(event)) Text(stringResource(R.string.sport_reminder_set), fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 15.sp, color = TextSecondary, modifier = modifier.padding(vertical = 12.dp))
}

/** "21:00" today, "Tomorrow 18:30", "On the 9 at 20:45". */
@Composable
private fun whenLabel(start: Long, now: Long): String {
    val clock = EpgResolver.formatClock(start)
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> clock
        today.plusDays(1) -> stringResource(R.string.sport_tomorrow, clock)
        else -> stringResource(R.string.sport_later, day.dayOfMonth, clock)
    }
}
