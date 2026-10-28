package io.github.sim7b.tele.ui.vod

import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.vod.TmdbFacts
import io.github.sim7b.tele.data.vod.VodBrowse
import io.github.sim7b.tele.data.vod.VodRankings
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.ui.components.RoundIconButton
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.screens.MicIcon
import io.github.sim7b.tele.ui.screens.SearchField
import io.github.sim7b.tele.ui.screens.SearchHint
import io.github.sim7b.tele.ui.screens.speechIntent
import io.github.sim7b.tele.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Search of the movies and series: titles of the library at once (every word, start of words), then
 * with a TMDB key the people named so (actors, directors) and their titles the library has.
 * Typed with the TV keyboard or spoken.
 */
@Composable
fun VodSearchScreen(
    titles: List<VodTitle>,
    facts: () -> Map<String, TmdbFacts>,
    tmdbKey: String?,
    onOpenTitle: (VodTitle) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    findPoster: (suspend (VodTitle) -> String?)? = null
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val resultFocus = remember { FocusRequester() }
    var focusResults by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)

    val voicePrompt = stringResource(R.string.search_voice_prompt)
    val voiceAvailable = remember { speechIntent(voicePrompt).resolveActivity(context.packageManager) != null }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.takeIf { result.resultCode == Activity.RESULT_OK && it.isNotBlank() }
            ?.let {
                query = it
                focusResults++
            }
    }

    val index by produceState(emptyList<Pair<String, VodTitle>>(), titles) {
        value = withContext(Dispatchers.Default) { VodBrowse.index(titles) }
    }
    val found by produceState(emptyList<VodTitle>(), index, query) {
        delay(SEARCH_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { VodBrowse.search(index, query, facts()) }
    }
    // People: once the typing stops, only with a TMDB key
    val people by produceState(emptyList<VodBrowse.PersonCredits>(), query, titles) {
        value = emptyList()
        val text = query.trim()
        if (tmdbKey.isNullOrBlank() || text.length < MIN_PERSON_QUERY) return@produceState
        delay(PEOPLE_DEBOUNCE_MS)
        val matcher = withContext(Dispatchers.Default) { VodRankings.Matcher(titles, facts()) }
        value = VodBrowse.people(text, tmdbKey, matcher)
    }

    LaunchedEffect(Unit) { fieldFocus.requestFocusWithRetry() }
    // Down / Search on the keyboard: to the first row of results (titles, else the first person)
    val hasResults = found.isNotEmpty() || people.isNotEmpty()
    LaunchedEffect(focusResults, hasResults) {
        if (focusResults > 0 && hasResults) {
            keyboard?.hide()
            resultFocus.requestFocusWithRetry()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
            .padding(start = 48.dp, end = 48.dp, top = 26.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { focusResults++ },
                placeholder = stringResource(R.string.vod_search_placeholder),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
            )
            if (voiceAvailable) {
                RoundIconButton(
                    icon = MicIcon,
                    contentDescription = stringResource(R.string.search_voice),
                    onClick = { voiceLauncher.launch(speechIntent(voicePrompt)) },
                    size = 48.dp
                )
            }
        }

        when {
            query.isBlank() -> SearchHint(stringResource(R.string.vod_search_hint))
            found.isEmpty() && people.isEmpty() && index.isNotEmpty() -> SearchHint(stringResource(R.string.vod_search_no_results, query.trim()))
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(top = 18.dp, bottom = 40.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (found.isNotEmpty()) {
                    item(key = "titles") {
                        ResultRow(stringResource(R.string.vod_search_titles), found, onOpenTitle, findPoster, resultFocus)
                    }
                }
                people.forEachIndexed { index, person ->
                    item(key = "person:${person.name}") {
                        val first = found.isEmpty() && index == 0
                        ResultRow(stringResource(R.string.vod_search_with, person.name), person.titles, onOpenTitle, findPoster, if (first) resultFocus else null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    label: String,
    titles: List<VodTitle>,
    onOpenTitle: (VodTitle) -> Unit,
    findPoster: (suspend (VodTitle) -> String?)?,
    firstFocus: FocusRequester?
) {
    Column {
        SectionLabel(text = label)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 10.dp, end = 24.dp)) {
            items(titles, key = { it.key }) { title ->
                PosterCard(
                    title = title,
                    onClick = { onOpenTitle(title) },
                    findPoster = findPoster,
                    modifier = if (firstFocus != null && title == titles.first()) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 150L
private const val PEOPLE_DEBOUNCE_MS = 700L
private const val MIN_PERSON_QUERY = 3
