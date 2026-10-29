package io.nisfeb.talon.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a tapped notice opens, the same in both shells. Android read no
 * tag at all, so a tapped Activity row there opened nothing.
 */
class NoticeLandingTest {
    @Test
    fun `each notice NoticeRepo records lands on its own place`() {
        assertEquals(NoticeLanding.Actions, noticeLanding("orrery:a1"))
        assertEquals(NoticeLanding.Loops, noticeLanding("loop:7"))
        assertEquals(NoticeLanding.Invites, noticeLanding("invite:~bus/crew"))
        assertEquals(NoticeLanding.Assistant, noticeLanding("armillary"))
        assertEquals(NoticeLanding.Chat("~bus"), noticeLanding("dm:~bus"))
    }

    // iOS hands a tapped ship push's tag here: orrery's action pushes,
    // the late nag's among them, open Actions, where the Late section leads.
    @Test
    fun `the ship's push about an action lands on Actions`() {
        assertEquals(NoticeLanding.Actions, noticeLanding("orrery-a1"))
        assertEquals(NoticeLanding.Actions, noticeLanding("orrery-overdue-a1"))
        assertNull(noticeLanding("orrery-nudge-3"))
        assertNull(noticeLanding("orrery-review"))
    }

    // Orrery 130's daily push to do the evening review is about no one
    // action: read as one, it showed under Actions as an action "evening".
    @Test
    fun `the evening review's push is not an action`() {
        assertNull(io.nisfeb.talon.orrery.actionIdOfTag("orrery-evening"))
        assertEquals("evening-walk", io.nisfeb.talon.orrery.actionIdOfTag("orrery-evening-walk"), "an action named so is still one")
        assertEquals(null, noticeLanding("orrery-evening"))
    }

    @Test
    fun `a calendar reminder keeps its whole tag, to find its occurrence`() {
        assertEquals(NoticeLanding.Calendar("cal-ev1-170"), noticeLanding("cal-ev1-170"))
    }

    @Test
    fun `a tag nothing opens, or a DM request naming nobody, lands nowhere`() {
        assertNull(noticeLanding("dm:"))
        assertNull(noticeLanding("armillary:more"))
        assertNull(noticeLanding("mail:t1"))
        assertNull(noticeLanding(""))
    }
}
