package io.github.sim7b.tele.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntroSkipTest {

    private val episode = 45 * 60_000L

    @Test
    fun `the learned end of the intro is offered until a little before it, always the same target`() {
        assertEquals(95_000L, introSkipTarget(10_000, episode, learnedEndMs = 95_000))
        assertEquals(95_000L, introSkipTarget(60_000, episode, learnedEndMs = 95_000))
        assertNull(introSkipTarget(93_000, episode, learnedEndMs = 95_000))
        // Not in the first seconds, nor in a short clip
        assertNull(introSkipTarget(2_000, episode, learnedEndMs = 95_000))
        assertNull(introSkipTarget(10_000, 5 * 60_000L, learnedEndMs = 95_000))
    }

    @Test
    fun `nothing is offered before an intro was learned`() {
        assertNull(introSkipTarget(30_000, episode, learnedEndMs = null))
    }

    @Test
    fun `a jump forward at the start of an episode is learned`() {
        assertEquals(100_000L, learnedIntroEnd(20_000, 100_000, episode))
        // Too short, too long, too late, backwards, or a short clip: not an intro
        assertNull(learnedIntroEnd(20_000, 30_000, episode))
        assertNull(learnedIntroEnd(0, 4 * 60_000L, episode))
        assertNull(learnedIntroEnd(20 * 60_000L, 22 * 60_000L, episode))
        assertNull(learnedIntroEnd(100_000, 20_000, episode))
        assertNull(learnedIntroEnd(20_000, 100_000, 5 * 60_000L))
        // Resuming the episode further, then jumping: not an intro
        assertNull(learnedIntroEnd(4 * 60_000L, 4 * 60_000L + 30_000, episode))
    }

    @Test
    fun `a jump right after the learned one adjusts it`() {
        // Too far, back 10 s
        assertEquals(90_000L, learnedIntroEnd(100_000, 90_000, episode, previousEndMs = 100_000))
        // Not far enough, 10 s more
        assertEquals(110_000L, learnedIntroEnd(101_000, 110_000, episode, previousEndMs = 100_000))
        // Later in the episode: not an adjustment
        assertNull(learnedIntroEnd(200_000, 190_000, episode, previousEndMs = 100_000))
    }
}
