package io.github.sim7b.tele.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.ui.theme.*
import java.time.LocalTime

/**
 * Startup welcome: "Good morning" or "Good evening" depending on the time, followed by the Dispatcharr account's first name.
 * Shown over the home screen while the guide and the favorites' artwork are loading.
 */
@Composable
fun WelcomeScreen(name: String?, modifier: Modifier = Modifier) {
    val greetingRes = remember { greetingFor(LocalTime.now()) }
    val greeting = stringResource(greetingRes.first)
    // Name part of the greeting (e.g. ", Matthieu"): the named variant minus the plain greeting
    val namePart = if (name.isNullOrBlank()) "" else stringResource(greetingRes.second, name).removePrefix(greeting)
    val appear = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
            .background(
                Brush.radialGradient(
                    colors = listOf(SurfaceCard.copy(alpha = 0.5f), Color.Transparent),
                    radius = 1100f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 18.dp.toPx()
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = TextPrimary.copy(alpha = 0.85f),
                modifier = Modifier.size(96.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = greeting,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Light,
                    color = TextPrimary
                )
                // The first name fades in if it arrives after the screen is shown (first launch)
                AnimatedVisibility(
                    visible = !name.isNullOrBlank(),
                    enter = fadeIn(tween(600)) + expandHorizontally(tween(600))
                ) {
                    Text(
                        text = namePart,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            LinearProgressIndicator(
                color = AccentCyan,
                trackColor = TextSecondary.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .width(140.dp)
                    .height(2.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.welcome_preparing_guide),
                fontSize = 13.sp,
                color = TextSecondary
            )
        }
    }
}

/** "Good morning" from 5 am to 6 pm, "Good evening" afterwards: plain greeting and greeting with the name. */
private fun greetingFor(time: LocalTime): Pair<Int, Int> =
    if (time.hour in 5..17) R.string.welcome_good_morning to R.string.welcome_good_morning_name
    else R.string.welcome_good_evening to R.string.welcome_good_evening_name
