package io.nisfeb.talon.call

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Switching on a line another ship hosts is a request to that ship, and
 * the admin screen says what came of it. The host answers a line it
 * opened with an announcement. Its %trunk nacks what it can't take. A
 * host with no %trunk says nothing at all, and neither does one whose
 * call settings keep us out, so those two are told apart by whether its
 * %trunk has ever spoken to us.
 */
class LineAskTest {

    private val key = "~bus/garden"

    private fun controller(h: TrunkHarness, waitMs: Long = 20_000) = CallController(
        h.session,
        CallEngineProvider { error("no media needed to ask for a line") },
        joinAskTimeoutMs = waitMs,
    )

    private suspend fun CallController.switchOn() =
        configureRoom("~bus", "garden", open = true, listen = false, members = listOf("~bus", "~nec"))

    private fun run(waitMs: Long = 20_000, block: suspend (TrunkHarness, CallController) -> Unit) = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = controller(h, waitMs)
        try {
            c.start()
            h.awaitConnected()
            block(h, c)
        } finally {
            c.stop()
        }
    }

    @Test
    fun aHostThatNacksTurnedItDown() = run { h, c ->
        c.switchOn()
        assertTrue(c.lineAsks.value[key] is LineAsk.Asking, "${c.lineAsks.value}")
        // What our own %trunk makes of the host's nack on the /room wire.
        h.emitFact("""{"denied":{"from":"~bus","name":"","why":"host unreachable"}}""")
        h.await { c.lineAsks.value[key] == LineAsk.Refused }
    }

    @Test
    fun aHostThatNeverAnswersMayHaveNoTrunk() = run(waitMs = 200) { _, c ->
        c.switchOn()
        val deadline = System.currentTimeMillis() + 5_000
        while (c.lineAsks.value[key] !is LineAsk.NoAnswer && System.currentTimeMillis() < deadline) delay(10)
        assertEquals(LineAsk.NoAnswer, c.lineAsks.value[key])
    }

    @Test
    fun silenceFromAHostWhoseTrunkHasSpokenIsARefusal() = run(waitMs = 200) { h, c ->
        // The host's answer to a peek: its %trunk is there.
        h.emitFact("""{"denied":{"from":"~bus","name":"garden","why":"no such room"}}""")
        delay(300)
        c.switchOn()
        h.await { c.lineAsks.value[key] == LineAsk.Ignored }
    }

    /** A nameless denial is our own ship relaying a nack, so it says
     *  nothing about the host's %trunk. */
    @Test
    fun aRelayedNackIsNotTheHostsTrunkSpeaking() = run(waitMs = 200) { h, c ->
        h.emitFact("""{"denied":{"from":"~bus","name":"","why":"host unreachable"}}""")
        delay(300)
        c.switchOn()
        h.await { c.lineAsks.value[key] == LineAsk.NoAnswer }
    }

    @Test
    fun theHostsAnnouncementSettlesIt() = run { h, c ->
        c.switchOn()
        h.emitFact("""{"open":{"from":"~bus","name":"garden","title":"The Garden"}}""")
        h.await { key in c.invites.value }
        assertNull(c.lineAsks.value[key])
    }

    @Test
    fun aLateAnnouncementClearsTheNoAnswer() = run(waitMs = 100) { h, c ->
        c.switchOn()
        h.await { c.lineAsks.value[key] == LineAsk.NoAnswer }
        h.emitFact("""{"open":{"from":"~bus","name":"garden","title":"The Garden"}}""")
        h.await { c.lineAsks.value[key] == null }
    }

    @Test
    fun ourOwnShipRefusingIsSaidAsOurs() = run { h, c ->
        h.pokeErr = "trunk crashed"
        c.switchOn()
        assertEquals(LineAsk.OwnShip, c.lineAsks.value[key])
    }

    @Test
    fun switchingOffOrOurOwnLineAsksNothing() = run(waitMs = 100) { h, c ->
        c.switchOn()
        h.await { c.lineAsks.value[key] == LineAsk.NoAnswer }
        c.configureRoom("~bus", "garden", open = false, listen = false)
        assertNull(c.lineAsks.value[key], "switching off clears what the last ask left")
        c.configureRoom("~nec", "garden", open = true, listen = false)
        delay(300)
        assertTrue(c.lineAsks.value.isEmpty(), "our own line applies at once: ${c.lineAsks.value}")
    }

    /** The screen's scope goes with the screen. The ask must not. */
    @Test
    fun leavingMidwayStillSends() = run(waitMs = 200) { h, c ->
        val sent = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        h.onPut = { body -> if ("configure-room" in body) { sent.complete(Unit); release.await() } }
        val screen = CoroutineScope(Dispatchers.Default).launch { c.switchOn() }
        sent.await()
        screen.cancel()
        release.countDown()
        h.await { c.lineAsks.value[key] == LineAsk.NoAnswer }
    }
}
