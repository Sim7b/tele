package io.github.sim7b.tele.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasSetTextAction
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.EpgProgram
import io.github.sim7b.tele.data.model.HomeRow
import io.github.sim7b.tele.data.storage.Profile
import io.github.sim7b.tele.data.storage.SecretBox
import io.github.sim7b.tele.data.storage.SettingsRepository
import io.github.sim7b.tele.ui.screens.HomeScreen
import io.github.sim7b.tele.ui.screens.ImmersiveHomeScreen
import io.github.sim7b.tele.ui.screens.ProfileChoice
import io.github.sim7b.tele.R
import io.github.sim7b.tele.ui.theme.LocalAppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import io.github.sim7b.tele.ui.screens.SearchScreen
import io.github.sim7b.tele.ui.screens.SettingsScreen
import io.github.sim7b.tele.ui.theme.TeleTheme
import io.github.sim7b.tele.ui.components.LocalClock
import androidx.compose.runtime.CompositionLocalProvider
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.crypto.KeyGenerator

/**
 * The screenshots of the README (docs/screenshots), drawn on the JVM in French at 1920 x 1080 with demo data.
 * Only written when asked: ./gradlew testDebugUnitTest --tests '*ReadmeScreenshots*' -Ptele.screenshots=docs/screenshots
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "fr-w960dp-h540dp-land-television-xhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReadmeScreenshots {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val folder: File? = System.getProperty("tele.screenshots")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun program(title: String, category: String, start: String, end: String, progress: Float, next: String) =
        EpgProgram(title = title, category = category, startTime = start, endTime = end, progress = progress, nextTitle = next, nextStartTime = end)

    private val channels = listOf(
        Channel("1", "Info 24", "Information", streamUrl = "http://demo.test/1", number = 1,
            currentProgram = program("Le Journal", "Information", "20:00", "20:45", 0.55f, "La Météo")),
        Channel("2", "Monde Info", "Information", streamUrl = "http://demo.test/2", number = 2,
            currentProgram = program("Grand Reportage", "Documentaire", "20:10", "21:05", 0.4f, "Le Débat")),
        Channel("3", "Ciné Club", "Cinéma", streamUrl = "http://demo.test/3", number = 3,
            currentProgram = program("Le Film du soir", "Film", "20:50", "22:40", 0.1f, "Court métrage")),
        Channel("4", "Sport Live", "Sport", streamUrl = "http://demo.test/4", number = 4,
            currentProgram = program("Match en direct", "Football", "20:45", "22:45", 0.15f, "Le Mag du foot")),
        Channel("5", "Ciné Classics", "Cinéma", streamUrl = "http://demo.test/5", number = 5,
            currentProgram = program("Documentaire", "Histoire", "19:30", "20:55", 0.85f, "Magazine")),
        Channel("6", "Kids TV", "Jeunesse", streamUrl = "http://demo.test/6", number = 6,
            currentProgram = program("Dessins animés", "Animation", "20:00", "21:00", 0.5f, "Le Conte")),
        Channel("7", "Musique Plus", "Musique", streamUrl = "http://demo.test/7", number = 7,
            currentProgram = program("Concert", "Musique", "20:30", "22:00", 0.2f, "Clips")),
        Channel("8", "Découverte", "Documentaires", streamUrl = "http://demo.test/8", number = 8,
            currentProgram = program("Les Océans", "Nature", "20:15", "21:10", 0.3f, "Les Volcans"))
    )

    // A photo-like picture (full-screen gradients) as JPEG: a PNG of it passes the 500 kB of the pre-commit hooks
    private fun shoot(name: String, settle: () -> Unit = {}, jpeg: Boolean = false, content: @Composable () -> Unit) {
        assumeTrue("Screenshots are written with -Ptele.screenshots=<folder>", folder != null)
        compose.setContent {
            // The same time on every capture (a fixed evening): the images only change with the screens
            CompositionLocalProvider(LocalClock provides CLOCK) {
                TeleTheme {
                    Box(Modifier.fillMaxSize().background(LocalAppColors.current.background)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        settle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // The window drawn in software: the capture of compose-test waits for a frame Robolectric does not give
        var image: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.rootView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        folder!!.mkdirs()
        val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        File(folder, if (jpeg) "$name.jpg" else "$name.png").outputStream().use { image!!.compress(format, 90, it) }
    }

    @Test
    fun home() = shoot("home") {
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

    @Test
    fun immersive() = shoot("immersive", jpeg = true) {
        ImmersiveHomeScreen(
            channels = channels,
            favorites = channels,
            rows = setOf(HomeRow.CATEGORIES),
            favoriteIds = channels.map { it.id },
            onChannelSelected = {},
            onToggleFavorite = {},
            onHideChannel = {},
            loadStreams = { Result.success(emptyList()) },
            onSelectStream = { _, _ -> },
            automaticStreamDescription = { "" },
            onOpenSettings = {},
            onOpenSearch = {}
        )
    }

    @Test
    fun search() = shoot(
        "search",
        settle = {
            compose.onNode(hasSetTextAction()).performTextInput("ciné")
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        }
    ) {
        SearchScreen(channels = channels, startWithVoice = false, onChannelSelected = {}, onBack = {})
    }

    @Test
    fun settings() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SettingsRepository.secretsFactory = { SecretBox({ key }, cbcTransformation = "AES/CBC/PKCS5Padding") }
        try {
            val repository = SettingsRepository(RuntimeEnvironment.getApplication())
            shoot(
                "settings",
                settle = {
                    // The playback section, its first setting focused
                    repeat(2) { compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionDown) } }
                    compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionRight) }
                }
            ) {
                SettingsScreen(repository = repository, onTestConnection = { Result.failure(Exception()) }, onBack = {})
            }
        } finally {
            SettingsRepository.secretsFactory = { SecretBox.androidKeystore() }
        }
    }

    @Test
    fun profiles() {
        val profiles = listOf(
            Profile(Profile.MAIN, "Alex"),
            Profile("0123456789ab", "Camille", pinSalt = "s", pinHash = "h"),
            Profile("0123456789ac", "Léa", kids = true),
            Profile("0123456789ad", "Invités")
        )
        shoot("profiles") {
            // The picker without its dialog window
            ProfileChoice(profiles, profiles.first(), stringResource(R.string.profile_picker_title), remember { FocusRequester() }) {}
        }
    }

    @Test
    fun sport() {
        val at = CLOCK.millis()
        val hour = 3_600_000L
        fun side(name: String, short: String, score: String? = null) = io.github.sim7b.tele.data.sport.SportSide(name, short, null, score)
        fun row(rank: Int, name: String, played: String, record: String, points: String) =
            io.github.sim7b.tele.data.sport.SportStanding(rank, name, null, null, played, points, record, null)
        val ligue1 = io.github.sim7b.tele.data.sport.SportCompetition(
            "football/france/ligue1", "Ligue 1", null,
            listOf(
                io.github.sim7b.tele.data.sport.SportFixture("1", at - hour / 2, io.github.sim7b.tele.data.sport.SportFixture.State.LIVE, null, side("Paris Saint-Germain", "PSG", "2"), side("Marseille", "OM", "1")),
                io.github.sim7b.tele.data.sport.SportFixture("2", at + 22 * hour, io.github.sim7b.tele.data.sport.SportFixture.State.UPCOMING, null, side("Lyon", "OL"), side("AS Monaco", "ASM")),
                io.github.sim7b.tele.data.sport.SportFixture("3", at + 44 * hour, io.github.sim7b.tele.data.sport.SportFixture.State.UPCOMING, null, side("Lille", "LOSC"), side("Lens", "RCL")),
                io.github.sim7b.tele.data.sport.SportFixture("4", at - 26 * hour, io.github.sim7b.tele.data.sport.SportFixture.State.FINISHED, null, side("Nice", "OGCN", "0"), side("Rennes", "SRFC", "2"))
            ),
            listOf(io.github.sim7b.tele.data.sport.SportTable(null, listOf(
                row(1, "Paris Saint-Germain", "13", "10-1-2", "31"), row(2, "Marseille", "13", "8-3-2", "27"),
                row(3, "AS Monaco", "13", "7-4-2", "25"), row(4, "Lille", "13", "7-2-4", "23"), row(5, "Lens", "13", "6-4-3", "22"),
                row(6, "Lyon", "13", "6-3-4", "21"), row(7, "Nice", "13", "5-4-4", "19"), row(8, "Rennes", "13", "5-3-5", "18")
            )))
        )
        val f1 = io.github.sim7b.tele.data.sport.SportCompetition("motorsport/f1", "F1", null, emptyList(), emptyList())
        val top14 = io.github.sim7b.tele.data.sport.SportCompetition("rugby/top14", "Top 14", null, emptyList(), emptyList())
        val live = io.github.sim7b.tele.data.sport.SportEvent(channels[3],
            io.github.sim7b.tele.data.model.GuideProgram("p", "4", "Ligue 1 : Paris SG - Marseille", startTimeIso = "", endTimeIso = "", startMillis = at - hour / 2, endMillis = at + 90 * 60_000L))
        val coming = io.github.sim7b.tele.data.sport.SportEvent(channels[2],
            io.github.sim7b.tele.data.model.GuideProgram("q", "3", "Lyon - Monaco", startTimeIso = "", endTimeIso = "", startMillis = at + 22 * hour, endMillis = at + 24 * hour))
        shoot("sport") {
            io.github.sim7b.tele.ui.screens.SportScreen(
                competitions = listOf(ligue1, f1, top14),
                loading = false,
                available = true,
                guideEvents = listOf(live, coming),
                followedTeams = io.github.sim7b.tele.data.sport.SportBoard.followedTeams(listOf("@football/france/ligue1/olympique-de-marseille")),
                hasReminder = { it == coming },
                onOpenEvent = {},
                onBack = {}
            )
        }
    }

    private companion object {
        val CLOCK: Clock = ZonedDateTime.of(2026, 11, 16, 20, 35, 0, 0, ZoneId.of("Europe/Paris"))
            .let { Clock.fixed(it.toInstant(), it.zone) }
    }
}
