package io.github.sim7b.tele.ui

import android.app.Application
import androidx.compose.ui.input.key.Key
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
import io.github.sim7b.tele.ui.screens.SearchScreen
import io.github.sim7b.tele.ui.vod.VodCategoryPicker
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
}
