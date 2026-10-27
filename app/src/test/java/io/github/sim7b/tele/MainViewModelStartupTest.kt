package io.github.sim7b.tele

import android.app.Application
import android.content.Context
import android.os.Looper
import io.github.sim7b.tele.data.storage.SecretBox
import io.github.sim7b.tele.data.storage.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * Builds the ViewModel as the app does at startup. A version once crashed there: the first source sync
 * ran from the constructor before a property it used was initialized.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// The Conscrypt of the app is the Android build, without its native library on the JVM
@ConscryptMode(ConscryptMode.Mode.OFF)
class MainViewModelStartupTest {

    private lateinit var app: Application

    // Exceptions escaping the ViewModel coroutines: they crash the app on the device, they would only be
    // logged in a test
    private val uncaught = mutableListOf<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        // On the device the ViewModel is built on the main thread: its first coroutines run during the construction
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousHandler = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught += error }
        app = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SettingsRepository.secretsFactory = { SecretBox({ key }, cbcTransformation = "AES/CBC/PKCS5Padding") }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        Thread.currentThread().uncaughtExceptionHandler = previousHandler
        SettingsRepository.secretsFactory = { SecretBox.androidKeystore() }
    }

    @Test
    fun `starts with several enabled sources`() {
        // Sources saved in plain text by an older version: read, then encrypted at startup
        app.getSharedPreferences("iptv_dispatcharr_prefs", Context.MODE_PRIVATE).edit()
            .putString(
                "dispatcharr_configs_list",
                """[{"id":"one","name":"One","serverUrl":"http://127.0.0.1:9/one.m3u","type":"M3U","isActive":true},
                   {"id":"two","name":"Two","serverUrl":"http://127.0.0.1:9/two.m3u","type":"M3U","isActive":true}]"""
            )
            .commit()

        val viewModel = MainViewModel(app)
        // Runs what the construction posted to the main thread (first source sync, display pipeline)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<Throwable>(), uncaught)
        assertEquals(listOf("one", "two"), viewModel.configRepository.enabledConfigs.value.map { it.id })
        assertTrue(viewModel.channels.value.isEmpty())
    }

    @Test
    fun `the welcome screen does not wait for the XMLTV guide`() {
        val guideReleased = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/list.m3u") {
                    val guide = server.url("/guide.xml")
                    MockResponse.Builder()
                        .body("#EXTM3U url-tvg=\"$guide\"\n#EXTINF:-1 tvg-id=\"tf1\" group-title=\"TNT\",TF1\nhttp://127.0.0.1:9/tf1\n")
                        .build()
                } else {
                    // A guide of tens of MB, still downloading
                    guideReleased.await(30, TimeUnit.SECONDS)
                    MockResponse.Builder().body("<tv></tv>").build()
                }
        }
        server.start()
        try {
            app.getSharedPreferences("iptv_dispatcharr_prefs", Context.MODE_PRIVATE).edit()
                .putString(
                    "dispatcharr_configs_list",
                    """[{"id":"m3u","name":"M3U","serverUrl":"${server.url("/list.m3u")}","type":"M3U","isActive":true}]"""
                )
                .commit()

            val viewModel = MainViewModel(app)
            val deadline = System.currentTimeMillis() + 10_000
            while (!viewModel.initialSyncDone.value && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }

            assertTrue(viewModel.initialSyncDone.value)
            assertEquals(listOf("TF1"), viewModel.channels.value.map { it.name })
            assertEquals(emptyList<Throwable>(), uncaught)
        } finally {
            guideReleased.countDown()
            server.close()
        }
    }

    @Test
    fun `starts without any source`() {
        val viewModel = MainViewModel(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.configRepository.enabledConfigs.value.isEmpty())
        assertEquals(emptyList<Throwable>(), uncaught)
        assertEquals(null, viewModel.loadError.value)
    }
}
