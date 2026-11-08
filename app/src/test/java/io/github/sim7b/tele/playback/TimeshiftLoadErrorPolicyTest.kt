package io.github.sim7b.tele.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class TimeshiftLoadErrorPolicyTest {

    private fun info(error: IOException, count: Int = 1) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(0, DataSpec(Uri.parse("timeshift://live/0")), 0),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        error,
        count
    )

    @Test
    fun `a failure of the recording is never loaded again from its start`() {
        // The provider stream dropped: the player would replay the recording from the beginning
        val dropped = HttpDataSource.HttpDataSourceException(
            DataSpec(Uri.parse("http://dispatcharr/proxy/ts/stream/1")),
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
        assertEquals(C.TIME_UNSET, TimeshiftLoadErrorPolicy.getRetryDelayMsFor(info(dropped)))
        assertEquals(C.TIME_UNSET, TimeshiftLoadErrorPolicy.getRetryDelayMsFor(info(IOException("Timeshift position deleted"), count = 3)))
    }
}
