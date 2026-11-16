package io.github.sim7b.tele.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.storage.Profile
import io.github.sim7b.tele.ui.components.PinDialog
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.LocalAppColors
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/** Displayed name of a profile: the main one has none until it is renamed. */
@Composable
fun profileName(profile: Profile): String =
    profile.name.ifEmpty { stringResource(R.string.profile_main) }

/**
 * "Who is watching?": the profiles of the TV, the one used last focused. A profile with a code asks it
 * ([checkPin], [waitMs] after too many wrong codes); [onSelect] gets the profile opened. Back keeps the current one.
 */
@Composable
fun ProfilePicker(
    profiles: List<Profile>,
    current: Profile,
    checkPin: (Profile, String) -> Boolean,
    waitMs: () -> Long,
    onSelect: (Profile) -> Unit,
    onDismiss: () -> Unit,
    // Out of a kids profile to one without a code: the parental code (null: not asked)
    checkParental: ((String) -> Boolean)? = null,
    title: String = stringResource(R.string.profile_picker_title)
) {
    val focus = remember { FocusRequester() }
    var asking by remember { mutableStateOf<Profile?>(null) }
    // The parental code is asked instead of the code of the profile
    var parentalAsked by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { focus.requestFocusWithRetry() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ProfileChoice(profiles, current, title, focus) { profile ->
            val leavingKids = checkParental != null && current.kids && !profile.hasPin
            if (profile.id != current.id && (profile.hasPin || leavingKids)) {
                pinError = null
                parentalAsked = !profile.hasPin
                asking = profile
            } else {
                onSelect(profile)
            }
        }
    }

    asking?.let { profile ->
        val resources = LocalContext.current.resources
        val wrongPin = stringResource(R.string.parental_wrong_pin)
        PinDialog(
            title = if (parentalAsked) stringResource(R.string.parental_enter_pin) else stringResource(R.string.profile_enter_pin, profileName(profile)),
            error = pinError,
            onSubmit = { pin ->
                val opened = if (parentalAsked) checkParental?.invoke(pin) ?: true else checkPin(profile, pin)
                if (opened) {
                    asking = null
                    onSelect(profile)
                } else {
                    val wait = waitMs()
                    pinError = if (wait > 0) resources.getString(R.string.parental_wait, (wait + 999) / 1000) else wrongPin
                }
            },
            onDismiss = { asking = null }
        )
    }
}

/** The profiles of the TV, as cards: [onClick] gets the chosen one. */
@Composable
internal fun ProfileChoice(profiles: List<Profile>, current: Profile, title: String, focus: FocusRequester, onClick: (Profile) -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onClick = { onClick(profile) },
                        modifier = if (profile.id == current.id) Modifier.focusRequester(focus) else Modifier
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileCard(profile: Profile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = LocalAppColors.current.accent
    val name = profileName(profile)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
        Surface(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = avatarColor(profile.id),
                focusedContainerColor = avatarColor(profile.id)
            ),
            modifier = modifier.size(120.dp).border(3.dp, if (focused) accent else Color.Transparent, CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = name.take(1).uppercase(),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (profile.hasPin) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(18.dp)
                    )
                }
            }
        }
        Text(
            text = name,
            fontSize = 16.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            color = if (focused) TextPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp)
        )
        if (profile.kids) {
            Text(
                text = stringResource(R.string.profile_kids),
                fontSize = 12.sp,
                color = accent,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

private val AVATAR_COLORS = listOf(
    Color(0xFF1E88E5), Color(0xFFD81B60), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFE53935), Color(0xFF5E35B1)
)

private fun avatarColor(id: String): Color = AVATAR_COLORS[Math.floorMod(id.hashCode(), AVATAR_COLORS.size)]
