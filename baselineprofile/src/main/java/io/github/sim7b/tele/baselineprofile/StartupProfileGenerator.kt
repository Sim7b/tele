package io.github.sim7b.tele.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The code run when the app starts and the remote moves through the home and the settings, compiled ahead of
 * time on the TVs (startup and first scrolls without waiting for the JIT). No source is configured on the
 * emulator: the start, the welcome, the style choice and the empty home are what is recorded.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = "io.github.sim7b.tele", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        // Style choice of the first launch, then the home
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        device.waitForIdle()
        repeat(3) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            device.waitForIdle()
        }
        repeat(3) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            device.waitForIdle()
        }
        device.pressKeyCode(KeyEvent.KEYCODE_BACK)
        device.waitForIdle()
    }
}
