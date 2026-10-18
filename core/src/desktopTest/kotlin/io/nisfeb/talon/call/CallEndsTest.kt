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

    // Two of our devices answering at once both send an accept; the
    // caller takes the first. The other never connects, and its give-up
    // must not hang up the call the first is on.
    @Test
    fun `an answered call that never connected ends here only`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { ScriptedCallEngine() }, connectTimeoutMs = 300)
        try {
            c.start()
            h.awaitConnected()
            h.emitFact(ring("c1"))
            withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }
            h.emitFact(offer("c1"))
            c.accept()
            h.awaitPut { TrunkHarness.sigIdIn(it, "accept") == "c1" }
            val end = withTimeout(5_000) { c.state.first { it is CallUiState.Ended } }
            assertEquals("couldn't connect", (end as CallUiState.Ended).reason)
            delay(300)
            assertEquals(0, hangups(h, "c1"), "the loser of the race hung up the winner's call")
        } finally {
            c.stop()
        }
    }

    @Test
    fun `a call we placed that never connected is still hung up on the peer`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { ScriptedCallEngine() }, connectTimeoutMs = 300)
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            c.placeCall("~zod")
            h.awaitPut { TrunkHarness.sigIdIn(it, "offer") != null }
            val id = c.currentCallId!!
            h.emitFact("""{"recv":{"from":"~zod","sig":{"accept":{"id":"$id","sdp":"v=0\na=fingerprint:sha-256 AA:BB\n","fpr":"sha-256 AA:BB"}}}}""")
            val end = withTimeout(5_000) { c.state.first { it is CallUiState.Ended } }
            assertEquals("couldn't connect", (end as CallUiState.Ended).reason)
            h.awaitPut { TrunkHarness.sigIdIn(it, "hangup") == id }
        } finally {
            c.stop()
        }
    }

    @Test
    fun `hang up ends a call that is on, and says when there is none`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            assertFalse(c.hangupLive(), "no call")
            h.emitFact(ring("r1"))
            withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }
            assertFalse(c.hangupLive(), "a ring is not a call on")
            c.reject()
            withTimeout(10_000) { c.state.first { it is CallUiState.Ended } }
            assertFalse(c.hangupLive(), "an Ended notice is not a call: the line beside it is what is on")

            c.placeCall("~zod")
            withTimeout(10_000) { c.state.first { it is CallUiState.Outgoing } }
            val id = c.currentCallId!!
            assertTrue(c.hangupLive())
            h.awaitPut { TrunkHarness.sigIdIn(it, "hangup") == id }
        } finally {
            c.stop()
        }
    }

    @Test
    fun `a decline from outside the app ends its channel and does not wait on the ack`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val started = System.currentTimeMillis()
        assertTrue(CallController.declineWithoutApp(h.session, "~zod", "c9", waitMs = 500))
        val took = System.currentTimeMillis() - started
        assertTrue(took < 4_000, "held for ${took}ms")
        val puts = h.putsSnapshot()
        val reject = puts.indexOfFirst { TrunkHarness.sigIdIn(it, "reject") == "c9" }
        assertTrue(reject >= 0, "$puts")
        assertTrue("\"declined\"" in puts[reject])
        assertTrue(puts.drop(reject + 1).any { it.contains("\"action\":\"delete\"") }, "the channel is ended on the ship")
    }
}
