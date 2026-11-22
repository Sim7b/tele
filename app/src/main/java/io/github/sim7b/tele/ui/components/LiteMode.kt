package io.github.sim7b.tele.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Classic (light) mode, chosen at the first launch for small devices: no full-screen images behind the screens,
 * no fades, images in half the memory. False: the immersive mode.
 */
val LocalLite = staticCompositionLocalOf { false }

/** Ultra light mode (see UltraLight): no artwork, nothing in the background. Implies the light mode. */
val LocalUltraLight = staticCompositionLocalOf { false }

/** What an image is: in the ultra light mode, the artworks are not loaded, the posters and logos are. */
enum class ImageKind { ARTWORK, POSTER, LOGO }

/** Fade of an image as it appears: none in the light mode. */
internal fun crossfadeMs(lite: Boolean, millis: Int): Int = if (lite) 0 else millis
