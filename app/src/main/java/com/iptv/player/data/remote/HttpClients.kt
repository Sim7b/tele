package com.iptv.player.data.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * HTTP client shared by the API, playlists, guides and the player: a single connection pool,
 * so TLS handshakes and keep-alive connections are reused (notably on each channel switch).
 */
object HttpClients {
    const val DEFAULT_USER_AGENT = "TV-AndroidTV/1.0 (Android TV)"

    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
