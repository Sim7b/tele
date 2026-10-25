package io.github.sim7b.tele.data.remote

import io.github.sim7b.tele.data.model.SourceConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Dispatcharr API key, sent only to the origin (scheme, host and port) of the server it belongs to:
 * never to a host the server redirects to, nor over plain HTTP after an HTTPS -> HTTP redirect.
 */
class ApiKeyAuth(private val origin: HttpUrl, val key: String) {

    fun appliesTo(url: HttpUrl): Boolean =
        url.scheme == origin.scheme && url.host.equals(origin.host, ignoreCase = true) && url.port == origin.port

    companion object {
        const val HEADER = "X-API-Key"

        fun of(config: SourceConfig): ApiKeyAuth? {
            val origin = config.normalizedUrl.toHttpUrlOrNull() ?: return null
            return config.apiKey.takeIf { it.isNotBlank() }?.let { ApiKeyAuth(origin, it) }
        }
    }
}

/**
 * Network interceptor adding the API key returned by [auth] for a request. Network interceptors run again
 * on each redirect, so the origin is checked for every hop.
 */
class ApiKeyInterceptor(private val auth: (Request) -> ApiKeyAuth?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val apiKey = auth(request)?.takeIf { it.appliesTo(request.url) } ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(ApiKeyAuth.HEADER, apiKey.key).build())
    }
}
