package com.iptv.player.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.iptv.player.ui.theme.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Petits éléments visuels partagés : palette sobre, accent réservé aux indicateurs, focus en blanc.
 */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun focusRing(shape: Shape, width: Dp = 2.dp): ClickableSurfaceBorder =
    ClickableSurfaceDefaults.border(
        focusedBorder = Border(BorderStroke(width, FocusBorderColor), shape = shape)
    )

/** Image réseau avec fondu enchaîné à l'apparition. */
@Composable
fun FadeInImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f
) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).crossfade(250).build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alpha = alpha
    )
}

/**
 * Teinte des logos déjà analysés (true = logo clair, à poser sur une plaque sombre), conservée d'une
 * session à l'autre : un logo connu est chargé en bitmap matériel, sans nouvelle analyse.
 */
private object LogoTones {
    private const val PREFS = "logo_tones"
    private val cache = ConcurrentHashMap<String, Boolean>()
    @Volatile
    private var loaded = false

    fun get(context: Context, url: String): Boolean? {
        ensureLoaded(context)
        return cache[url]
    }

    fun put(context: Context, url: String, light: Boolean) {
        cache[url] = light
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(url, light).apply()
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (url, value) ->
            if (value is Boolean) cache[url] = value
        }
        loaded = true
    }
}

private val LightPlate = Color(0xFFF1F3F5)
private val DarkPlate = Color(0xFF2B303B)

/**
 * Logo de chaîne sur plaque : claire pour les logos sombres (France 2, TF1…), sombre pour les logos
 * blancs sur fond transparent (France Info…), sinon ils disparaissent. Initiales si le logo manque.
 */
@Composable
fun LogoPlate(
    logoUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    padding: Dp = 5.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember(logoUrl) { mutableStateOf(false) }
    val knownTone = remember(logoUrl) { logoUrl?.let { LogoTones.get(context, it) } }
    var isLightLogo by remember(logoUrl) { mutableStateOf(knownTone ?: false) }
    val request = remember(logoUrl) {
        // Bitmap logiciel uniquement si le logo doit être analysé ; sinon bitmap matériel, plus rapide à dessiner
        ImageRequest.Builder(context).data(logoUrl).allowHardware(knownTone != null).crossfade(200).build()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(if (isLightLogo) DarkPlate else LightPlate),
        contentAlignment = Alignment.Center
    ) {
        if (!logoUrl.isNullOrBlank() && !failed) {
            AsyncImage(
                model = request,
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    if (knownTone == null) {
                        // Analyse hors du fil d'affichage : ne ralentit pas le défilement
                        val drawable = state.result.drawable
                        scope.launch(Dispatchers.Default) {
                            val light = isMostlyLight(drawable)
                            LogoTones.put(context, logoUrl, light)
                            withContext(Dispatchers.Main) { isLightLogo = light }
                        }
                    }
                },
                onError = { failed = true }
            )
        } else {
            Text(
                text = name.take(3).uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937)
            )
        }
    }
}

/** Vrai si les pixels visibles du logo sont majoritairement clairs (logo blanc sur fond transparent). */
private fun isMostlyLight(drawable: Drawable): Boolean {
    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return false
    val sample = try {
        Bitmap.createScaledBitmap(bitmap, 24, 24, true)
    } catch (e: Exception) {
        return false
    }
    var luminanceSum = 0.0
    var opaque = 0
    for (x in 0 until sample.width) {
        for (y in 0 until sample.height) {
            val pixel = sample.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) < 128) continue
            luminanceSum += (0.2126 * android.graphics.Color.red(pixel) +
                0.7152 * android.graphics.Color.green(pixel) +
                0.0722 * android.graphics.Color.blue(pixel)) / 255.0
            opaque++
        }
    }
    val total = sample.width * sample.height
    // Logo sur fond opaque : il porte son propre fond, la plaque claire convient
    if (opaque == 0 || opaque > total * 0.9) return false
    return luminanceSum / opaque > 0.78
}

/** Fine barre de progression du programme en cours. */
@Composable
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 2.dp,
    trackColor: Color = Color.White.copy(alpha = 0.16f),
    color: Color = AccentCyan
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(height))
                .background(color)
        )
    }
}

/** Intitulé de section discret (petites capitales espacées). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        color = TextSecondary,
        modifier = modifier
    )
}

/** Étiquette technique (FHD, HEVC, fournisseur…). */
@Composable
fun Tag(text: String, color: Color = TextSecondary, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text = text, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1)
    }
}

/**
 * Ligne de menu : transparente au repos, pleine (couleur du texte principal) au focus.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    leading: (@Composable (focused: Boolean) -> Unit)? = null,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val contentColor = if (isFocused) LocalAppColors.current.background else TextPrimary
    val secondaryColor = if (isFocused) LocalAppColors.current.background.copy(alpha = 0.7f) else TextSecondary

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = TextPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (subtitle != null) 8.dp else 11.dp)
        ) {
            when {
                leading != null -> {
                    leading(isFocused)
                    Spacer(modifier = Modifier.width(12.dp))
                }
                icon != null -> {
                    Icon(imageVector = icon, contentDescription = null, tint = secondaryColor, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing?.invoke(isFocused)
        }
    }
}

/** Indicateur de sélection (bouton radio) pour les listes de choix. */
@Composable
fun SelectionDot(selected: Boolean, focused: Boolean) {
    val ring = if (focused) LocalAppColors.current.background.copy(alpha = 0.6f) else TextSecondary.copy(alpha = 0.6f)
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (selected) AccentCyan else ring, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentCyan)
            )
        }
    }
}

/** Bouton icône rond (paramètres, retour…). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceDark,
            focusedContainerColor = TextPrimary
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isFocused) LocalAppColors.current.background else TextSecondary,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}
