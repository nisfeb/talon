package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B3-16: a lapsed login said nothing with Talon behind. Android's lapse
 * watch feeds each repo's signed-out flow through [LapseNotices.heard].
 */
class LapseNoticesTest {
    @Test
    fun `a lapse heard with Talon behind posts the notice once`() {
        val n = LapseNotices()
        assertEquals(LapseStep.None, n.heard("~zod", before = null, now = false, inFront = false), "a new repo's first word")
        assertEquals(LapseStep.Post, n.heard("~zod", before = false, now = true, inFront = false))
        // A rebuilt repo of the ship is refused again: no second notice.
        assertEquals(LapseStep.None, n.heard("~zod", before = null, now = false, inFront = false))
        assertEquals(LapseStep.None, n.heard("~zod", before = false, now = true, inFront = false))
        assertEquals(LapseStep.None, n.heard("~zod", before = null, now = true, inFront = false), "refused before the watch began")
    }

    @Test
    fun `in front the form opens and no notice posts`() {
        val n = LapseNotices()
        assertEquals(LapseStep.None, n.heard("~zod", before = false, now = true, inFront = true))
        // Heard again later with Talon behind, from a rebuilt repo.
        assertEquals(LapseStep.Post, n.heard("~zod", before = null, now = true, inFront = false))
    }

    @Test
    fun `signed in again the notice goes and the next lapse posts again`() {
        val n = LapseNotices()
        n.heard("~zod", before = false, now = true, inFront = false)
        assertEquals(LapseStep.Clear, n.heard("~zod", before = true, now = false, inFront = false), "the same repo took a channel")
        assertEquals(LapseStep.Post, n.heard("~zod", before = false, now = true, inFront = false))
        // Signed in from the form while another ship was open: a new repo, no transition.
        n.signedIn("~zod")
        assertEquals(LapseStep.Post, n.heard("~zod", before = null, now = true, inFront = false))
    }

    @Test
    fun `each ship has its own notice`() {
        val n = LapseNotices()
        assertEquals(LapseStep.Post, n.heard("~zod", before = false, now = true, inFront = false))
        assertEquals(LapseStep.Post, n.heard("~bus", before = false, now = true, inFront = false))
        assertEquals(LapseStep.Clear, n.heard("~bus", before = true, now = false, inFront = false))
        assertEquals(LapseStep.None, n.heard("~zod", before = null, now = true, inFront = false), "~bus signing in leaves ~zod's")
    }

    @Test
    fun `the notice and the sync line say which ship`() {
        assertEquals("Signed out of ~zod" to "Sign in again to keep getting its messages.", signedOutNotice("~zod"))
        assertEquals("Listening for new messages", syncNoticeLine(null))
        assertEquals("Signed out of ~zod", syncNoticeLine("~zod"))
    }
}
