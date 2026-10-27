package io.github.sim7b.tele.data.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.executeCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit

/** A TV offering its configuration on the local network. */
data class TransferPeer(val name: String, val host: String, val port: Int) {
    val address: String get() = "$host:$port"
}

/**
 * Configuration transfer between two TVs of the same local network, without account nor cloud:
 * the sending TV serves its configuration, encrypted with the code it shows ([TransferCrypto]), on a
 * small HTTP server announced by mDNS (NSD); the receiving TV finds it (or takes its address),
 * downloads it and decrypts it with the typed code.
 */
object ConfigTransfer {

    private const val TAG = "ConfigTransfer"
    const val SERVICE_TYPE = "_teletransfer._tcp."
    private const val PATH = "/config"
    private const val MAX_PAYLOAD_BYTES = 1L * 1024 * 1024

    /**
     * Sending side: serves [sealed] to the first request on [PATH] (then [onSent]), until [Sender.stop].
     */
    class Sender(private val context: Context, private val sealed: String, private val onSent: () -> Unit) {
        private val server = ServerSocket(0)
        private var job: Job? = null
        private val nsd = context.getSystemService(NsdManager::class.java)
        private var registration: NsdManager.RegistrationListener? = null

        val port: Int get() = server.localPort

        fun start(scope: CoroutineScope) {
            job = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        server.accept().use { client ->
                            client.soTimeout = 10_000
                            val reader = client.getInputStream().bufferedReader()
                            val requestLine = reader.readLine().orEmpty()
                            // Headers, up to the blank line
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            val ok = requestLine.startsWith("GET $PATH ")
                            val body = if (ok) sealed.toByteArray(Charsets.UTF_8) else ByteArray(0)
                            val status = if (ok) "200 OK" else "404 Not Found"
                            client.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray(Charsets.US_ASCII)
                                )
                                write(body)
                                flush()
                            }
                            if (ok) withContext(Dispatchers.Main) { onSent() }
                        }
                    } catch (e: SocketException) {
                        // Server closed by stop()
                        break
                    } catch (e: IOException) {
                        Log.w(TAG, "Transfer request failed: ${e.javaClass.simpleName}")
                    }
                }
            }
            register()
        }

        private fun register() {
            val info = NsdServiceInfo().apply {
                serviceName = "Télé · ${Build.MODEL}"
                serviceType = SERVICE_TYPE
                port = server.localPort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                    Log.w(TAG, "mDNS registration failed: $error")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            }
            registration = listener
            try {
                nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: RuntimeException) {
                Log.w(TAG, "mDNS unavailable: ${e.javaClass.simpleName}")
            }
        }

        fun stop() {
            registration?.let { listener ->
                try {
                    nsd?.unregisterService(listener)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "mDNS unregistration failed: ${e.javaClass.simpleName}")
                }
            }
            registration = null
            job?.cancel()
            try {
                server.close()
            } catch (_: IOException) {
            }
        }
    }

    /** IPv4 addresses of this TV on the local network (shown for a manual connection). */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (e: SocketException) {
        emptyList()
    }

    /** Receiving side: TVs announcing a configuration, as they are found and resolved. */
    fun discover(context: Context): Flow<TransferPeer> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java) ?: run {
            close()
            return@callbackFlow
        }
        // NSD resolves one service at a time: the others wait in this queue
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        // NSD callbacks come from binder threads: the queue and the flag are guarded by the queue lock
        fun resolveNext() {
            val next = synchronized(pending) {
                if (resolving) return
                pending.removeFirstOrNull()?.also { resolving = true }
            } ?: return
            fun done() {
                synchronized(pending) { resolving = false }
                resolveNext()
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress
                    if (host != null) trySend(TransferPeer(info.serviceName, host, info.port))
                    done()
                }
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) = done()
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(pending) { pending.addLast(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                Log.w(TAG, "mDNS discovery failed: $error")
            }
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "mDNS unavailable: ${e.javaClass.simpleName}")
        }
        awaitClose {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: RuntimeException) {
            }
        }
    }

    /** Downloads the encrypted configuration of [address] ("host:port"). */
    suspend fun fetch(address: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("http://${address.trim()}$PATH").build()
        val client = HttpClients.base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        client.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body.source()
            if (source.request(MAX_PAYLOAD_BYTES + 1)) throw IOException("Configuration too large")
            source.readUtf8()
        }
    }
}
