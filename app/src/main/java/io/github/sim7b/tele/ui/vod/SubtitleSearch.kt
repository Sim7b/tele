package io.github.sim7b.tele.ui.vod

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.UiText
import io.github.sim7b.tele.data.resolve
import io.github.sim7b.tele.data.subtitles.ExternalSubtitle
import io.github.sim7b.tele.data.subtitles.SubtitleQuery
import io.github.sim7b.tele.data.subtitles.SubtitleResult
import io.github.sim7b.tele.data.toUiText
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.AccentCyan
import io.github.sim7b.tele.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * OpenSubtitles for the screens of the module: downloaded subtitles are always read back ([cached]),
 * the search needs an API key ([canSearch]).
 */
class VodSubtitles(
    val canSearch: Boolean,
    val search: suspend (SubtitleQuery, allLanguages: Boolean) -> List<SubtitleResult>,
    val download: suspend (itemKey: String, SubtitleResult) -> ExternalSubtitle,
    val cached: suspend (itemKey: String) -> List<ExternalSubtitle>,
    val remaining: Int?
)

/**
 * Subtitles found on OpenSubtitles for a movie or an episode: searched when shown (never before), in the
 * languages of the settings or in every language; OK downloads one and hands it over with [onDownloaded].
 */
@Composable
internal fun SubtitleSearchList(
    search: suspend (allLanguages: Boolean) -> List<SubtitleResult>,
    download: suspend (SubtitleResult) -> Unit,
    onDownloaded: () -> Unit,
    modifier: Modifier = Modifier,
    remaining: Int? = null
) {
    val scope = rememberCoroutineScope()
    var allLanguages by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SubtitleResult>?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var downloading by remember { mutableStateOf<Long?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(allLanguages, attempt) {
        results = null
        error = null
        try {
            results = search(allLanguages)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.toUiText()
        }
    }
    LaunchedEffect(results != null, error != null) {
        if (results != null || error != null) firstFocus.requestFocusWithRetry()
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        val found = results
        when {
            error != null -> item {
                Text(text = error!!.resolve(), fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp))
                MenuRow(
                    label = stringResource(R.string.home_retry),
                    icon = Icons.Filled.Refresh,
                    onClick = { attempt++ },
                    modifier = Modifier.focusRequester(firstFocus)
                )
            }
            found == null -> item {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
            else -> {
                if (found.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.subtitles_none),
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp)
                        )
                    }
                }
                items(found, key = { it.fileId }) { result ->
                    val details = listOfNotNull(
                        pluralStringResource(R.plurals.subtitles_downloads, result.downloads, result.downloads),
                        if (result.hearingImpaired) stringResource(R.string.subtitles_hearing_impaired) else null,
                        if (result.machineTranslated) stringResource(R.string.subtitles_machine) else null,
                        if (result.fromTrusted) stringResource(R.string.subtitles_trusted) else null,
                        if (downloading == result.fileId) stringResource(R.string.subtitles_downloading) else null
                    ).joinToString(" · ")
                    MenuRow(
                        label = (languageName(result.language) ?: result.language) + " · " + result.release,
                        subtitle = details,
                        onClick = {
                            if (downloading != null) return@MenuRow
                            downloading = result.fileId
                            scope.launch {
                                try {
                                    download(result)
                                    onDownloaded()
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    error = e.toUiText()
                                } finally {
                                    downloading = null
                                }
                            }
                        },
                        modifier = if (result == found.first()) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
                if (!allLanguages) {
                    item {
                        MenuRow(
                            label = stringResource(R.string.subtitles_all_languages),
                            icon = Icons.Filled.Search,
                            onClick = { allLanguages = true },
                            modifier = if (found.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
                        )
                    }
                }
                remaining?.let { left ->
                    item {
                        Text(
                            text = pluralStringResource(R.plurals.subtitles_remaining, left, left),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
