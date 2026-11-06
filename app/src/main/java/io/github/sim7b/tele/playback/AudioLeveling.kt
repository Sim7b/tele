package io.github.sim7b.tele.playback

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import io.github.sim7b.tele.R

/**
 * Loudness of the sound: as broadcast, evened out (loud channels and adverts brought down, quiet ones up),
 * or night mode (whispers and explosions close to each other). Applied by Android to the decoded sound
 * (not to a sound passed through as is to an amplifier).
 */
enum class AudioLeveling(@param:StringRes val labelRes: Int, @param:StringRes val descriptionRes: Int, val dynamics: Dynamics?) {
    OFF(R.string.audio_leveling_off, R.string.audio_leveling_off_description, null),
    NORMAL(R.string.audio_leveling_normal, R.string.audio_leveling_normal_description, Dynamics(thresholdDb = -24f, ratio = 3f, gainDb = 6f)),
    NIGHT(R.string.audio_leveling_night, R.string.audio_leveling_night_description, Dynamics(thresholdDb = -34f, ratio = 8f, gainDb = 12f));

    /**
     * A compressor over the whole sound: above [thresholdDb] the level rises [ratio] times less, then
     * [gainDb] brings the quiet parts up; a limiter keeps the peaks under -1 dB.
     */
    data class Dynamics(val thresholdDb: Float, val ratio: Float, val gainDb: Float) {
        val attackMs = 20f
        val releaseMs = 300f
        val limiterThresholdDb = -1f
    }
}

/**
 * The [AudioLeveling] effect on an audio session of the player (Android 9 and later); [apply] again when
 * the session or the choice changes. Effects a device refuses are left out: the sound plays as broadcast.
 */
class AudioLevelingEffect {

    private var effect: Any? = null
    private var session = 0
    private var leveling = AudioLeveling.OFF

    fun apply(audioSessionId: Int, choice: AudioLeveling) {
        if (audioSessionId == session && choice == leveling && (effect != null || choice.dynamics == null)) return
        release()
        session = audioSessionId
        leveling = choice
        val dynamics = choice.dynamics ?: return
        if (audioSessionId == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        effect = try {
            create(audioSessionId, dynamics)
        } catch (e: Exception) {
            Log.w(TAG, "Audio leveling unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (effect as? DynamicsProcessing)?.release()
        effect = null
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun create(audioSessionId: Int, dynamics: AudioLeveling.Dynamics): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            false, 0, // no equalizer before
            true, 1, // one-band compressor
            false, 0, // no equalizer after
            true // limiter
        ).build()
        val band = DynamicsProcessing.MbcBand(
            true, 20_000f, dynamics.attackMs, dynamics.releaseMs, dynamics.ratio, dynamics.thresholdDb,
            KNEE_DB, -90f, 1f, 0f, dynamics.gainDb
        )
        config.setMbcBandAllChannelsTo(0, band)
        config.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, dynamics.limiterThresholdDb, 0f)
        )
        return DynamicsProcessing(0, audioSessionId, config).apply { enabled = true }
    }

    private companion object {
        const val TAG = "AudioLeveling"
        // Up to 7.1 sound decoded
        const val CHANNELS = 8
        const val KNEE_DB = 6f
    }
}
