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
    fun `starts without any source`() {
        val viewModel = MainViewModel(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.configRepository.enabledConfigs.value.isEmpty())
        assertEquals(emptyList<Throwable>(), uncaught)
        assertEquals(null, viewModel.loadError.value)
    }
}
