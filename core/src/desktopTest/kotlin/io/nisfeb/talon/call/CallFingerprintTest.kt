package io.nisfeb.talon.call

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signaled fingerprint must agree with the SDP's own
 * a=fingerprint line — the check that catches a tampered relay.
 */
class CallFingerprintTest {

    @Test
    fun aMismatchedOfferFingerprintDropsTheCallAndRejects() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(
            h.session,
            CallEngineProvider { error("the call must die before media") },
        )
        try {
            c.start()
            h.awaitConnected()
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}""")
            withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }

            // SDP pins AA:AA; the signal claims BB:BB.
            h.emitFact(
                """{"recv":{"from":"~zod","sig":{"offer":{"id":"c1",""" +
                    """"sdp":"v=0\na=fingerprint:sha-256 AA:AA\n","fpr":"sha-256 BB:BB"}}}}""",
            )
            val end = withTimeout(10_000) { c.state.first { it is CallUiState.Ended } }
            assertEquals("security error", (end as CallUiState.Ended).reason)
            h.awaitPut { it.contains("\"reject\"") && it.contains("fingerprint mismatch") }
        } finally {
            c.stop()
        }
    }

    @Test
    fun anAgreeingOfferFingerprintConnectsNormally() = runBlocking {
        val h = TrunkHarness()
        val eng = ScriptedCallEngine()
        val c = CallController(h.session, CallEngineProvider { eng })
        try {
            c.start()
            h.awaitConnected()
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}""")
            withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }

            h.emitFact(
                """{"recv":{"from":"~zod","sig":{"offer":{"id":"c1",""" +
                    """"sdp":"v=0\na=fingerprint:sha-256 AA:AA\n","fpr":"sha-256 AA:AA"}}}}""",
            )
            c.accept()
            withTimeout(10_000) { c.state.first { it is CallUiState.Active } }
            assertTrue(
                h.putsSnapshot().none { it.contains("\"reject\"") },
                "an agreeing fingerprint was rejected",
            )
        } finally {
            c.stop()
        }
    }
}
