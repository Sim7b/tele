package io.github.sim7b.tele.ui.components

import android.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.media.MediaRouteSelector
import androidx.tv.material3.Text
import com.google.android.gms.cast.CastMediaControlIntent
import io.github.sim7b.tele.R
import io.github.sim7b.tele.playback.TvPlayerManager
import io.github.sim7b.tele.ui.theme.AccentBlue
import io.github.sim7b.tele.ui.theme.TextSecondary

// Cast icon (Material "cast"), and its connected variant with the screen filled
private const val CAST_PATH = "M21,3H3C1.9,3 1,3.9 1,5v3h2V5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3z" +
    "M1,18v3h3C4,19.34 2.66,18 1,18zM1,14v2c2.76,0 5,2.24 5,5h2C8,17.13 4.87,14 1,14z" +
    "M1,10v2c4.97,0 9,4.03 9,9h2C12,14.92 7.07,10 1,10z"
private const val CAST_SCREEN_PATH = "M19,7H5v1.63c3.96,1.28 7.09,4.41 8.37,8.37H19V7z"

private fun castIcon(connected: Boolean): ImageVector =
    ImageVector.Builder(
        name = if (connected) "CastConnected" else "Cast",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(pathData = addPathNodes(CAST_PATH), fill = SolidColor(Color.Black))
        if (connected) addPath(pathData = addPathNodes(CAST_SCREEN_PATH), fill = SolidColor(Color.Black))
    }.build()

private val CastIcon by lazy { castIcon(connected = false) }
private val CastConnectedIcon by lazy { castIcon(connected = true) }

/**
 * Phone / tablet: button that picks a Chromecast (the device list of Android), or, while casting, shows
 * the Chromecast with its volume and the button that disconnects it. Nothing on a TV, nor without
 * Google Play services.
 */
@Composable
fun CastButton(playerManager: TvPlayerManager, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    if (!Device.isTouch || !playerManager.castAvailable) return
    val context = LocalContext.current
    val device by playerManager.castDevice.collectAsState()
    RoundIconButton(
        icon = if (device != null) CastConnectedIcon else CastIcon,
        contentDescription = stringResource(R.string.cast_button),
        onClick = {
            // The dialogs of Android (AppCompat), dark like the app
            val dialogContext = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
            if (device != null) {
                MediaRouteControllerDialog(dialogContext, 0).show()
            } else {
                MediaRouteChooserDialog(dialogContext, 0).apply {
                    routeSelector = MediaRouteSelector.Builder()
                        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                        .build()
                }.show()
            }
        },
        modifier = modifier,
        size = size
    )
}

/**
 * Screen of the players while the stream plays on a Chromecast: instead of the picture (or a black screen),
 * what plays, and where. The phone is the remote: the player gestures and buttons stay above it.
 */
@Composable
fun CastRemoteScreen(
    device: String,
    title: String,
    imageUrl: String?,
    hint: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1B2333), Color(0xFF07090D))))
            .padding(horizontal = 24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
            LogoPlate(
                logoUrl = imageUrl,
                name = title,
                corner = 14.dp,
                padding = 12.dp,
                modifier = Modifier.size(width = 168.dp, height = 112.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(imageVector = CastConnectedIcon, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                Text(
                    text = if (device.isNotBlank()) stringResource(R.string.cast_playing_on, device) else stringResource(R.string.cast_playing_on_device),
                    color = AccentBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = hint,
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
