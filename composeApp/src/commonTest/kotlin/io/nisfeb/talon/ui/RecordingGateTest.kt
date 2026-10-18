package io.nisfeb.talon.ui

import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.call.TrunkWire
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingGateTest {
    private val wire = TrunkWire.WIRE_VERSION_RECORDING

    @Test
    fun onlyAdminsMayRecord() {
        assertTrue(recordingAllowed(true, true, true, "~zod", wire, isAdmin = true))
        assertFalse(recordingAllowed(true, true, true, "~zod", wire, isAdmin = false))
        assertFalse(recordingAllowed(true, true, true, "~zod", wire - 1, isAdmin = true))
        assertFalse(recordingAllowed(false, true, true, "~zod", wire, isAdmin = true))
    }

    private fun live(room: String) = PartyState.Live(room = room, members = emptyList(), muted = false, media = MediaState.Idle)

    // Review B5-1: Live anywhere read as on this line, so Record in
    // another group's chat recorded the line we were on and told this
    // chat's host. A room is a slug, the same on two hosts.
    @Test
    fun onThisLineIsThisChatsOwnHostAndRoom() {
        assertTrue(onThisLine(live("lounge"), "~zod" to "lounge", "~zod/lounge"))
        assertFalse(onThisLine(live("lounge"), "~bus" to "lounge", "~zod/lounge"), "same room on another host")
        assertFalse(onThisLine(live("lounge"), "~zod" to "lounge", null), "never granted")
        assertFalse(onThisLine(live("den"), "~zod" to "lounge", "~zod/lounge"), "another room")
        assertFalse(onThisLine(live("lounge"), null, "~zod/lounge"), "a chat with no line")
        assertFalse(onThisLine(PartyState.Connecting("lounge"), "~zod" to "lounge", "~zod/lounge"), "not live yet")
    }
}
