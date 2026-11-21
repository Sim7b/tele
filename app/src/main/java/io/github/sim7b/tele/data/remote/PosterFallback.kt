package io.github.sim7b.tele.data.remote

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Light posters of the Télé API plugin with a way back: when one cannot be had (plugin removed, disabled or
 * failing since the catalog was loaded), the image of the same VOD logo kept by Dispatcharr is loaded instead.
 */
object PosterFallback : Interceptor {

    private val POSTER = Regex("^${Regex.escape(TeleApi.VOD_POSTER_PATH)}(\\d{1,12})/$")

    /** The image of Dispatcharr behind a light poster of the plugin; null for any other address. */
    fun fallbackOf(url: HttpUrl): HttpUrl? {
        val id = POSTER.find(url.encodedPath)?.groupValues?.get(1) ?: return null
        return url.newBuilder().encodedPath("/api/vod/vodlogos/$id/cache/").query(null).build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fallback = fallbackOf(request.url) ?: return chain.proceed(request)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            null
        }
        if (response != null && response.isSuccessful && response.header("Content-Type")?.startsWith("image/") == true) return response
        response?.close()
        return chain.proceed(request.newBuilder().url(fallback).build())
    }
}
