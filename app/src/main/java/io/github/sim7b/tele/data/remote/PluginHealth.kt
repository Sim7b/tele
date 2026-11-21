package io.github.sim7b.tele.data.remote

/**
 * What the TV knows of the Télé API plugin of a server: its features, for a while only. They are probed again
 * every [RECHECK_MS] (a plugin installed, updated or removed meanwhile, a server restarted without it), and a
 * failed call of the plugin (no answer, server error, an HTML page where its routes are missing) sets it aside
 * for [SET_ASIDE_MS]: the standard API of Dispatcharr answers at once meanwhile, instead of a wait on every call.
 */
class PluginHealth(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile private var features: Set<String>? = null
    @Volatile private var validUntil = 0L

    /** The features while they hold (empty: no plugin, or set aside); null when they must be probed. */
    fun current(): Set<String>? = features?.takeIf { clock() < validUntil }

    /** The features last known, without probing (empty when unknown). */
    fun known(): Set<String> = current().orEmpty()

    fun probed(found: Set<String>) {
        features = found
        validUntil = clock() + RECHECK_MS
    }

    /** A call of the plugin failed: set aside, then probed again. */
    fun failed() {
        features = emptySet()
        validUntil = clock() + SET_ASIDE_MS
    }

    fun reset() {
        features = null
        validUntil = 0L
    }

    companion object {
        const val RECHECK_MS = 15 * 60_000L
        const val SET_ASIDE_MS = 5 * 60_000L

        /**
         * Whether an answer of the plugin shows it broken: a server error, or an HTML page (its routes are not
         * installed: the web interface of Dispatcharr answers, a CSRF refusal among them). A refusal in JSON (no
         * right, too many requests) or a missing item is an answer of the plugin.
         */
        fun isFailure(code: Int, contentType: String?): Boolean =
            code >= 500 || contentType?.contains("html", ignoreCase = true) == true
    }
}
