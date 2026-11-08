package io.github.sim7b.tele

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.ImageCacheSize
import io.github.sim7b.tele.data.remote.TlsCompat
import io.github.sim7b.tele.ui.components.Device

/**
 * Application: sets up the TLS of the HTTP clients and provides the image loader of the logos and artwork.
 */
class TeleApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Before any connection: the shared HTTP client picks its TLS stack when first used
        TlsCompat.init(this)
        // TV (remote) or phone / tablet (touch), for the whole UI
        Device.init(this)
    }

    // Same connection pool as the API, the guides and the player (Coil would create its own client). A disk cache
    // sized from the storage left at each start (see ImageCacheSize), in the cache folder the system empties
    // when it lacks room; its images used without asking the servers again: their addresses change when the
    // picture does (TMDB, providers)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { HttpClients.base }
        .respectCacheHeaders(false)
        .diskCache {
            val dir = cacheDir.resolve("images")
            val used = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            DiskCache.Builder()
                .directory(dir)
                .maxSizeBytes(ImageCacheSize.bytes(freeBytes = cacheDir.usableSpace, cacheBytes = used))
                .build()
        }
        .build()
}
