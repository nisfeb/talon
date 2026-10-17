package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tapping another group's party line while on one. The ticket retitled
 * the line we were on with the new one's name, the join was dropped
 * because the line was busy, and the presence for the line we were on
 * told its host we had left it. Now we leave it, then join.
 */
class JoinGrantedTest {
    private class Link : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit
    }

    private fun presence(h: TrunkHarness, kind: String, host: String, name: String) = h.putsSnapshot().count {
        it.contains("\"$kind\":{\"host\":\"$host\",\"name\":\"$name\"}")
    }

    @Test
    fun `a grant for another line moves us there, and one for this line does nothing`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        // The SFU never answers: the line sits Connecting, which is all this needs.
        val line = PartyLine(HttpClient(MockEngine { awaitCancellation() }), links = { _, _ -> Link() })
        try {
            c.start()
            h.awaitConnected()
            c.joinGranted(line, "~zod", TrunkTicket("lounge", "http://sfu.test/a/", "tok"), "~nec")
            h.await { presence(h, "enter-room", "~zod", "lounge") > 0 }
            assertEquals(PartyState.Connecting("lounge"), line.state.value)

            c.joinGranted(line, "~bus", TrunkTicket("lounge", "http://sfu.test/b/", "tok"), "~nec")
            h.await { presence(h, "leave-room", "~zod", "lounge") > 0 }
            h.await { presence(h, "enter-room", "~bus", "lounge") > 0 }
            assertEquals(PartyState.Connecting("lounge"), line.state.value, "on the new line, the same slug on another host")

            // Asked again for the line we are on: nothing to leave.
            c.joinGranted(line, "~bus", TrunkTicket("lounge", "http://sfu.test/b/", "tok"), "~nec")
            delay(300)
            assertEquals(0, presence(h, "leave-room", "~bus", "lounge"), "left the line it was asked to join")
            assertTrue(line.state.value is PartyState.Connecting)
        } finally {
            line.leave()
            c.stop()
        }
    }
}
