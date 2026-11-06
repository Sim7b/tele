package io.github.sim7b.tele.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntroSkipTest {

    private val episode = 45 * 60_000L

    @Test
    fun `the learned end of the intro is offered until a little before it`() {
        assertEquals(95_000L, introSkipTarget(10_000, episode, learnedEndMs = 95_000))
        assertNull(introSkipTarget(93_000, episode, learnedEndMs = 95_000))
        // Not in the first seconds, nor in a short clip
        assertNull(introSkipTarget(2_000, episode, learnedEndMs = 95_000))
        assertNull(introSkipTarget(10_000, 5 * 60_000L, learnedEndMs = 95_000))
    }

    @Test
    fun `without a learned end a default jump is offered in the first minutes`() {
        assertEquals(30_000L + DEFAULT_INTRO_SKIP_MS, introSkipTarget(30_000, episode, learnedEndMs = null))
        assertNull(introSkipTarget(3 * 60_000L, episode, learnedEndMs = null))
    }

    @Test
    fun `a jump forward in the opening minutes is learned`() {
        assertEquals(100_000L, learnedIntroEnd(20_000, 100_000, episode))
        // Too short, too late, backwards, or a short clip: not an intro
        assertNull(learnedIntroEnd(20_000, 30_000, episode))
        assertNull(learnedIntroEnd(20 * 60_000L, 22 * 60_000L, episode))
        assertNull(learnedIntroEnd(100_000, 20_000, episode))
        assertNull(learnedIntroEnd(20_000, 100_000, 5 * 60_000L))
    }
}
