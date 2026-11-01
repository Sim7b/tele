package io.github.sim7b.tele.data.transfer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

/** Texts of the phone page, in the language of the TV. */
data class KeyEntryPage(
    val title: String,
    val label: String,
    val send: String,
    val done: String,
    val invalid: String,
    val getKey: String,
    val providerUrl: String
)

/**
 * Entry of an API key from a phone: a page of the local network (shown as a QR code by the TV) where the key
 * is pasted, then sent to the TV. It only answers at its random path, only while the dialog is open, and only
 * once: the first valid key ends it. Requests are small and read with a timeout.
 */
class KeyEntryServer(private val page: KeyEntryPage, private val onKey: (String) -> Unit) {

    private val server = ServerSocket(0)
    private var job: Job? = null
    @Volatile
    private var received = false

    // Unguessable path: other devices of the network cannot post a key
    val token: String = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    val port: Int get() = server.localPort

    fun url(host: String): String = "http://$host:$port/$token"

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !received) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                socket.use { serve(it) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        try {
            server.close()
        } catch (e: Exception) {
            Log.w(TAG, "Key entry server not closed: ${e.javaClass.simpleName}")
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
            val reply = respond(request.method, request.path, request.body)
            if (reply.key != null && !received) {
                received = true
                onKey(reply.key)
            }
            val bytes = reply.html.toByteArray()
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 ${reply.status} ${if (reply.status == 200) "OK" else "Not Found"}\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                write(bytes)
                flush()
            }
        } catch (e: Exception) {
            // Slow or broken client: dropped
            Log.w(TAG, "Key entry request dropped: ${e.javaClass.simpleName}")
        }
    }

    /** Answer to a request: the form, the key received (then [Reply.key] is set), or nothing. */
    internal fun respond(method: String, path: String, body: String): Reply {
        if (path.substringBefore('?') != "/$token") return Reply(404, "")
        return when (method) {
            "GET" -> Reply(200, form(error = false))
            "POST" -> {
                val key = parseForm(body)["key"]?.trim().orEmpty()
                if (isValidKey(key)) Reply(200, done(), key) else Reply(200, form(error = true))
            }
            else -> Reply(404, "")
        }
    }

    internal data class Reply(val status: Int, val html: String, val key: String? = null)

    private fun form(error: Boolean): String = html(
        """
        <form method="post" action="/$token">
          <label for="key">${escape(page.label)}</label>
          <input id="key" name="key" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="$MAX_KEY_LENGTH" required>
          ${if (error) "<p class=\"error\">${escape(page.invalid)}</p>" else ""}
          <button type="submit">${escape(page.send)}</button>
        </form>
        <p><a href="${escape(page.providerUrl)}" rel="noreferrer">${escape(page.getKey)}</a></p>
        """
    )

    private fun done(): String = html("<p class=\"done\">${escape(page.done)}</p>")

    private fun html(content: String) = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${escape(page.title)}</title>
        <style>
        body{font-family:system-ui,sans-serif;background:#080b10;color:#f8fafc;margin:0;padding:24px;max-width:520px}
        h1{font-size:20px;font-weight:600}label{display:block;margin:16px 0 8px;color:#94a3b8}
        input{width:100%;box-sizing:border-box;padding:14px;font-size:16px;border-radius:10px;border:1px solid #334155;background:#111722;color:#f8fafc}
        button{margin-top:16px;width:100%;padding:14px;font-size:16px;border:0;border-radius:10px;background:#06b6d4;color:#080b10;font-weight:600}
        a{color:#38bdf8}.error{color:#f87171}.done{font-size:18px}
        </style></head><body><h1>${escape(page.title)}</h1>$content</body></html>
    """.trimIndent()

    private class Request(val method: String, val path: String, val body: String)

    /** Request line, headers and a small form body; null when malformed or too large. */
    private fun readRequest(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val length = lines.drop(1).firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) return null
            read += count
        }
        return Request(parts[0], parts[1], String(body))
    }

    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n"
        while (bytes.size() < MAX_HEAD_BYTES) {
            val value = input.read()
            if (value < 0) return null
            bytes.write(value)
            matched = if (value.toChar() == end[matched]) matched + 1 else if (value.toChar() == '\r') 1 else 0
            if (matched == end.length) return bytes.toString(Charsets.ISO_8859_1.name()).trimEnd()
        }
        return null
    }

    companion object {
        private const val TAG = "KeyEntry"
        private const val TOKEN_BYTES = 16
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024
        private const val MAX_KEY_LENGTH = 128
        private val KEY = Regex("""[A-Za-z0-9._-]{16,$MAX_KEY_LENGTH}""")

        /** API keys: letters, digits and . _ - (TMDB, OpenSubtitles). */
        fun isValidKey(key: String): Boolean = KEY.matches(key)

        internal fun parseForm(body: String): Map<String, String> =
            body.split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=', "")
                if (name.isEmpty()) return@mapNotNull null
                try {
                    URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    null
                }
            }.toMap()

        internal fun escape(text: String): String = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }
}
