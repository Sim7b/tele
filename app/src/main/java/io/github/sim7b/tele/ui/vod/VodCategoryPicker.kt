package io.github.sim7b.tele.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.vod.VodCategories
import io.github.sim7b.tele.data.vod.VodCategory
import io.github.sim7b.tele.data.vod.VodCategoryHint
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodLanguageGuess
import io.github.sim7b.tele.data.vod.VodSelection
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.theme.LiveRed
import io.github.sim7b.tele.ui.theme.TextPrimary
import io.github.sim7b.tele.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Categories of a source with what their names tell, read off the main thread. */
private class PickerData(
    val categories: List<VodCategory>,
    val hints: Map<String, VodCategoryHint>,
    // Countries and languages found, the most frequent first
    val countries: List<String>,
    val audio: List<String>,
    val subtitles: List<String>
)

private const val COLUMNS = 4
private const val MAX_CHOICES = 16

/**
 * Choice of the movies and series to load from a source: countries and audio / subtitle languages
 * (read from the category names) check the matching categories, which can then be changed one by one.
 * Only the categories checked are loaded. Shown at the first opening, and from the settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VodCategoryPicker(
    source: SourceConfig,
    current: VodSelection?,
    loadCategories: suspend () -> List<VodCategory>,
    onChoose: (VodSelection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    var attempt by remember { mutableIntStateOf(0) }
    var data by remember(source.id) { mutableStateOf<PickerData?>(null) }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    val errorText = stringResource(R.string.vod_categories_error)
    LaunchedEffect(source.id, attempt) {
        error = null
        try {
            val categories = loadCategories()
            data = withContext(Dispatchers.Default) { pickerData(categories) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = errorText
        }
    }

    var countries by remember(source.id) { mutableStateOf(emptySet<String>()) }
    var audio by remember(source.id) { mutableStateOf(emptySet<String>()) }
    var subtitles by remember(source.id) { mutableStateOf(emptySet<String>()) }
    var checked by remember(source.id) { mutableStateOf(emptySet<String>()) }
    // Checked first, ordered again only when the choice by country or language changes (not on each toggle)
    var order by remember(source.id) { mutableStateOf(emptyList<VodCategory>()) }
    // First display: the current choice, else the categories in the language of the TV (when there are)
    LaunchedEffect(data) {
        val loaded = data ?: return@LaunchedEffect
        // No movies nor series in this source: nothing to choose
        if (loaded.categories.isEmpty()) {
            onChoose(VodSelection.NONE)
            return@LaunchedEffect
        }
        val all = loaded.categories.map { it.key }
        checked = when {
            current?.all == true -> all.toSet()
            current != null -> current.keys.intersect(all.toSet())
            else -> {
                val language = Locale.getDefault().language
                if (language in loaded.audio) {
                    audio = setOf(language)
                    VodCategories.matching(loaded.categories, loaded.hints, emptySet(), audio, emptySet())
                } else {
                    emptySet()
                }
            }
        }
        order = loaded.categories.sortedBy { it.key !in checked }
    }
    fun applyFilters() {
        val loaded = data ?: return
        checked = VodCategories.matching(loaded.categories, loaded.hints, countries, audio, subtitles)
        order = loaded.categories.sortedBy { it.key !in checked }
    }

    val loadFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(data != null, error != null) {
        when {
            data != null -> loadFocus.requestFocusWithRetry()
            error != null -> retryFocus.requestFocusWithRetry()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text(text = stringResource(R.string.vod_categories_title), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(text = source.name, fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
                Text(
                    text = stringResource(R.string.vod_categories_description),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp).widthIn(max = 720.dp)
                )
            }
        }

        val loaded = data
        if (loaded == null) {
            item {
                if (error != null) {
                    Column {
                        Text(text = error.orEmpty(), fontSize = 14.sp, color = LiveRed)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VodChip(label = stringResource(R.string.vod_categories_retry), selected = false, onClick = { attempt++ }, modifier = Modifier.focusRequester(retryFocus))
                            VodChip(label = stringResource(R.string.vod_categories_skip), selected = false, onClick = { onChoose(VodSelection.NONE) })
                            VodChip(label = stringResource(R.string.vod_categories_cancel), selected = false, onClick = onBack)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
                        CircularProgressIndicator(color = TextSecondary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = stringResource(R.string.vod_categories_loading), fontSize = 14.sp, color = TextSecondary)
                    }
                }
            }
            return@LazyColumn
        }

        // Actions first: hundreds of categories may follow
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val all = loaded.categories.size
                VodChip(
                    label = pluralStringResource(R.plurals.vod_categories_load, checked.size, checked.size),
                    selected = true,
                    onClick = {
                        if (checked.isNotEmpty()) {
                            onChoose(if (checked.size == all) VodSelection.ALL else VodSelection(all = false, keys = checked))
                        }
                    },
                    modifier = Modifier.focusRequester(loadFocus)
                )
                VodChip(label = stringResource(R.string.settings_select_all), selected = false, onClick = { checked = loaded.categories.mapTo(LinkedHashSet()) { it.key } })
                VodChip(label = stringResource(R.string.settings_select_none), selected = false, onClick = { checked = emptySet() })
                VodChip(label = stringResource(R.string.vod_categories_skip), selected = false, onClick = { onChoose(VodSelection.NONE) })
                if (current != null) VodChip(label = stringResource(R.string.vod_categories_cancel), selected = false, onClick = onBack)
            }
        }
        if (loaded.countries.isNotEmpty()) {
            item {
                ChoiceRow(stringResource(R.string.vod_categories_countries), loaded.countries, countries, ::countryName) { code ->
                    countries = if (code in countries) countries - code else countries + code
                    applyFilters()
                }
            }
        }
        if (loaded.audio.isNotEmpty()) {
            item {
                ChoiceRow(stringResource(R.string.vod_filter_audio), loaded.audio, audio, { languageName(it) ?: it }) { code ->
                    audio = if (code in audio) audio - code else audio + code
                    applyFilters()
                }
            }
        }
        if (loaded.subtitles.isNotEmpty()) {
            item {
                ChoiceRow(stringResource(R.string.vod_filter_subtitles), loaded.subtitles, subtitles, { languageName(it) ?: it }) { code ->
                    subtitles = if (code in subtitles) subtitles - code else subtitles + code
                    applyFilters()
                }
            }
        }

        listOf(VodKind.MOVIE to R.string.vod_tab_movies, VodKind.SERIES to R.string.vod_tab_series).forEach { (kind, labelRes) ->
            val ofKind = order.ifEmpty { loaded.categories }.filter { it.kind == kind }
            if (ofKind.isEmpty()) return@forEach
            item {
                Text(
                    text = stringResource(labelRes) + "  ·  " + ofKind.count { it.key in checked } + " / " + ofKind.size,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            items(ofKind.chunked(COLUMNS)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { category ->
                        val isChecked = category.key in checked
                        VodChip(
                            label = (if (isChecked) "✓  " else "") + category.name.ifBlank { stringResource(R.string.group_other) },
                            selected = isChecked,
                            onClick = { checked = if (isChecked) checked - category.key else checked + category.key },
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                    }
                    repeat(COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: Set<String>,
    name: (String) -> String,
    onToggle: (String) -> Unit
) {
    Column {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { code ->
                VodChip(label = (if (code in selected) "✓  " else "") + name(code), selected = code in selected, onClick = { onToggle(code) })
            }
        }
    }
}

/** Hints of every category and the choices they offer, the most frequent first. */
private fun pickerData(categories: List<VodCategory>): PickerData {
    val estimator = VodLanguageGuess.Estimator()
    val hints = categories.associate { it.key to VodCategories.hint(it.name, estimator) }
    fun ranked(values: List<String>) = values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(MAX_CHOICES)
    return PickerData(
        categories = categories,
        hints = hints,
        countries = ranked(hints.values.mapNotNull { it.country }),
        audio = ranked(hints.values.flatMap { it.languages.audio }),
        subtitles = ranked(hints.values.flatMap { it.languages.subtitles })
    )
}
