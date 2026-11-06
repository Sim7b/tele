package io.github.sim7b.tele.data.update

/**
 * Update downloaded ahead of time: one APK per version in the cache ("tele-0.3.15.apk"), kept until it is
 * installed or a newer one replaces it.
 */
object UpdateFiles {

    private const val PREFIX = "tele-"
    private const val SUFFIX = ".apk"
    private val SAFE_VERSION = Regex("[0-9A-Za-z.+-]{1,40}")

    /** File name of [version]; null for a version that is not a plain version string (never a path). */
    fun fileName(version: String): String? = version.takeIf { SAFE_VERSION.matches(it) && ".." !in it }?.let { PREFIX + it + SUFFIX }

    /** Version of a file named by [fileName], null for any other file. */
    fun versionOf(name: String): String? =
        name.takeIf { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }?.removePrefix(PREFIX)?.removeSuffix(SUFFIX)?.takeIf { fileName(it) == name }

    /** Files left to delete: anything but the APK of a version newer than [installed] (and the [keep] one). */
    fun stale(names: List<String>, installed: String, keep: String? = null): List<String> = names.filter { name ->
        val version = versionOf(name)
        version == null || !AppVersion.isNewer(version, installed) || (keep != null && version != keep)
    }

    /** The offer at startup: a downloaded version the user has not chosen to ignore. */
    fun shouldPrompt(readyVersion: String?, ignoredVersion: String?): Boolean =
        readyVersion != null && readyVersion != ignoredVersion
}
