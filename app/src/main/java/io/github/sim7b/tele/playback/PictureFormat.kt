package io.github.sim7b.tele.playback

import androidx.annotation.StringRes
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import io.github.sim7b.tele.R

/** How the video fills the screen, chosen in the playback options and kept across channels. */
@OptIn(UnstableApi::class)
enum class PictureFormat(@param:StringRes val labelRes: Int, val resizeMode: Int) {
    // Whole picture, black bars when its shape differs from the screen
    FIT(R.string.picture_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    // Fills the screen, edges cropped (4:3 broadcasts, letterboxed films)
    ZOOM(R.string.picture_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    // Fills the screen, picture distorted
    STRETCH(R.string.picture_stretch, AspectRatioFrameLayout.RESIZE_MODE_FILL)
}
