package io.github.sim7b.tele.data.update

import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.UserFacingException
import io.github.sim7b.tele.data.optStringOrNull
import io.github.sim7b.tele.data.remote.HttpClients
import io.github.sim7b.tele.data.remote.executeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File

/** A release of the app published on GitHub, with its signed APK. */
data class AppRelease(val version: String, val apkUrl: String, val apkSize: Long, val highlights: List<String>)

/** Semantic versions "MAJOR.MINOR.PATCH", with an optional "v" prefix (pre-release suffix ignored). */
internal object AppVersion {
    fun parse(text: String): List<Int>? {
        val parts = text.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.')
        if (parts.size != 3) return null
        return parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    }

    /** True only if both versions are valid and [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val next = parse(candidate) ?: return false
        val installed = parse(current) ?: return false
        for (i in next.indices) {
            if (next[i] != installed[i]) return next[i] > installed[i]
        }
        return false
    }
}

/**
 * Looks up the latest GitHub release of the app and downloads its APK. Only APKs published under the
 * repository's own release download path are accepted; their size is bounded and checked after download.
 */
class UpdateClient(
    private val client: OkHttpClient = HttpClients.base,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
    private val downloadPrefix: String = DOWNLOAD_PREFIX
) {

    /** Latest published release, or null if it has no usable APK. */
    suspend fun latestRelease(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) {
                throw UserFacingException(UiText.Res(R.string.update_error_check, response.code))
            }
            parseRelease(HttpClients.jsonString(response.body), downloadPrefix)
        }
    }

    /** Downloads the APK of [release] into [target], reporting the progress between 0 and 1. */
    suspend fun download(release: AppRelease, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(release.apkUrl).build()
        client.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) {
                throw UserFacingException(UiText.Res(R.string.update_error_download, response.code))
            }
            target.parentFile?.mkdirs()
            var received = 0L
            val source = response.body.source()
            target.sink().buffer().use { sink ->
                while (true) {
                    val read = source.read(sink.buffer, BUFFER_BYTES)
                    if (read == -1L) break
                    received += read
                    // Larger than announced by GitHub: not the published file
                    if (received > release.apkSize) throw incomplete()
                    sink.emitCompleteSegments()
                    onProgress(received.toFloat() / release.apkSize)
                }
            }
            if (received != release.apkSize) throw incomplete()
        }
    }

    private fun incomplete() = UserFacingException(UiText.Res(R.string.update_error_incomplete))

    companion object {
        const val REPOSITORY = "Sim7b/tele"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        private const val DOWNLOAD_PREFIX = "https://github.com/$REPOSITORY/releases/download/"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val MAX_NOTES_LENGTH = 4_000
        private const val MAX_HIGHLIGHTS = 6
        private val COMMIT_LINK = Regex("""\s*\(\[[0-9a-f]{7,40}]\([^)]*\)\)""")
        private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
        private const val BUFFER_BYTES = 64L * 1024

        /** Release from the GitHub API JSON; drafts, pre-releases and foreign download URLs are ignored. */
        internal fun parseRelease(json: String, downloadPrefix: String = DOWNLOAD_PREFIX): AppRelease? {
            val root = JSONObject(json)
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
            val tag = root.optStringOrNull("tag_name") ?: return null
            AppVersion.parse(tag) ?: return null
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optStringOrNull("name") ?: continue
                val url = asset.optStringOrNull("browser_download_url") ?: continue
                val size = asset.optLong("size", -1)
                if (!name.endsWith(".apk") || !url.startsWith(downloadPrefix)) continue
                if (size <= 0 || size > MAX_APK_BYTES) continue
                return AppRelease(
                    version = tag.trim().removePrefix("v"),
                    apkUrl = url,
                    apkSize = size,
                    highlights = highlights(root.optStringOrNull("body")?.take(MAX_NOTES_LENGTH).orEmpty())
                )
            }
            return null
        }

        /**
         * Readable points of the semantic-release notes: the list items, without commit links
         * ("([abc1234](url))"), Markdown links and bold markers.
         */
        internal fun highlights(markdown: String): List<String> = markdown.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("* ") || it.startsWith("- ") }
            .map { line ->
                line.drop(2)
                    .replace(COMMIT_LINK, "")
                    .replace(MARKDOWN_LINK, "$1")
                    .replace("**", "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .take(MAX_HIGHLIGHTS)
            .toList()
    }
}
