package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a swipe back that lets go goes back, and when the screen springs back instead. */
class BackSwipeCommitTest {
    @Test
    fun `past a third of the way it goes back, short of it the screen springs back`() {
        assertTrue(backSwipeCommits(0.5f, 0f))
        assertTrue(backSwipeCommits(BACK_COMMIT_FRACTION, 0f))
        assertFalse(backSwipeCommits(0.2f, 0f))
        assertFalse(backSwipeCommits(0.34f, 100f), "a slow drag short of the mark")
    }

    @Test
    fun `a flick decides, either way`() {
        assertTrue(backSwipeCommits(0.1f, BACK_FLICK_DP_PER_SEC), "a short fast flick right goes back")
        assertFalse(backSwipeCommits(0.8f, -BACK_FLICK_DP_PER_SEC), "flung back left, it stays")
        assertFalse(backSwipeCommits(0f, 2_000f), "a flick that went nowhere")
    }
}
