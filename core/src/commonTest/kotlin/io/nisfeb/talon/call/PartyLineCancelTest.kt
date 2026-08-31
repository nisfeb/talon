package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cancelled join is not a failed line.
 *
 * The pump caught Throwable, which includes CancellationException, and
 * wrote its message into PartyState.Failed. Cancellation reaches the
 * pump whenever the process is told to stop mid-join — leave(), a ship
 * switch closing the shared HttpClient — and Compose-adjacent
 * cancellations carry the message "The coroutine scope left the
 * composition". Users saw exactly that as a sticky red bar at the top
 * of their chats. A cancelled line must fall back to Idle; only a real
 * failure may say Failed.
 */
class PartyLineCancelTest {

    private class FakeLink : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit
    }

    private fun line(engineThrows: Throwable): PartyLine {
        val engine = MockEngine {
            // The first thing run() awaits is the .status GET; failing
            // it here exercises the pump's catch without a websocket.
            throw engineThrows
        }
        return PartyLine(HttpClient(engine), links = { _, _ -> FakeLink() })
    }

    private fun ticket() = TrunkTicket("room", "http://sfu.test/group/g/r/", "tok")

    private suspend fun settled(l: PartyLine): PartyState {
        withTimeout(5_000) {
            while (l.state.value is PartyState.Connecting) delay(10)
        }
        return l.state.value
    }

    @Test
    fun aCancelledJoinFallsBackToIdleNotFailed() = runBlocking {
        val l = line(CancellationException("The coroutine scope left the composition"))
        l.join(ticket(), "~nec")
        val end = settled(l)
        assertEquals(
            PartyState.Idle, end,
            "cancellation surfaced as $end — users saw this as a sticky red bar",
        )
    }

    @Test
    fun aRealFailureStillSaysWhy() = runBlocking {
        // The control: real errors must keep reporting, or a broken
        // line degrades to a button that silently does nothing.
        val l = line(IllegalStateException("sfu unreachable"))
        l.join(ticket(), "~nec")
        val end = settled(l)
        assertTrue(end is PartyState.Failed, "expected Failed, got $end")
        assertEquals("sfu unreachable", (end as PartyState.Failed).why)
    }
}
