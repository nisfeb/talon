package io.nisfeb.talon.call

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The give-up timers. Without them a caller that vanishes mid-ring
 * leaves the callee ringing internally forever — and a device that
 * thinks it is ringing answers "busy" to every call after it.
 */
class CallWatchdogTest {

    @Test
    fun anUnansweredIncomingRingEndsAsMissed() = runBlocking {
        val h = TrunkHarness()
        val c = CallController(
            h.session,
            CallEngineProvider { error("nobody answers") },
            ringTimeoutMs = 200,
        )
        try {
            c.start()
            h.awaitConnected()
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}""")
            withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }

            val end = withTimeout(5_000) { c.state.first { it is CallUiState.Ended } }
            assertEquals("missed", (end as CallUiState.Ended).reason)
        } finally {
            c.stop()
        }
    }

    @Test
    fun anUnansweredOutgoingRingEndsLocallyBeforeTheHangupPoke() = runBlocking {
        val h = TrunkHarness()
        val c = CallController(
            h.session,
            CallEngineProvider { HangingCallEngine() },
            ringTimeoutMs = 200,
        )
        // Capture what the UI showed at the instant the hangup poke
        // went out. Ending must not wait on the network: if the poke
        // led, the state here would still be Outgoing.
        val stateAtHangup = AtomicReference<CallUiState?>(null)
        h.onPut = { body ->
            if (body.contains("\"hangup\"")) {
                stateAtHangup.compareAndSet(null, c.state.value)
            }
        }
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            c.placeCall("~zod")
            withTimeout(10_000) { c.state.first { it is CallUiState.Outgoing } }

            val end = withTimeout(5_000) { c.state.first { it is CallUiState.Ended } }
            assertEquals("no answer", (end as CallUiState.Ended).reason)

            h.awaitPut { it.contains("\"hangup\"") }
            val seen = stateAtHangup.get()
            assertTrue(
                seen != null && seen !is CallUiState.Outgoing,
                "the hangup poke went out while the call was still on " +
                    "screen (state at poke time: $seen)",
            )
        } finally {
            c.stop()
        }
    }

    @Test
    fun answeringBeforeTheTimeoutDisarmsTheRingWatchdog() = runBlocking {
        val h = TrunkHarness()
        val eng = ScriptedCallEngine()
        val c = CallController(
            h.session,
            CallEngineProvider { eng },
            ringTimeoutMs = 700,
        )
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

            // Sit out the ring deadline and then some: an armed
            // watchdog would have ended the call at 700ms.
            delay(1_200)
            assertTrue(
                c.state.value is CallUiState.Active,
                "an answered call was ended by its own ring watchdog: ${c.state.value}",
            )
        } finally {
            c.stop()
        }
    }

    @Test
    fun aCallStuckConnectingGivesUp() = runBlocking {
        val h = TrunkHarness()
        // The engine accepts the offer but its media never goes Live.
        val eng = ScriptedCallEngine()
        val c = CallController(
            h.session,
            CallEngineProvider { eng },
            connectTimeoutMs = 200,
        )
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

            val end = withTimeout(5_000) { c.state.first { it is CallUiState.Ended } }
            assertEquals("couldn't connect", (end as CallUiState.Ended).reason)
        } finally {
            c.stop()
        }
    }
}
