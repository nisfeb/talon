package io.nisfeb.talon.call

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A grant that comes after the ask is over. A slow host's grant after the
 * ask timed out is still taken (the person is still waiting), but a
 * cancelled or refused ask took one too: tap the line, see "asking", press
 * Cancel, and the host's grant a minute later put this device on the line.
 */
class JoinAskCancelTest {
    private val ticket = """{"ticket":{"from":"~bus","name":"lounge","location":"http://sfu.test/","token":"tok"}}"""

    private suspend fun asking(h: TrunkHarness, c: CallController) {
        c.start()
        h.awaitConnected()
        withTimeout(10_000) { c.policy.first { it != null } }
        c.joinRoom("~bus", "lounge")
        h.awaitPut { it.contains("\"join-room\":{\"host\":\"~bus\",\"name\":\"lounge\"}") }
    }

    private fun controller(h: TrunkHarness, tickets: MutableList<String>) =
        CallController(h.session, CallEngineProvider { HangingCallEngine() }, joinAskTimeoutMs = 300).also { c ->
            c.onTicket = { from, t -> tickets += "$from/${t.name}" }
        }

    @Test
    fun `a grant after Cancel is not taken`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val tickets = mutableListOf<String>()
        val c = controller(h, tickets)
        try {
            asking(h, c)
            c.cancelJoin()
            h.emitFact(ticket)
            delay(300)
            assertEquals(emptyList(), tickets, "a cancelled ask still took the line")
        } finally {
            c.stop()
        }
    }

    @Test
    fun `a grant after a refusal is not taken`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val tickets = mutableListOf<String>()
        val c = controller(h, tickets)
        try {
            asking(h, c)
            h.emitFact("""{"denied":{"from":"~bus","name":"lounge","why":"not a member"}}""")
            h.await { c.pendingJoin.value == null }
            h.emitFact(ticket)
            delay(300)
            assertEquals(emptyList(), tickets, "a refused ask still took the line")
        } finally {
            c.stop()
        }
    }

    @Test
    fun `a slow host's grant after the ask timed out is still taken`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val tickets = mutableListOf<String>()
        val c = controller(h, tickets)
        try {
            asking(h, c)
            // Past the ask's deadline: the pending indicator has given up.
            h.await { c.pendingJoin.value == null }
            h.emitFact(ticket)
            h.await { tickets.isNotEmpty() }
            assertEquals(listOf("~bus/lounge"), tickets)
        } finally {
            c.stop()
        }
    }

    @Test
    fun `a grant while asking is taken`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val tickets = mutableListOf<String>()
        val c = controller(h, tickets)
        try {
            asking(h, c)
            h.emitFact(ticket)
            h.await { tickets.isNotEmpty() }
            assertEquals(listOf("~bus/lounge"), tickets)
        } finally {
            c.stop()
        }
    }
}
