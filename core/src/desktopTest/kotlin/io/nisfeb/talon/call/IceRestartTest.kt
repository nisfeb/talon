package io.nisfeb.talon.call

import io.nisfeb.talon.util.NetworkChanges
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Only the caller restarts ice after a network change, and an engine
 * that cannot restart leaves a working call alone. An iPhone that lost
 * cellular in a lift ended every call it had placed with "connection
 * failed"; the side that adopted the other's ring in glare kept
 * restarting as the caller, and the real caller ignored it.
 */
class IceRestartTest {
    private val restartDesc = SessionDesc("v=0\na=fingerprint:sha-256 CC:DD\n", "sha-256 CC:DD")

    private fun accept(id: String) =
        """{"recv":{"from":"~zod","sig":{"accept":{"id":"$id","sdp":"v=0\na=fingerprint:sha-256 AA:BB\n","fpr":"sha-256 AA:BB"}}}}"""

    private fun offer(id: String) =
        """{"recv":{"from":"~zod","sig":{"offer":{"id":"$id","sdp":"v=0\na=fingerprint:sha-256 AA:AA\n","fpr":"sha-256 AA:AA"}}}}"""

    private fun offersFor(h: TrunkHarness, id: String) = h.putsSnapshot().count { TrunkHarness.sigIdIn(it, "offer") == id }

    /** A call we placed to ~zod, answered and live. */
    private suspend fun placedAndLive(h: TrunkHarness, c: CallController, eng: ScriptedCallEngine): String {
        c.start()
        h.awaitConnected()
        withTimeout(10_000) { c.policy.first { it != null } }
        c.placeCall("~zod")
        h.awaitPut { TrunkHarness.sigIdIn(it, "offer") != null }
        val id = c.currentCallId!!
        h.emitFact(accept(id))
        withTimeout(10_000) { c.state.first { it is CallUiState.Active } }
        eng.stateFlow.value = MediaState.Live
        withTimeout(10_000) { c.state.first { (it as? CallUiState.Active)?.media == MediaState.Live } }
        return id
    }

    @Test
    fun `an engine that cannot restart keeps a working call through a network change`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val eng = ScriptedCallEngine()
        val c = CallController(h.session, CallEngineProvider { eng })
        try {
            val id = placedAndLive(h, c, eng)
            NetworkChanges.bump()
            delay(500)
            assertTrue(c.state.value is CallUiState.Active, "a network change ended a live call: ${c.state.value}")
            assertTrue(h.putsSnapshot().none { TrunkHarness.sigIdIn(it, "hangup") == id })

            c.switchCamera()
            assertEquals(1, eng.switched, "the flip reaches the call's engine")

            // The path really gone is what ends it, as before.
            eng.stateFlow.value = MediaState.Failed
            val end = withTimeout(5_000) { c.state.first { it is CallUiState.Ended } }
            assertEquals("connection failed", (end as CallUiState.Ended).reason)
            h.awaitPut { TrunkHarness.sigIdIn(it, "hangup") == id }
        } finally {
            c.stop()
        }
    }

    @Test
    fun `the side that adopted the other ring in glare does not restart`() = runBlocking<Unit> {
        val h = TrunkHarness() // ~nec, below ~zod: ours is the call given up
        val eng = ScriptedCallEngine(restart = restartDesc)
        val c = CallController(h.session, CallEngineProvider { eng })
        try {
            c.start()
            h.awaitConnected()
            withTimeout(10_000) { c.policy.first { it != null } }
            c.placeCall("~zod")
            withTimeout(10_000) { c.state.first { it is CallUiState.Outgoing } }
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"g1"}}}}""")
            withTimeout(10_000) { c.state.first { it is CallUiState.Incoming } }
            assertEquals("g1", c.currentCallId)
            c.accept()
            h.emitFact(offer("g1"))
            h.awaitPut { TrunkHarness.sigIdIn(it, "accept") == "g1" }
            eng.stateFlow.value = MediaState.Live
            withTimeout(10_000) { c.state.first { (it as? CallUiState.Active)?.media == MediaState.Live } }

            NetworkChanges.bump()
            delay(500)
            assertEquals(0, offersFor(h, "g1"), "the callee sent a restart offer the caller ignores")
            assertTrue(c.state.value is CallUiState.Active)
        } finally {
            c.stop()
        }
    }

    @Test
    fun `a restart belongs to its call, not the next`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val eng = ScriptedCallEngine(restart = restartDesc)
        val c = CallController(h.session, CallEngineProvider { eng }, restartGiveUpMs = 400)
        try {
            val first = placedAndLive(h, c, eng)
            NetworkChanges.bump()
            withTimeout(5_000) { while (offersFor(h, first) < 2) delay(10) }
            // Hung up mid-restart, before the path came back.
            c.hangup()
            withTimeout(5_000) { c.state.first { it is CallUiState.Ended } }
            c.dismissEnded()

            eng.stateFlow.value = MediaState.Idle
            c.placeCall("~zod")
            h.awaitPut { TrunkHarness.sigIdIn(it, "offer").let { o -> o != null && o != first } }
            val second = c.currentCallId!!
            h.emitFact(accept(second))
            withTimeout(10_000) { c.state.first { it is CallUiState.Active } }
            delay(800)
            assertTrue(c.state.value is CallUiState.Active, "the first call's give-up ended the second: ${c.state.value}")

            eng.stateFlow.value = MediaState.Live
            withTimeout(10_000) { c.state.first { (it as? CallUiState.Active)?.media == MediaState.Live } }
            NetworkChanges.bump()
            withTimeout(5_000) { while (offersFor(h, second) < 2) delay(10) }
        } finally {
            c.stop()
        }
    }
}
