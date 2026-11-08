package io.github.sim7b.tele.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.vod.VodKind
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Dark text on the white of a focused glass element. */
val GlassInk = Color(0xFF0B0D12)

/** True inside the screens drawn in the immersive style (glass over pictures), see [ScreenNav]. */
val LocalImmersive = staticCompositionLocalOf { false }

/** Section shown, marked in the glass bar. */
enum class NavSection { HOME, GUIDE, MOVIES, SERIES }

/**
 * The screens reachable from the glass bar at the top of the immersive screens (home, guide, movies and series):
 * a screen given one is drawn in the immersive style. Null entries are not shown (no guide, no movies...).
 */
class ScreenNav(
    val onHome: () -> Unit,
    val onGuide: (() -> Unit)?,
    val onVod: ((VodKind) -> Unit)?,
    val onRecordings: (() -> Unit)?,
    val onSearch: () -> Unit,
    val onSettings: () -> Unit,
    val updateAvailable: Boolean = false
)

/** Glass of the tabs, buttons and panels: a light veil with a bright edge (the picture behind stays visible). */
fun Modifier.glass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Color(0x33141A24))
    .background(Color.White.copy(alpha = 0.10f))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.08f))), shape)

/**
 * The sections in a glass bar: [current] marked, [extra] tabs after the series (the collections of the movies
 * and series), then search and settings.
 */
@Composable
fun GlassNavBar(
    nav: ScreenNav,
    current: NavSection?,
    modifier: Modifier = Modifier,
    // Given to the tab of [current], to bring the focus back to it
    currentFocus: FocusRequester? = null,
    extra: @Composable RowScope.() -> Unit = {}
) {
    fun at(section: NavSection) = if (section == current && currentFocus != null) Modifier.focusRequester(currentFocus) else Modifier
    Row(
        modifier = modifier.glass(RoundedCornerShape(50)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassTab(label = stringResource(R.string.side_menu_home), selected = current == NavSection.HOME, onClick = nav.onHome, modifier = at(NavSection.HOME))
        nav.onGuide?.let { GlassTab(label = stringResource(R.string.home_open_guide), selected = current == NavSection.GUIDE, onClick = it, modifier = at(NavSection.GUIDE)) }
        nav.onVod?.let { openVod ->
            GlassTab(label = stringResource(R.string.vod_tab_movies), selected = current == NavSection.MOVIES, onClick = { openVod(VodKind.MOVIE) }, modifier = at(NavSection.MOVIES))
            GlassTab(label = stringResource(R.string.vod_tab_series), selected = current == NavSection.SERIES, onClick = { openVod(VodKind.SERIES) }, modifier = at(NavSection.SERIES))
        }
        extra()
        nav.onRecordings?.let { GlassTab(label = stringResource(R.string.recordings_screen_title), onClick = it) }
        GlassTab(icon = Icons.Filled.Search, label = stringResource(R.string.home_search), onClick = nav.onSearch)
        GlassTab(
            icon = Icons.Filled.Settings,
            label = stringResource(if (nav.updateAvailable) R.string.home_settings_update else R.string.home_settings),
            dot = nav.updateAvailable,
            onClick = nav.onSettings
        )
    }
}

/** A tab of the glass bar: white when focused, lighter when it is the screen shown. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlassTab(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    icon: ImageVector? = null,
    dot: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp).touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = GlassInk
        )
    ) {
        Box(modifier = Modifier.fillMaxHeight().padding(horizontal = if (icon != null) 12.dp else 18.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp))
                if (dot) Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp).size(6.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
            } else {
                Text(text = label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** The time, alone, in a corner. */
@Composable
fun GlassClock(modifier: Modifier = Modifier) {
    val time by produceState(initialValue = LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(5_000)
        }
    }
    Text(
        text = remember(time.hour, time.minute) { time.format(DateTimeFormatter.ofPattern("HH:mm")) },
        fontSize = 20.sp,
        fontWeight = FontWeight.Light,
        color = Color.White,
        modifier = modifier
    )
}

/**
 * Glass menu on the left of an immersive screen, opened by Left at the start of a row: the lists of the screen
 * shown (rows of the home, of the movies or the series, categories of the guide), [current] focused first.
 * Right or Back closes it (Back is handled by the screen).
 */
@Composable
fun ListsMenu(
    title: String,
    entries: List<Pair<String, String>>,
    current: Int,
    focus: FocusRequester,
    onPick: (index: Int, key: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    LazyColumn(
        state = listState,
        modifier = modifier
            .padding(start = 18.dp, top = 18.dp, bottom = 18.dp)
            .fillMaxHeight()
            .width(ListsMenuWidth)
            .background(Color(0xF20B0E14), shape)
            .glass(shape)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                letterSpacing = 1.4.sp,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp)
            )
        }
        itemsIndexed(entries, key = { _, entry -> entry.first }) { index, (key, name) ->
            MenuRow(
                label = name,
                onClick = { onPick(index, key) },
                modifier = if (index == current.coerceIn(0, entries.lastIndex)) Modifier.focusRequester(focus) else Modifier
            )
        }
    }
}

private val ListsMenuWidth = 300.dp
