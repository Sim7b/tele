package io.github.sim7b.tele

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.github.sim7b.tele.data.remote.HttpClients

/**
 * Application: provides the image loader of the logos and artwork.
 */
class TeleApplication : Application(), ImageLoaderFactory {

    // Same connection pool as the API, the guides and the player (Coil would create its own client)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { HttpClients.base }
        .build()
}
