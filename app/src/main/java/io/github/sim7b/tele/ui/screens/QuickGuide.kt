package io.github.sim7b.tele.ui.screens

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/** One page of the guide: an icon, a title and a few short lines. */
private class GuidePage(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val lines: List<Int>)

private val PAGES = listOf(
    GuidePage(Icons.Filled.Star, R.string.guide_welcome_title, listOf(R.string.guide_welcome_1, R.string.guide_welcome_2)),
    GuidePage(Icons.Filled.Home, R.string.guide_home_title, listOf(R.string.guide_home_1, R.string.guide_home_2, R.string.guide_home_3)),
    GuidePage(
        Icons.Filled.PlayArrow,
        R.string.guide_player_title,
        listOf(R.string.guide_player_1, R.string.guide_player_2, R.string.guide_player_3, R.string.guide_player_4)
    ),
    GuidePage(Icons.Filled.Search, R.string.guide_search_title, listOf(R.string.guide_search_1, R.string.guide_search_2, R.string.guide_search_3))
)

/**
 * Quick start guide, shown once after the first welcome screen and from the settings: a few pages
 * driven by the remote (→ or OK: next, ←: previous, Back: close).
 */
@Composable
fun QuickGuide(onClose: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val accent = LocalAppColors.current.accent

    LaunchedEffect(Unit) { focus.requestFocusWithRetry() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(LocalAppColors.current.background)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (page < PAGES.lastIndex) page++ else onClose()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (page > 0) page--
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 760.dp)
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState > initialState
                        (fadeIn() + slideInHorizontally { if (forward) it / 8 else -it / 8 }) togetherWith
                            (fadeOut() + slideOutHorizontally { if (forward) -it / 8 else it / 8 })
                    },
                    label = "guidePage"
                ) { index ->
                    val current = PAGES[index]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.16f))
                        ) {
                            Icon(imageVector = current.icon, contentDescription = null, tint = accent, modifier = Modifier.size(34.dp))
                        }
                        Spacer(modifier = Modifier.height(22.dp))
                        Text(
                            text = stringResource(current.title),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(22.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            current.lines.forEach { line ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 9.dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(accent)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(text = stringResource(line), fontSize = 17.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
                // Progress dots: the current page is a wider accent pill
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PAGES.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (index == page) 22.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (index == page) accent else TextSecondary.copy(alpha = 0.35f))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = stringResource(if (page < PAGES.lastIndex) R.string.guide_hint_next else R.string.guide_hint_last),
                    fontSize = 13.sp,
                    color = TextSecondary.copy(alpha = 0.75f)
                )
            }
        }
    }
}
