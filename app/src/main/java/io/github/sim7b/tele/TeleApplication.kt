package io.github.sim7b.tele

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.TlsCompat

/**
 * Application: sets up the TLS of the HTTP clients and provides the image loader of the logos and artwork.
 */
class TeleApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Before any connection: the shared HTTP client picks its TLS stack when first used
        TlsCompat.init(this)
    }

    // Same connection pool as the API, the guides and the player (Coil would create its own client)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { HttpClients.base }
        .build()
}
