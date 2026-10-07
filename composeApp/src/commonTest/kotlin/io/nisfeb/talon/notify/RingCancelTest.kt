package io.nisfeb.talon.notify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * sneagan, 2026-10-07: "add the ring-cancel fix too". The ship's trunk
 * cancels on every device it has; a "hangup" cancel for a call this
 * phone never rang ended whatever call it was in (Android hands any id
 * the 1:1 call's controls).
 */
class RingCancelTest {
    @Test
    fun a_cancel_is_ours_for_the_ring_we_show_or_the_call_we_are_in() {
        assertTrue(ringCancelIsOurs("c1", "c1", null, null), "the ring on screen")
        assertTrue(ringCancelIsOurs("c1", null, "c1", null), "the call we are in")
        assertTrue(ringCancelIsOurs("c1", null, null, "c1"), "a call telecom holds")
    }

    @Test
    fun a_cancel_for_a_call_we_never_rang_is_not() {
        assertFalse(ringCancelIsOurs("c2", "c1", "c3", null), "another call in progress stays up")
        assertFalse(ringCancelIsOurs("c2", null, null, null), "nothing ringing, nothing to end")
        assertFalse(ringCancelIsOurs("", "", null), "no id is no call")
    }
}
