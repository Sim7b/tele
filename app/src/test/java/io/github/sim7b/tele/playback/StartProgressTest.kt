package io.github.sim7b.tele.playback

import io.github.sim7b.tele.data.remote.ChannelStartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartProgressTest {

    private fun state(state: String?, since: Double? = 1.0, stream: Long? = 7, chunks: Int = 0) =
        ChannelStartState(state, since, stream, chunks)

    @Test
    fun `a quick start shows nothing`() {
        assertNull(StartProgress.step(state("connecting"), null, 1_000))
        assertNull(StartProgress.step(null, null, 3_000))
    }

    @Test
    fun `the step follows what Dispatcharr does`() {
        assertEquals(StartStep.PREPARING, StartProgress.step(state("initializing"), null, 3_000))
        assertEquals(StartStep.CONNECTING, StartProgress.step(state("connecting", since = 2.0), 7, 3_000))
        assertEquals(StartStep.PROVIDER_SLOW, StartProgress.step(state("connecting", since = 5.0), 7, 8_000))
        assertEquals(StartStep.CONNECTING, StartProgress.step(state("connecting", since = 5.0, chunks = 2), 7, 8_000))
        assertEquals(StartStep.STARTING, StartProgress.step(state("waiting_for_clients"), 7, 9_000))
        assertEquals(StartStep.PROVIDER_SLOW, StartProgress.step(state("buffering"), 7, 9_000))
    }

    @Test
    fun `a new stream on the server is a switch`() {
        assertEquals(StartStep.SWITCHING, StartProgress.step(state("connecting", stream = 8), 7, 10_000))
        assertEquals(StartStep.CONNECTING, StartProgress.step(state("connecting", stream = 8), 8, 10_000))
    }

    @Test
    fun `without the plugin a long wait is told after a while`() {
        assertNull(StartProgress.step(state(null), null, 5_000))
        assertEquals(StartStep.WAITING, StartProgress.step(state(null), null, 6_000))
        assertEquals(StartStep.WAITING, StartProgress.step(null, null, 7_000))
        assertEquals(StartStep.WAITING, StartProgress.step(state("weird"), null, 7_000))
    }
}
