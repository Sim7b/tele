package com.iptv.player.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.ui.theme.*
import java.time.LocalTime

/**
 * Accueil au démarrage : "Bonjour" ou "Bonsoir" selon l'heure, suivi du prénom du compte Dispatcharr.
 * Affiché par-dessus l'accueil pendant le chargement du guide et des visuels des favoris.
 */
@Composable
fun WelcomeScreen(name: String?, modifier: Modifier = Modifier) {
    val greeting = remember { greetingFor(LocalTime.now()) }
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
                // Le prénom apparaît en douceur s'il arrive après l'affichage (premier lancement)
                AnimatedVisibility(
                    visible = !name.isNullOrBlank(),
                    enter = fadeIn(tween(600)) + expandHorizontally(tween(600))
                ) {
                    Text(
                        text = " ${name.orEmpty()}",
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
                text = "Préparation de votre guide",
                fontSize = 13.sp,
                color = TextSecondary
            )
        }
    }
}

/** "Bonjour" de 5 h à 18 h, "Bonsoir" ensuite. */
private fun greetingFor(time: LocalTime): String = if (time.hour in 5..17) "Bonjour" else "Bonsoir"
