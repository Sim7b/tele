package io.github.sim7b.tele.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import io.github.sim7b.tele.data.storage.ParentalControl
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.SurfaceCard
import io.github.sim7b.tele.ui.theme.SurfaceDark
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary

/**
 * Entry of the parental code: 4 boxes, typed with the number keys of the remote, the on-screen keyboard
 * (OK on the boxes) or a phone keyboard. [onSubmit] gets the 4 digits, then the boxes are emptied;
 * [error] says why the last code was refused. Back closes it.
 */
@Composable
fun PinDialog(title: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit, subtitle: String? = null, error: String? = null) {
    var pin by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusWithRetry() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .dialogWidth(440.dp)
                .clip(shape)
                .background(SurfaceDark)
                .border(1.dp, TextPrimary.copy(alpha = 0.08f), shape)
                .padding(28.dp)
        ) {
            Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            subtitle?.let { Text(text = it, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(modifier = Modifier.height(20.dp))
            BasicTextField(
                value = pin,
                onValueChange = { typed ->
                    val digits = typed.filter { it in '0'..'9' }.take(ParentalControl.PIN_LENGTH)
                    if (digits.length == ParentalControl.PIN_LENGTH) {
                        pin = ""
                        onSubmit(digits)
                    } else {
                        pin = digits
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                textStyle = TextStyle(color = Color.Transparent),
                modifier = Modifier
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box {
                        // The typed text stays invisible: only the boxes show how many digits are in
                        Box(modifier = Modifier.size(1.dp)) { inner() }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(ParentalControl.PIN_LENGTH) { index ->
                                val current = focused && index == pin.length
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(width = 48.dp, height = 56.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(SurfaceCard)
                                        .border(2.dp, if (current) AccentCyan else Color.Transparent, RoundedCornerShape(10.dp))
                                ) {
                                    if (index < pin.length) Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(TextPrimary))
                                }
                            }
                        }
                    }
                }
            )
            error?.let { Text(text = it, fontSize = 12.sp, color = LiveRed, modifier = Modifier.padding(top = 14.dp)) }
        }
    }
}
