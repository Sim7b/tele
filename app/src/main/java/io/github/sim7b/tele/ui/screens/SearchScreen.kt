package io.github.sim7b.tele.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.search.ChannelSearch
import io.github.sim7b.tele.data.search.ProgramSearch
import io.github.sim7b.tele.data.vod.VodBrowse
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.ui.components.GuideActions
import io.github.sim7b.tele.ui.components.MenuRow
import io.github.sim7b.tele.ui.components.programBadges
import io.github.sim7b.tele.ui.components.programHours
import io.github.sim7b.tele.ui.components.programWhen
import io.github.sim7b.tele.ui.components.rememberNow
import io.github.sim7b.tele.ui.vod.PosterCard
import io.github.sim7b.tele.ui.components.CompactChannelCard
import io.github.sim7b.tele.ui.components.LocalScreen
import io.github.sim7b.tele.ui.components.RoundIconButton
import io.github.sim7b.tele.ui.components.screenMargin
import io.github.sim7b.tele.ui.components.SectionLabel
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SEARCH_COLUMNS = 4
private const val COMPACT_SEARCH_COLUMNS = 2
private const val SEARCH_DEBOUNCE_MS = 150L
private const val PROGRAM_INDEX_MS = 5 * 60_000L
private const val MAX_VOD_RESULTS = 40

private fun programKey(hit: ProgramSearch.Hit) = "p:${hit.channel.id}:${hit.program.startMillis}"
private fun titleKey(title: VodTitle) = "v:${title.key}"

// Material "Mic" icon (material-icons-core does not include it, the extended set is very large)
internal val MicIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Mic", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                    "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
            ),
            fill = SolidColor(Color.Black)
        )
        .build()
}

