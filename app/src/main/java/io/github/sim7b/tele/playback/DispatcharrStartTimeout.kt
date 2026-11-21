package io.github.sim7b.tele.playback

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Longer wait for the answer of a Dispatcharr channel ([ReconnectPolicy.DISPATCHARR_FIRST_BYTES_S]): the server holds
 * it until its provider filled the buffer. Other streams keep the read timeout of the client; a channel frozen
 * once playing is caught by the stall watchdog of the player.
 */
object DispatcharrStartTimeout : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!ReconnectPolicy.isDispatcharrLive(request.url.encodedPath)) return chain.proceed(request)
        val seconds = maxOf(ReconnectPolicy.DISPATCHARR_FIRST_BYTES_S, chain.readTimeoutMillis() / 1000L).toInt()
        return chain.withReadTimeout(seconds, TimeUnit.SECONDS).proceed(request)
    }
}
