package io.github.sim7b.tele.data.subtitles

import android.net.Uri
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.UserFacingException
import io.github.sim7b.tele.data.storage.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Subtitles from OpenSubtitles, only on request: search with the key and the languages of the settings,
 * download into [cache] (a movie or an episode played again gets them back without any request).
 */
class SubtitleRepository(private val settings: SettingsRepository, private val cache: SubtitleCache) {

    // Downloads left today, from the last download (null: unknown)
    private val _remaining = MutableStateFlow<Int?>(null)
    val remaining: StateFlow<Int?> = _remaining.asStateFlow()

    val available: Boolean get() = settings.openSubtitlesKey.value.isNotBlank()

    /** Subtitles for [query] in the languages of the settings, or in every language. */
    suspend fun search(query: SubtitleQuery, allLanguages: Boolean): List<SubtitleResult> =
        OpenSubtitles.search(key(), query, if (allLanguages) emptyList() else settings.subtitleLanguages.value)

    /** Downloads [result] for the movie or episode [itemKey] and keeps it on the device. */
    suspend fun download(itemKey: String, result: SubtitleResult): ExternalSubtitle {
        val downloaded = OpenSubtitles.download(key(), result.fileId)
        downloaded.remaining?.let { _remaining.value = it }
        val saved = withContext(Dispatchers.IO) {
            cache.save(itemKey, result.fileId, result.language, downloaded.extension, downloaded.bytes)
        }
        return saved.toExternal()
    }

    /** Subtitles already downloaded for [itemKey] (no request). */
    suspend fun cached(itemKey: String): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        cache.list(itemKey).map { it.toExternal() }
    }

    private fun key(): String = settings.openSubtitlesKey.value.takeIf { it.isNotBlank() }
        ?: throw UserFacingException(UiText.Res(R.string.subtitles_error_no_key))

    private fun CachedSubtitle.toExternal() = ExternalSubtitle(
        uri = Uri.fromFile(file).toString(),
        mimeType = mimeType,
        language = language,
        label = "OpenSubtitles"
    )
}
