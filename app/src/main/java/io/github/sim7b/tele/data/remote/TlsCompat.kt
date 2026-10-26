package io.github.sim7b.tele.data.remote

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.sim7b.tele.R
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Modern TLS on Android 9 and older: their system TLS stops at 1.2, while self-hosted servers
 * (Dispatcharr behind a recent reverse proxy) often only accept TLS 1.3, and they do not know the
 * recent Let's Encrypt roots. There, connections go through the bundled Conscrypt (TLS 1.3) and trust
 * the system certificates plus the ISRG Root X1 / X2 roots. Android 10 and later keep the system TLS,
 * with the same roots added by the network security config.
 */
object TlsCompat {

    private const val TAG = "TlsCompat"
    private val BUNDLED_ROOTS = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)

    // Bundled roots read once at startup (PEM), no context kept
    @Volatile
    private var bundledRoots: List<ByteArray>? = null

    /** Called by the Application before any connection. Without it (JVM tests) nothing changes. */
    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        bundledRoots = BUNDLED_ROOTS.map { res -> context.resources.openRawResource(res).use { it.readBytes() } }
    }

    /** [builder] with the Conscrypt TLS on Android 9 and older; unchanged on newer versions. */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val roots = bundledRoots ?: return builder
        return try {
            val trustManager = trustManager(roots)
            val sslContext = SSLContext.getInstance("TLS", Conscrypt.newProvider())
            sslContext.init(null, arrayOf(trustManager), null)
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        } catch (e: Exception) {
            // The system TLS still works for servers accepting TLS 1.2
            Log.w(TAG, "Conscrypt TLS unavailable: ${e.javaClass.simpleName}")
            builder
        }
    }

    /** System certificates (not the user-installed ones, as the network security config) and the bundled roots. */
    private fun trustManager(roots: List<ByteArray>): X509TrustManager {
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        system.aliases().asSequence()
            .filter { it.startsWith("system:") }
            .forEach { alias -> trusted.setCertificateEntry(alias, system.getCertificate(alias)) }
        val certificates = CertificateFactory.getInstance("X.509")
        roots.forEachIndexed { index, pem ->
            trusted.setCertificateEntry("bundled:$index", certificates.generateCertificate(pem.inputStream()))
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(trusted)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
