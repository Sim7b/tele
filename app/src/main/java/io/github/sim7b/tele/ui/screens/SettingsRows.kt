package io.github.sim7b.tele.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.dialogWidth
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.components.touchClick
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.BackgroundDark
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.SurfaceDark
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/** Title of a group of settings, with an optional line under it. */
@Composable
internal fun SettingGroup(title: String, caption: String? = null) {
    Column(modifier = Modifier.padding(top = 14.dp, start = 4.dp, bottom = 2.dp)) {
        SectionLabel(text = title)
        caption?.let {
            Text(text = it, fontSize = 11.sp, color = TextSecondary.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * One setting: its name (and a line on it), its current value on the right, and › when OK opens its
 * choices. [toggle] draws a switch instead (OK flips it).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    description: String? = null,
    icon: ImageVector? = null,
    swatch: Color? = null,
    toggle: Boolean? = null,
    chevron: Boolean = toggle == null,
    destructive: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val focusedContent = if (destructive) Color.White else LocalAppColors.current.background
    val contentColor = if (isFocused) focusedContent else TextPrimary
    val secondaryColor = if (isFocused) focusedContent.copy(alpha = 0.7f) else TextSecondary

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().touchClick(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceDark,
            focusedContainerColor = if (destructive) LiveRed else TextPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = if (description != null) 11.dp else 14.dp)
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = if (isFocused) contentColor else secondaryColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (destructive && !isFocused) LiveRed else contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                description?.let {
                    Text(text = it, fontSize = 11.sp, color = secondaryColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
            }
            if (swatch != null) {
                Spacer(modifier = Modifier.width(10.dp))
                Swatch(swatch)
            }
            if (toggle != null) {
                Spacer(modifier = Modifier.width(14.dp))
                Switch(on = toggle, focused = isFocused)
            }
            if (chevron) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(imageVector = Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = secondaryColor, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, TextSecondary.copy(alpha = 0.5f), CircleShape)
    )
}

@Composable
private fun Switch(on: Boolean, focused: Boolean) {
    val track = when {
        on -> AccentCyan
        focused -> BackgroundDark.copy(alpha = 0.3f)
        else -> TextSecondary.copy(alpha = 0.35f)
    }
    Box(
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier
            .size(width = 36.dp, height = 20.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .padding(3.dp)
    ) {
        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color.White))
    }
}

/** How the choices of a panel behave: one value (closes), several values, or actions (close). */
internal enum class ChoiceStyle { SINGLE, MULTIPLE, ACTIONS }

internal class Choice(
    val label: String,
    val onClick: () -> Unit,
    val selected: Boolean = false,
    val description: String? = null,
    val swatch: Color? = null,
    val trailing: String? = null,
    val icon: ImageVector? = null,
    val destructive: Boolean = false
)

/**
 * Choices of a setting, in a panel on the right side of the screen: opens on the current value, Back
 * closes it. [onSelectAll] / [onSelectNone]: buttons above several values.
 */
@Composable
internal fun ChoicePanel(
    title: String,
    choices: List<Choice>,
    onDismiss: () -> Unit,
    style: ChoiceStyle = ChoiceStyle.SINGLE,
    description: String? = null,
    onSelectAll: (() -> Unit)? = null,
    onSelectNone: (() -> Unit)? = null
) {
    val start = if (style == ChoiceStyle.SINGLE) choices.indexOfFirst { it.selected }.coerceAtLeast(0) else 0
    val startFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    LaunchedEffect(Unit) { startFocus.requestFocusWithRetry(attempts = 20) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Touch: a tap beside the panel closes it, one on the panel stays there
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .pointerInput(Unit) { detectTapGestures { } }
                    .dialogWidth(460.dp)
                    .background(SurfaceDark)
                    .padding(start = 28.dp, end = 28.dp, top = 32.dp)
            ) {
                Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                description?.let {
                    Text(text = it, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(modifier = Modifier.padding(top = 16.dp))
                if (onSelectAll != null && onSelectNone != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            ChoiceRow(Choice(stringResource(R.string.settings_select_all), onSelectAll, icon = Icons.Filled.Check), ChoiceStyle.ACTIONS)
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            ChoiceRow(Choice(stringResource(R.string.settings_select_none), onSelectNone, icon = Icons.Filled.Close), ChoiceStyle.ACTIONS)
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(choices) { index, choice ->
                        ChoiceRow(
                            choice = choice,
                            style = style,
                            onPicked = {
                                when (style) {
                                    ChoiceStyle.SINGLE -> {
                                        choice.onClick()
                                        onDismiss()
                                    }
                                    // An action may open a dialog of its own: the panel goes first
                                    ChoiceStyle.ACTIONS -> {
                                        onDismiss()
                                        choice.onClick()
                                    }
                                    ChoiceStyle.MULTIPLE -> choice.onClick()
                                }
                            },
                            modifier = if (index == start) Modifier.focusRequester(startFocus) else Modifier
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChoiceRow(choice: Choice, style: ChoiceStyle, modifier: Modifier = Modifier, onPicked: () -> Unit = choice.onClick) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val focusedContent = if (choice.destructive) Color.White else LocalAppColors.current.background
    val contentColor = if (isFocused) focusedContent else TextPrimary
    val secondaryColor = if (isFocused) focusedContent.copy(alpha = 0.7f) else TextSecondary
    Surface(
        onClick = onPicked,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().touchClick(onPicked),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = if (choice.destructive) LiveRed else TextPrimary
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            when {
                choice.icon != null -> Icon(imageVector = choice.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                style == ChoiceStyle.SINGLE -> Radio(choice.selected, isFocused)
                style == ChoiceStyle.MULTIPLE -> CheckBox(choice.selected, isFocused)
                else -> Unit
            }
            if (choice.icon != null || style != ChoiceStyle.ACTIONS) Spacer(modifier = Modifier.width(14.dp))
            if (choice.swatch != null) {
                Swatch(choice.swatch)
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = choice.label,
                    fontSize = 14.sp,
                    fontWeight = if (choice.selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (choice.destructive && !isFocused) LiveRed else contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                choice.description?.let {
                    Text(text = it, fontSize = 11.sp, color = secondaryColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            choice.trailing?.let {
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = it, fontSize = 12.sp, color = secondaryColor, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Radio(selected: Boolean, focused: Boolean) {
    val color = if (focused) LocalAppColors.current.background else if (selected) AccentCyan else TextSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(18.dp).clip(CircleShape).border(2.dp, color, CircleShape)
    ) {
        if (selected) Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun CheckBox(checked: Boolean, focused: Boolean) {
    val color = if (focused) LocalAppColors.current.background else if (checked) AccentCyan else TextSecondary
    val shape = RoundedCornerShape(4.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(18.dp)
            .clip(shape)
            .background(if (checked) color else Color.Transparent)
            .border(2.dp, color, shape)
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.settings_selected),
                tint = if (focused) TextPrimary else BackgroundDark,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
