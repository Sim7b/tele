package io.github.sim7b.tele.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Clock

/**
 * Clock of the screens (the time of the headers, the progress of the programs): the system one, or a fixed one for
 * the README screenshots, so they are drawn the same every time.
 */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }
