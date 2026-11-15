package io.github.sim7b.tele.data.remote

import android.content.Context
import okhttp3.Request
import java.security.SecureRandom

/**
 * User-Agent sent to the Dispatcharr servers: the default one with a random id of this install
 * ("TV-AndroidTV/1.0 (Android TV; Tele-1a2b3c4d5e6f)"). Dispatcharr stores the User-Agent of each viewer, so
 * the Télé API plugin can tell the sessions of this TV from the ones of another TV of the same account behind
 * the same address. The id is random (no device data), kept in a preference that is neither backed up nor
 * copied to another TV, and removed from any request to another host ([withoutDeviceId]).
 */
object DeviceUserAgent {
    private const val PREFS_NAME = "device"
    private const val KEY_ID = "install_id"
    private const val ID_BYTES = 6
    private val ID_PATTERN = Regex("^[0-9a-f]{12}$")
    private val MARKER = Regex("; Tele-[0-9a-f]{12}\\)")

    // Internal setter: set by the tests
    @Volatile var installId: String? = null
        internal set

    /** Reads (or creates on the first launch) the id of this install. */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ID, null)?.takeIf { ID_PATTERN.matches(it) }
        installId = stored ?: newId().also { prefs.edit().putString(KEY_ID, it).apply() }
    }

    internal fun newId(random: SecureRandom = SecureRandom()): String =
        ByteArray(ID_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** User-Agent of the Dispatcharr requests and streams; the default one until [init] ran. */
    val dispatcharr: String
        get() = withId(HttpClients.DEFAULT_USER_AGENT, installId)

    internal fun withId(base: String, id: String?): String {
        if (id == null || !ID_PATTERN.matches(id) || !base.endsWith(")")) return base
        return base.dropLast(1) + "; Tele-$id)"
    }

    /** User-Agent of a Dispatcharr source: the one chosen by the user, else [dispatcharr]. */
    fun forDispatcharr(customUserAgent: String?): String = customUserAgent?.takeIf { it.isNotBlank() } ?: dispatcharr

    /** [userAgent] without the install id (a redirect to a provider must not carry it). */
    fun strip(userAgent: String): String = MARKER.replace(userAgent, ")")

    /** [request] with the install id removed from its User-Agent, unchanged when it has none. */
    fun withoutDeviceId(request: Request): Request {
        val userAgent = request.header("User-Agent") ?: return request
        val stripped = strip(userAgent)
        return if (stripped == userAgent) request else request.newBuilder().header("User-Agent", stripped).build()
    }
}
