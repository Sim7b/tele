package io.github.sim7b.tele.ui

import android.app.Application
import androidx.compose.ui.input.key.Key
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.sim7b.tele.data.storage.SecretBox
import io.github.sim7b.tele.data.storage.SettingsRepository
import io.github.sim7b.tele.ui.components.ignoreHeldConfirm
import io.github.sim7b.tele.ui.components.requestFocusWithRetry
import io.github.sim7b.tele.ui.screens.SettingsScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.robolectric.RuntimeEnvironment
import javax.crypto.KeyGenerator
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.vod.VodCategory
import io.github.sim7b.tele.data.vod.VodKind
import io.github.sim7b.tele.data.vod.VodSelection
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.ui.screens.HomeScreen
import io.github.sim7b.tele.ui.screens.ImmersiveHomeScreen
import io.github.sim7b.tele.data.model.HomeRow
import io.github.sim7b.tele.data.model.EpgProgram
import io.github.sim7b.tele.ui.screens.SearchScreen
import io.github.sim7b.tele.ui.vod.VodCategoryPicker
import io.github.sim7b.tele.ui.vod.VodTitleActions
import io.github.sim7b.tele.ui.vod.nextResumeKey
import io.github.sim7b.tele.data.vod.VodTitle
import io.github.sim7b.tele.data.storage.VodProgress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Focus with the remote on screens of the app: where it starts, and where the arrows take it. Run on the
 * JVM (Robolectric), the screens built with test data.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// A bare application: the app's own starts its services
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RemoteNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun channel(id: String, name: String) = Channel(id = id, name = name, group = "News", streamUrl = "http://example.test/$id")

    @Test
    fun `search starts in the field and Down goes to the first result`() {
        compose.setContent {
            SearchScreen(
                channels = listOf(channel("1", "Info Monde"), channel("2", "Info Sport"), channel("3", "Cinema")),
                startWithVoice = false,
                onChannelSelected = {},
                onBack = {}
            )
        }
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction())
        field.assertIsFocused()

        field.performTextInput("info")
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyDescendant(hasText("Info Monde")), useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    fun `the immersive home starts on the first favorite, shows its program, and Down goes to the next row`() {
        val news = (1..3).map { channel("$it", "Channel $it").copy(currentProgram = EpgProgram(title = "Program $it", startTime = "20:00", endTime = "21:00")) }
        val movies = (4..9).map { channel("$it", "Channel $it").copy(group = "Movies") }
        var opened: Channel? = null
        compose.setContent {
            ImmersiveHomeScreen(
                channels = news + movies,
                // More favorites than fit on the screen: the row scrolls
                favorites = news + movies,
                rows = setOf(HomeRow.CATEGORIES),
                favoriteIds = (news + movies).map { it.id },
                onChannelSelected = { opened = it },
                onToggleFavorite = {},
                onHideChannel = {},
                loadStreams = { Result.success(emptyList()) },
                onSelectStream = { _, _ -> },
                automaticStreamDescription = { "" },
                onOpenSettings = {},
                onOpenSearch = {}
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyDescendant(hasText("Channel 1")), useUnmergedTree = true).assertExists()
        // The focused program over the screen
        compose.onAllNodes(hasText("Program 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let { assertEquals(true, it) }
        repeat(8) { compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) } }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyDescendant(hasText("Channel 9")), useUnmergedTree = true).assertExists()
        // Down: the first category row ("News")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals("News", opened?.group)
    }

    @Test
    fun `the home starts on the first favorite and Down goes to the selected category (all the channels)`() {
        val channels = (1..6).map { channel("$it", "Channel $it") }
        compose.setContent {
            HomeScreen(
                channels = channels,
                favorites = channels.take(4),
                hasSource = true,
                favoriteIds = channels.take(4).map { it.id },
                isLoading = false,
                onChannelSelected = {},
                onToggleFavorite = {},
                onHideChannel = {},
                loadStreams = { Result.success(emptyList()) },
                onSelectStream = { _, _ -> },
                onMoveFavorite = { _, _ -> },
                onHideCategory = {},
                automaticStreamDescription = { "" },
                onOpenSettings = {},
                onOpenSearch = {},
                onRefresh = {}
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyDescendant(hasText("Channel 1")), useUnmergedTree = true).assertExists()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyDescendant(hasText("All")), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a long press on a movie of the home resume row opens its actions`() {
        val channels = (1..4).map { channel("$it", "Channel $it") }
        val movie = VodTitle("m1", VodKind.MOVIE, "Movie One", 2020, null, null, null, emptyList(), null, null, null, null, emptyList())
        var removed: String? = null
        val actions = VodTitleActions(
            toggleMyList = {},
            toggleInCollection = { _, _ -> },
            createCollectionWith = { _, _ -> },
            removeFromContinue = { removed = it },
            setWatched = { _, _ -> }
        )
        compose.setContent {
            HomeScreen(
                channels = channels,
                favorites = channels,
                hasSource = true,
                favoriteIds = channels.map { it.id },
                isLoading = false,
                onChannelSelected = {},
                onToggleFavorite = {},
                onHideChannel = {},
                loadStreams = { Result.success(emptyList()) },
                onSelectStream = { _, _ -> },
                onMoveFavorite = { _, _ -> },
                onHideCategory = {},
                automaticStreamDescription = { "" },
                onOpenSettings = {},
                onOpenSearch = {},
                onRefresh = {},
                resume = listOf(movie to VodProgress("m1", "m1", 60_000, 600_000, 1)),
                resumeActions = actions
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Up from the first favorite: the resume card
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyDescendant(hasText("Movie One")), useUnmergedTree = true).assertExists()
        // OK held: its repeat flagged as a long press, then the release
        val root = compose.onAllNodes(isRoot())[0]
        root.performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_CENTER, 0)))
        root.performKeyPress(
            KeyEvent(NativeKeyEvent(0, 600, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_CENTER, 1, 0, 0, 0, NativeKeyEvent.FLAG_LONG_PRESS))
        )
        root.performKeyPress(KeyEvent(NativeKeyEvent(0, 700, NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DPAD_CENTER, 0)))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNode(hasText("Remove from “Continue watching”"), useUnmergedTree = true).assertExists()
        // Down to the action, a new OK
        val dialog = compose.onAllNodes(isRoot()).onLast()
        repeat(2) { dialog.performKeyInput { pressKey(Key.DirectionDown) } }
        compose.waitForIdle()
        dialog.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals("m1", removed)
    }

    @Test
    fun `a card taken out of the resume row gives the focus to the next one, else the one before`() {
        assertEquals("c", nextResumeKey(listOf("a", "b", "c"), "b"))
        assertEquals("b", nextResumeKey(listOf("a", "b", "c"), "c"))
        assertEquals("a", nextResumeKey(listOf("a", "b"), "x"))
        assertEquals(null, nextResumeKey(listOf("a"), "a"))
    }

    @Test
    fun `the category picker starts on its load button and a category is checked with OK`() {
        var chosen: VodSelection? = null
        val categories = listOf(VodCategory(VodKind.MOVIE, "1", "FR | FILMS"), VodCategory(VodKind.MOVIE, "2", "EN | MOVIES"))
        compose.setContent {
            VodCategoryPicker(
                source = SourceConfig(id = "s", name = "Source", type = SourceType.XTREAM, serverUrl = "http://example.test"),
                current = VodSelection(all = false, keys = setOf("MOVIE:1")),
                loadCategories = { categories },
                onChoose = { chosen = it },
                onBack = {}
            )
        }
        // The categories are read off the main thread: their load button, then its focus
        compose.waitUntil(5_000) {
            compose.onAllNodes(isFocused() and hasAnyDescendant(hasText("Load 1 category")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Load button first (focused), then OK: the current choice, with the categories known
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(VodSelection(all = false, keys = setOf("MOVIE:1"), known = setOf("MOVIE:1", "MOVIE:2")), chosen)
    }

    @Test
    fun `a menu opened by a long press ignores the held OK until a new press`() {
        var clicks = 0
        compose.setContent {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocusWithRetry() }
            Box(modifier = Modifier.ignoreHeldConfirm()) {
                Button(onClick = { clicks++ }, modifier = Modifier.focusRequester(focus)) { Text("Open") }
            }
        }
        compose.waitForIdle()
        val root = compose.onAllNodes(isRoot())[0]
        // The repeats and the release of the long press that opened it
        root.performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_CENTER, 5)))
        root.performKeyPress(KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DPAD_CENTER)))
        compose.waitForIdle()
        assertEquals(0, clicks)
        // A new press
        root.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    fun `in the settings a row opens its choices on the current value, Back comes back to the row`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SettingsRepository.secretsFactory = { SecretBox({ key }, cbcTransformation = "AES/CBC/PKCS5Padding") }
        try {
            val app = RuntimeEnvironment.getApplication()
            val repository = SettingsRepository(app)
            val current = app.getString(repository.qualityPreference.value.labelRes)
            compose.setContent {
                SettingsScreen(repository = repository, onTestConnection = { Result.failure(Exception()) }, onBack = {})
            }
            compose.waitForIdle()
            compose.onNode(isFocused() and hasAnyDescendant(hasText("Sources")), useUnmergedTree = true).assertExists()

            // Down to Playback, → to its first setting
            repeat(2) { compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionDown) } }
            compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
            compose.onNode(isFocused() and hasAnyDescendant(hasText("Stream quality")), useUnmergedTree = true).assertExists()

            // OK: the panel, on the current value
            compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(5_000) {
                compose.onAllNodes(isFocused() and hasAnyDescendant(hasText(current)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Back: the row again
            compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Back) }
            compose.waitUntil(5_000) {
                compose.onAllNodes(isFocused() and hasAnyDescendant(hasText("Stream quality")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            SettingsRepository.secretsFactory = { SecretBox.androidKeystore() }
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    fun `the settings opened from the movies and series start on their first setting`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SettingsRepository.secretsFactory = { SecretBox({ key }, cbcTransformation = "AES/CBC/PKCS5Padding") }
        try {
            val repository = SettingsRepository(RuntimeEnvironment.getApplication())
            compose.setContent {
                SettingsScreen(repository = repository, onTestConnection = { Result.failure(Exception()) }, startOnVod = true, onBack = {})
            }
            // The switch of the movies and series, whichever its state
            compose.waitUntil(5_000) {
                compose.onAllNodes(isFocused() and hasAnyDescendant(hasText("Movies and series button", substring = true)), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(isFocused() and hasAnyDescendant(hasText("Live TV only")), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            SettingsRepository.secretsFactory = { SecretBox.androidKeystore() }
        }
    }
}