/** Intent of the system speech recognition (Google on Android TV). */
internal fun speechIntent(prompt: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

/**
 * Channel search by name, number or current program, typed with the TV keyboard or spoken
 * (microphone button, or directly when opened from the remote's search key).
 * [channels] are the channels not individually hidden, hidden categories included.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    channels: List<Channel>,
    startWithVoice: Boolean,
    onChannelSelected: (Channel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Programs of the guide (OK: the channel when on air, a reminder when to come)
    schedules: Map<String, List<GuideProgram>> = emptyMap(),
    actions: GuideActions = GuideActions(),
    // Movies and series of the library
    vodTitles: List<VodTitle> = emptyList(),
    onTitleSelected: (VodTitle) -> Unit = {},
    findPoster: (suspend (VodTitle) -> String?)? = null,
    // Search already typed (asked by voice from outside the app)
    initialQuery: String = ""
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var lastFocusedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Voice already offered on this screen (not again when coming back from the player)
    var voiceStarted by rememberSaveable { mutableStateOf(false) }

    val fieldFocus = remember { FocusRequester() }
    val resultFocus = remember { FocusRequester() }
    var focusResults by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    val voicePrompt = stringResource(R.string.search_voice_prompt)
    val voiceAvailable = remember {
        speechIntent(voicePrompt).resolveActivity(context.packageManager) != null
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { result.resultCode == Activity.RESULT_OK && it.isNotBlank() }
        if (spoken != null) {
            query = spoken
            lastFocusedId = null
            focusResults++
        }
    }
    fun startVoice() {
        voiceStarted = true
        voiceLauncher.launch(speechIntent(voicePrompt))
    }

    // Normalized names computed off the main thread, once per channel list
    val index by produceState(initialValue = emptyList<ChannelSearch.Entry>(), channels) {
        value = withContext(Dispatchers.Default) { ChannelSearch.index(channels) }
    }
    val results by produceState(initialValue = emptyList<Channel>(), index, query) {
        delay(SEARCH_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { ChannelSearch.searchIndex(index, query) }
    }
    // Programs of the guide on air or to come (indexed again every few minutes: the ones over go)
    val now = rememberNow(PROGRAM_INDEX_MS)
    val programIndex by produceState(initialValue = emptyList<ProgramSearch.Entry>(), channels, schedules, now) {
        value = withContext(Dispatchers.Default) { ProgramSearch.index(channels, schedules, now) }
    }
    val programs by produceState(initialValue = emptyList<ProgramSearch.Hit>(), programIndex, query) {
        delay(SEARCH_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { ProgramSearch.search(programIndex, query, System.currentTimeMillis()) }
    }
    // Movies and series of the library (when loaded)
    val vodIndex by produceState(initialValue = emptyList<Pair<String, VodTitle>>(), vodTitles) {
        value = withContext(Dispatchers.Default) { VodBrowse.index(vodTitles) }
    }
    val titles by produceState(initialValue = emptyList<VodTitle>(), vodIndex, query) {
        delay(SEARCH_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { VodBrowse.search(vodIndex, query, emptyMap(), limit = MAX_VOD_RESULTS) }
    }
    // Phone: two results per row
    val columns = if (LocalScreen.current.compact) COMPACT_SEARCH_COLUMNS else SEARCH_COLUMNS
    val rows = remember(results, columns) { results.chunked(columns) }
    // Keys of every result, in the order shown: the focus goes back to the one opened, else the first
    val resultKeys = remember(results, programs, titles) {
        results.map { it.id } + programs.map { programKey(it) } + titles.map { titleKey(it) }
    }
    val focusTargetId = lastFocusedId?.takeIf { it in resultKeys } ?: resultKeys.firstOrNull()

    // Back from the player: focus returns to the channel that was opened, once the results are recomputed
    var restoreResultFocus by remember { mutableStateOf(lastFocusedId != null) }
    LaunchedEffect(Unit) {
        when {
            startWithVoice && !voiceStarted && voiceAvailable -> startVoice()
            !restoreResultFocus -> fieldFocus.requestFocusWithRetry()
        }
    }
    LaunchedEffect(restoreResultFocus, focusTargetId) {
        if (restoreResultFocus && focusTargetId != null) {
            resultFocus.requestFocusWithRetry()
            restoreResultFocus = false
        }
    }
    LaunchedEffect(focusResults, focusTargetId) {
        if (focusResults > 0 && focusTargetId != null) {
            keyboard?.hide()
            resultFocus.requestFocusWithRetry()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = screenMargin(48.dp), end = screenMargin(48.dp), top = 26.dp, bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp)
        ) {
            SearchField(
                query = query,
                onQueryChange = {
                    query = it
                    lastFocusedId = null
                },
                onSubmit = { focusResults++ },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
            )
            if (voiceAvailable) {
                RoundIconButton(
                    icon = MicIcon,
                    contentDescription = stringResource(R.string.search_voice),
                    onClick = { startVoice() },
                    size = 48.dp
                )
            }
        }

        when {
            query.isBlank() -> SearchHint(stringResource(R.string.search_hint))
            resultKeys.isEmpty() && index.isNotEmpty() -> SearchHint(stringResource(R.string.search_no_results, query.trim()))
            else -> {
                fun Modifier.result(key: String) = this
                    .then(if (key == focusTargetId) Modifier.focusRequester(resultFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) lastFocusedId = key }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (results.isNotEmpty()) item(key = "channels") {
                        SectionLabel(text = pluralStringResource(R.plurals.search_results, results.size, results.size))
                    }
                    items(rows, key = { row -> "row:${row.first().id}" }) { rowChannels ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            for (channel in rowChannels) {
                                Box(modifier = Modifier.weight(1f)) {
                                    CompactChannelCard(
                                        channel = channel,
                                        onClick = { onChannelSelected(channel) },
                                        modifier = Modifier.result(channel.id)
                                    )
                                }
                            }
                            repeat(columns - rowChannels.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (programs.isNotEmpty()) item(key = "programs") {
                        SectionLabel(
                            text = stringResource(R.string.search_programs),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(programs, key = { programKey(it) }) { hit ->
                        val program = hit.program
                        val onAir = program.startMillis <= now
                        val reminder = actions.hasReminder(hit.channel, program)
                        val details = listOfNotNull(
                            hit.channel.name,
                            programHours(program, now, withDay = true),
                            programWhen(program, now).takeIf { onAir }
                        ) + programBadges(replay = false, reminder = reminder)
                        MenuRow(
                            label = program.title,
                            subtitle = details.joinToString("  ·  "),
                            icon = if (onAir) Icons.Filled.PlayArrow else Icons.Filled.Notifications,
                            onClick = { if (onAir) onChannelSelected(hit.channel) else actions.toggleReminder(hit.channel, program) },
                            modifier = Modifier.result(programKey(hit))
                        )
                    }
                    if (titles.isNotEmpty()) item(key = "titles") {
                        Column {
                            SectionLabel(
                                text = stringResource(R.string.search_vod),
                                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(titles, key = { titleKey(it) }) { title ->
                                    PosterCard(
                                        title = title,
                                        onClick = { onTitleSelected(title) },
                                        findPoster = findPoster,
                                        modifier = Modifier.result(titleKey(title))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Text field of the search: the TV keyboard's "Search" key (or Down) moves to the results. */
@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_placeholder)
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val accent = LocalAppColors.current.accent
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 18.sp),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                if (down) onSubmit()
                down
            },
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(SurfaceDark)
                    .border(2.dp, if (focused) accent else SurfaceDark, shape)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 18.sp,
                            color = TextSecondary.copy(alpha = 0.6f)
                        )
                    }
                    innerTextField()
                }
            }
        }
    )
}

@Composable
internal fun SearchHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(text = text, fontSize = 15.sp, color = TextSecondary)
    }
}
