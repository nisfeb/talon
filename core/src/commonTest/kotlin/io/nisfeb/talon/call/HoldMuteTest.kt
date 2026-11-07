package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertEquals

class HoldMuteTest {
    private class Mic {
        var muted = false
        var turnedOn = 0
        fun mute() { muted = true }
        fun unmute() { muted = false; turnedOn++ }
    }

    @Test
    fun `the hold unmutes what it muted`() {
        val h = HoldMute()
        val mic = Mic()
        h.hold(unmuted = true) { mic.mute() }
        assertEquals(true, mic.muted)
        h.unhold { mic.unmute() }
        assertEquals(false, mic.muted)
        assertEquals(1, mic.turnedOn)
        // Once: a second unhold has nothing of the hold's to undo.
        h.unhold { mic.unmute() }
        assertEquals(1, mic.turnedOn)
    }

    @Test
    fun `a mic already muted stays muted after the hold`() {
        val h = HoldMute()
        val mic = Mic().apply { muted = true }
        h.hold(unmuted = false) { mic.mute() }
        h.unhold { mic.unmute() }
        assertEquals(true, mic.muted)
        assertEquals(0, mic.turnedOn)
    }

    @Test
    fun `a line that ended while held does not unmute the next one`() {
        val h = HoldMute()
        val first = Mic()
        h.hold(unmuted = true) { first.mute() }
        h.ended()
        // The next line joins muted; a phone call holds and releases it.
        val next = Mic().apply { muted = true }
        h.hold(unmuted = false) { next.mute() }
        h.unhold { next.unmute() }
        assertEquals(true, next.muted, "the next line's mic came on by itself")
        assertEquals(0, next.turnedOn)
    }
}
