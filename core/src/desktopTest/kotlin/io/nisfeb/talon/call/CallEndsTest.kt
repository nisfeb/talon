package io.nisfeb.talon.call

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who hears that a call ended, and from where. The app letting go of a
 * call told nobody; a device that lost the race to answer hung up the
 * one that won; the call notification's Hang up did nothing during a
 * call's Ended notice; and a decline from the notification left its
 * channel on the ship and held the receiver for the ack's whole wait.
 */
class CallEndsTest {
    private fun ring(id: String) = """{"recv":{"from":"~zod","sig":{"ring":{"id":"$id"}}}}"""
    private fun offer(id: String) =
        """{"recv":{"from":"~zod","sig":{"offer":{"id":"$id","sdp":"v=0\na=fingerprint:sha-256 AA:AA\n","fpr":"sha-256 AA:AA"}}}}"""

    private fun hangups(h: TrunkHarness, id: String) = h.putsSnapshot().count { TrunkHarness.sigIdIn(it, "hangup") == id }

    @Test
    fun `letting go of a placed call hangs it up on the peer, and a ring is left alone`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        c.start()
        h.awaitConnected()
        withTimeout(10_000) { c.policy.first { it != null } }
        c.placeCall("~zod")
        withTimeout(10_000) { c.state.first { it is CallUiState.Outgoing } }
        h.awaitPut { TrunkHarness.sigIdIn(it, "ring") != null }
        val id = c.currentCallId!!
        c.stop()
        h.awaitPut { TrunkHarness.sigIdIn(it, "hangup") == id }
        h.awaitPut { it.contains("\"action\":\"delete\"") }
        val puts = h.putsSnapshot()
        assertTrue(
            puts.indexOfFirst { TrunkHarness.sigIdIn(it, "hangup") == id } < puts.indexOfLast { it.contains("\"action\":\"delete\"") },
            "the hangup goes on the channel before it is ended",
        )

        val h2 = TrunkHarness()
        val ringing = CallController(h2.session, CallEngineProvider { HangingCallEngine() })
        ringing.start()
        h2.awaitConnected()
        h2.emitFact(ring("r1"))
        withTimeout(10_000) { ringing.state.first { it is CallUiState.Incoming } }
        ringing.stop()
        h2.awaitPut { it.contains("\"action\":\"delete\"") }
        assertTrue(h2.putsSnapshot().none { TrunkHarness.sigIdIn(it, "reject") != null || TrunkHarness.sigIdIn(it, "hangup") != null })
    }
}
