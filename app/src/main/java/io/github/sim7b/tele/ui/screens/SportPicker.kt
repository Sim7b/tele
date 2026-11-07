package io.github.sim7b.tele.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.sport.SportCatalog
import io.github.sim7b.tele.data.sport.SportTopic
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.SelectionDot
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.vod.NameDialog

/** The followed entries as shown: the names of the topics, the words typed. */
fun followedSportLabel(entries: List<String>): String =
    entries.joinToString(", ") { SportCatalog.topicOf(it)?.name ?: it }

/**
 * Choice of the sport to follow from the catalog: sports, then countries or competitions, then teams. OK on a
 * row with more opens it, on a team follows it; "Follow …" at the top follows the whole level (what it holds
 * comes along). Words of one's own can also be typed. Back goes up a level, then closes.
 */
@Composable
fun SportPickerDialog(followed: List<String>, onChange: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(emptyList<SportTopic>()) }
    var typing by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val followedSet = followed.toSet()
    fun isFollowed(topic: SportTopic) = SportCatalog.entry(topic) in followedSet
    fun toggle(topic: SportTopic) {
        val entry = SportCatalog.entry(topic)
        onChange(if (entry in followedSet) followed - entry else followed + entry)
    }
    // Followed itself or by one of the topics above it
    fun covered(topic: SportTopic): Boolean = isFollowed(topic) || path.any { isFollowed(it) }
    fun followsInside(topic: SportTopic): Boolean = topic.children.any { isFollowed(it) || followsInside(it) }

    LaunchedEffect(path) { firstFocus.requestFocusWithRetry() }

    if (typing) {
        val typed = followed.filter { SportCatalog.topicOf(it) == null }
        NameDialog(
            title = stringResource(R.string.sport_free_words),
            initial = typed.joinToString(", "),
            confirm = stringResource(R.string.vod_collection_save),
            hint = stringResource(R.string.settings_followed_sport_hint),
            maxLength = 600,
            onConfirm = { text ->
                val words = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                onChange(followed.filter { SportCatalog.topicOf(it) != null } + words)
                typing = false
            },
            onDismiss = { typing = false }
        )
        return
    }

    val current = path.lastOrNull()
    val topics = current?.children ?: SportCatalog.topics
    TransferDialogFrame(
        title = path.joinToString(" › ") { it.name }.ifEmpty { stringResource(R.string.settings_followed_sport) },
        onDismiss = { if (path.isNotEmpty()) path = path.dropLast(1) else onDismiss() }
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
            if (current == null) {
                item {
                    val typed = followed.filter { SportCatalog.topicOf(it) == null }
                    MenuRow(
                        label = stringResource(R.string.sport_free_words),
                        subtitle = typed.joinToString(", ").ifEmpty { null },
                        icon = Icons.Filled.Edit,
                        onClick = { typing = true },
                        modifier = Modifier.focusRequester(firstFocus)
                    )
                }
            } else {
                item {
                    MenuRow(
                        label = stringResource(R.string.sport_follow_all, current.name),
                        leading = { focused -> SelectionDot(selected = covered(current), focused = focused) },
                        onClick = { toggle(current) },
                        modifier = Modifier.focusRequester(firstFocus)
                    )
                }
            }
            itemsIndexed(topics, key = { _, topic -> topic.id }) { _, topic ->
                if (topic.children.isEmpty()) {
                    MenuRow(
                        label = topic.name,
                        leading = { focused -> SelectionDot(selected = covered(topic), focused = focused) },
                        onClick = { toggle(topic) }
                    )
                } else {
                    val state = when {
                        covered(topic) -> stringResource(R.string.sport_followed)
                        followsInside(topic) -> stringResource(R.string.sport_followed_inside)
                        else -> null
                    }
                    MenuRow(
                        label = topic.name,
                        subtitle = listOfNotNull(pluralStringResource(R.plurals.sport_items, topic.children.size, topic.children.size), state).joinToString(" · "),
                        trailing = { Icon(imageVector = Icons.Filled.KeyboardArrowRight, contentDescription = null) },
                        onClick = { path = path + topic }
                    )
                }
            }
        }
    }
}
