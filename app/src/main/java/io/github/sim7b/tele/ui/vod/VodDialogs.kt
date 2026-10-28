package io.github.sim7b.tele.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.SelectionDot
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.SurfaceCard
import io.github.sim7b.tele.ui.theme.SurfaceDark
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/** What a long press on a title can change (collections, continue watching, watched). */
class VodTitleActions(
    val toggleMyList: (titleKey: String) -> Unit,
    val toggleInCollection: (collectionId: String, titleKey: String) -> Unit,
    val createCollectionWith: (name: String, titleKey: String) -> Unit,
    val removeFromContinue: (titleKey: String) -> Unit,
    val setWatched: (title: VodTitle, watched: Boolean) -> Unit
)

/**
 * Actions on a title (hold OK on its poster): open it, add it to a collection, take it out of
 * "Continue watching" or of the row it is shown in ([removeFrom]), mark a movie watched or unwatched.
 */
@Composable
internal fun TitleActionsDialog(
    title: VodTitle,
    inContinue: Boolean,
    watched: Boolean?,
    removeFrom: String?,
    onOpen: () -> Unit,
    onAddTo: () -> Unit,
    onRemoveContinue: () -> Unit,
    onToggleWatched: () -> Unit,
    onRemoveFrom: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWithRetry() }
    fun run(action: () -> Unit) {
        onDismiss()
        action()
    }
    VodDialogFrame(title = title.title, onDismiss = onDismiss) {
        MenuRow(label = stringResource(R.string.vod_menu_open), icon = Icons.Filled.PlayArrow, onClick = { run(onOpen) }, modifier = Modifier.focusRequester(firstFocus))
        MenuRow(label = stringResource(R.string.vod_add_to), icon = Icons.Filled.Add, onClick = { run(onAddTo) })
        if (removeFrom != null) {
            MenuRow(label = stringResource(R.string.vod_menu_remove_from, removeFrom), icon = Icons.Filled.Delete, onClick = { run(onRemoveFrom) })
        }
        if (inContinue) {
            MenuRow(label = stringResource(R.string.vod_menu_remove_continue), icon = Icons.Filled.Close, onClick = { run(onRemoveContinue) })
        }
        if (watched != null) {
            MenuRow(
                label = stringResource(if (watched) R.string.vod_menu_mark_unwatched else R.string.vod_menu_mark_watched),
                icon = Icons.Filled.Check,
                onClick = { run(onToggleWatched) }
            )
        }
    }
}

/** Frame of the movies and series dialogs: title, optional subtitle, content. */
@Composable
internal fun VodDialogFrame(title: String, onDismiss: () -> Unit, subtitle: String? = null, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceDark)
                .padding(horizontal = 26.dp, vertical = 22.dp)
        ) {
            Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            subtitle?.let { Text(text = it, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/** One value to choose among [options] (with their number of titles); the first row clears it. */
@Composable
internal fun <T> FilterDialog(
    title: String,
    options: List<Pair<T, Int>>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T?) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    allowNone: Boolean = true
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWithRetry() }
    VodDialogFrame(title = title, subtitle = subtitle, onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            if (allowNone) {
                item {
                    MenuRow(
                        label = stringResource(R.string.vod_filter_any),
                        onClick = { onSelect(null) },
                        leading = { focused -> SelectionDot(selected = selected == null, focused = focused) },
                        modifier = Modifier.focusRequester(firstFocus)
                    )
                }
            }
            items(options, key = { it.first.toString() }) { (value, count) ->
                MenuRow(
                    label = label(value),
                    subtitle = pluralStringResource(R.plurals.vod_titles_count, count, count),
                    onClick = { onSelect(value) },
                    leading = { focused -> SelectionDot(selected = value == selected, focused = focused) },
                    modifier = if (!allowNone && value == options.first().first) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }
}

/**
 * Collections a title is in: "my list" and the user's collections, each toggled by OK, and a new
 * collection (the title is added to it).
 */
@Composable
internal fun CollectionPickerDialog(
    collections: List<VodCollection>,
    titleKey: String,
    inMyList: Boolean,
    onToggleMyList: () -> Unit,
    onToggle: (collectionId: String) -> Unit,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var naming by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(naming) { if (!naming) firstFocus.requestFocusWithRetry() }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.vod_collection_new),
            initial = "",
            confirm = stringResource(R.string.vod_collection_create),
            onConfirm = { name ->
                onCreate(name)
                naming = false
            },
            onDismiss = { naming = false }
        )
        return
    }
    VodDialogFrame(title = stringResource(R.string.vod_collections_title), onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            item {
                MenuRow(
                    label = stringResource(R.string.vod_row_my_list),
                    onClick = onToggleMyList,
                    leading = { focused -> SelectionDot(selected = inMyList, focused = focused) },
                    modifier = Modifier.focusRequester(firstFocus)
                )
            }
            items(collections, key = { it.id }) { collection ->
                MenuRow(
                    label = collection.name,
                    subtitle = pluralStringResource(R.plurals.vod_collection_titles, collection.titleKeys.size, collection.titleKeys.size),
                    onClick = { onToggle(collection.id) },
                    leading = { focused -> SelectionDot(selected = titleKey in collection.titleKeys, focused = focused) }
                )
            }
            item { MenuRow(label = stringResource(R.string.vod_collection_new), icon = Icons.Filled.Add, onClick = { naming = true }) }
            item { MenuRow(label = stringResource(R.string.vod_close), icon = Icons.Filled.Check, onClick = onDismiss) }
        }
    }
}

/** Name of a collection, typed with the TV keyboard. */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocusWithRetry() }
    val accent = LocalAppColors.current.accent
    val shape = RoundedCornerShape(10.dp)
    fun submit() {
        if (name.isNotBlank()) onConfirm(name.trim())
    }
    VodDialogFrame(title = title, onDismiss = onDismiss) {
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 17.sp),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(SurfaceCard)
                        .border(2.dp, if (focused) accent else SurfaceCard, shape)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (name.isEmpty()) Text(text = stringResource(R.string.vod_collection_name_hint), fontSize = 17.sp, color = TextSecondary.copy(alpha = 0.6f))
                    inner()
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        MenuRow(label = confirm, icon = Icons.Filled.Check, onClick = { submit() })
        MenuRow(label = stringResource(R.string.vod_close), icon = Icons.Filled.Close, onClick = onDismiss)
    }
}
