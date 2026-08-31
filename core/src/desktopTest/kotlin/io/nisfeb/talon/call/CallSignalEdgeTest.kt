package io.nisfeb.talon.call

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signal-handling edges that each cost a debugging session in the
 * field: glare (two people dialing each other), a second device's
 * duplicate accept, and an old desk's relay nack for a peer that
 * never received the ring at all.
 */
class CallSignalEdgeTest {

    @Test
    fun glareTheLowerShipAdoptsTheIncomingRingAndHangsUpItsOwn() = runBlocking {
        val h = TrunkHarness(ship = "~dev")
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            c.placeCall("~zod")
            withTimeout(10_000) { c.state.first { it is CallUiState.Outgoing } }
            val ringPut = h.awaitPut { TrunkHarness.sigIdIn(it, "ring") != null }
            val ourCallId = TrunkHarness.sigIdIn(ringPut, "ring")!!

            // ~zod rings us while we are dialing ~zod. "~dev" < "~zod",
            // so this side abandons its attempt and takes their ring.
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"g9"}}}}""")
            val adopted = withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }
            assertEquals("~zod", (adopted as CallUiState.Incoming).peer)

            // And their devices ringing for OUR abandoned call are told
            // it is over.
            val hungUp = h.awaitPut { TrunkHarness.sigIdIn(it, "hangup") != null }
            assertEquals(ourCallId, TrunkHarness.sigIdIn(hungUp, "hangup"))
        } finally {
            c.stop()
        }
    }

    @Test
    fun glareTheHigherShipKeepsRinging() = runBlocking {
        val h = TrunkHarness(ship = "~zod")
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            c.placeCall("~dev")
            withTimeout(10_000) { c.state.first { it is CallUiState.Outgoing } }

            h.emitFact("""{"recv":{"from":"~dev","sig":{"ring":{"id":"g9"}}}}""")
            // Sentinel: facts process in order, so the ring above has
            // been fully handled once the policy flips.
            h.emitFact("""{"policy":{"mode":"allow"}}""")
            withTimeout(10_000) { c.policy.first { it?.mode == CallPolicy.Mode.Allow } }

            assertTrue(
                c.state.value is CallUiState.Outgoing,
                "the higher ship must keep its own call, got ${c.state.value}",
            )
            assertTrue(
                h.putsSnapshot().none { TrunkHarness.sigIdIn(it, "hangup") != null },
                "the higher ship abandoned its call too — both sides gave up",
            )
        } finally {
            c.stop()
        }
    }

    @Test
    fun aDuplicateAcceptIsIgnoredOnceTheCallIsActive() = runBlocking {
        val h = TrunkHarness()
        val eng = ScriptedCallEngine()
        val c = CallController(h.session, CallEngineProvider { eng })
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            c.placeCall("~zod")
            val ringPut = h.awaitPut { TrunkHarness.sigIdIn(it, "ring") != null }
            val id = TrunkHarness.sigIdIn(ringPut, "ring")!!

            val accept =
                """{"recv":{"from":"~zod","sig":{"accept":{"id":"$id",""" +
                    """"sdp":"v=0\na=fingerprint:sha-256 EE:FF\n","fpr":"sha-256 EE:FF"}}}}"""
            h.emitFact(accept)
            withTimeout(10_000) { c.state.first { it is CallUiState.Active } }
            eng.stateFlow.value = MediaState.Live
            withTimeout(10_000) {
                c.state.first { it is CallUiState.Active && it.media == MediaState.Live }
            }

            // A second device answered within an ames round trip: the
            // same accept again. It used to knock the live call back to
            // Connecting and then drop it when libwebrtc refused the
            // duplicate remote description.
            h.emitFact(accept)
            h.emitFact("""{"policy":{"mode":"allow"}}""")
            withTimeout(10_000) { c.policy.first { it?.mode == CallPolicy.Mode.Allow } }

            val cur = c.state.value
            assertTrue(
                cur is CallUiState.Active && cur.media == MediaState.Live,
                "the duplicate accept disturbed a live call: $cur",
            )
            assertEquals(1, eng.answersSet, "the answer must be applied exactly once")
        } finally {
            c.stop()
        }
    }

    @Test
    fun anUnknownIdRejectFromTheDialedPeerMeansUnreachable() = runBlocking {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            c.placeCall("~zod")
            withTimeout(10_000) { c.state.first { it is CallUiState.Outgoing } }

            // An old desk's relay nack can't echo the call id, so it
            // rejects as id "unknown". From the ship we are dialing
            // that means our signaling never got through.
            h.emitFact(
                """{"recv":{"from":"~zod","sig":{"reject":{"id":"unknown","reason":"unreachable"}}}}""",
            )
            val end = withTimeout(10_000) { c.state.first { it is CallUiState.Ended } }
            assertEquals("couldn't be reached", (end as CallUiState.Ended).reason)
        } finally {
            c.stop()
        }
    }
}
