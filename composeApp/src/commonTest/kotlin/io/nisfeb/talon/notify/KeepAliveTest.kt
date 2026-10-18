package io.nisfeb.talon.notify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * sneagan, 2026-10-07: "talon's always on background service should turn
 * off if there is an appropriate push service enabled". Android keeps
 * Talon running only while nothing pushes to the phone for the ship.
 */
class KeepAliveTest {
    private val endpoint = "https://ntfy.test/up/abc"

    @Test
    fun a_phone_its_ship_pushes_to_needs_no_service() {
        val s = InMemoryRelaySettings().apply { setViaShipPush("~zod", true) }
        assertFalse(keepAliveNeeded(s, "~zod", endpoint))
    }

    @Test
    fun a_phone_on_the_relay_needs_no_service() {
        val s = InMemoryRelaySettings().apply { setDeviceIdFor("~zod", "dev-1") }
        assertFalse(keepAliveNeeded(s, "~zod", endpoint))
    }

    @Test
    fun nothing_pushing_or_no_distributor_keeps_it_running() {
        assertTrue(keepAliveNeeded(InMemoryRelaySettings(), "~zod", endpoint), "registered nowhere")
        val s = InMemoryRelaySettings().apply { setViaShipPush("~zod", true) }
        assertTrue(keepAliveNeeded(s, "~zod", null), "no distributor: nothing can arrive")
        assertTrue(keepAliveNeeded(s, "~bus", endpoint), "another ship's push says nothing for this one")
    }

    @Test
    fun going_back_to_the_relay_before_registering_turns_it_back_on() {
        val s = InMemoryRelaySettings().apply { setViaShipPush("~zod", true); setTrunkDeviceIdFor("~zod", "t-1") }
        kotlinx.coroutines.test.runTest { leaveShipPush("~zod", s) {} }
        assertTrue(keepAliveNeeded(s, "~zod", endpoint))
    }

    // Review of 1.8.1: mail notifies only from the running app.
    @Test
    fun mail_keeps_talon_running_even_with_push() {
        val s = InMemoryRelaySettings().apply { setViaShipPush("~zod", true) }
        assertTrue(keepAliveNeeded(s, "~zod", endpoint, mailNeedsProcess = true))
        assertFalse(keepAliveNeeded(s, "~zod", endpoint, mailNeedsProcess = false))
    }

    // B3-6 and B3-15: mail polls only while the screen lives. Gone, the
    // service is decided again without mail (TalonSyncService.streamNeeded),
    // and the catch-up worker is scheduled on that alone.
    @Test
    fun the_screen_gone_mail_keeps_nothing_and_only_the_stream_decides() {
        val pushed = InMemoryRelaySettings().apply { setViaShipPush("~zod", true) }
        assertTrue(keepAliveNeeded(pushed, "~zod", endpoint, mailNeedsProcess = true), "while the screen polls mail")
        assertFalse(keepAliveNeeded(pushed, "~zod", endpoint), "the screen gone, with push: stopped, no worker")
        assertTrue(keepAliveNeeded(InMemoryRelaySettings(), "~zod", endpoint), "no push: the stream and its worker stay")
        assertTrue(keepAliveNeeded(pushed, "~zod", endpoint, shipPushBroken = true), "the ship's push broken: they stay")
    }
}

