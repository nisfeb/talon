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
        // The SFU never answers: the line sits Connecting, which is all this
        // needs. Which line it asked for is the GET of that line's status.
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val line = PartyLine(
            HttpClient(MockEngine { asked += it.url.toString(); awaitCancellation() }),
            links = { _, _ -> Link() },
        )
        try {
            c.start()
            h.awaitConnected()
            h.emitFact("""{"open":{"from":"~bus","name":"lounge","title":"Bus lounge"}}""")
            h.await { c.lineFor("~bus", "lounge") != null }
            c.joinGranted(line, "~zod", TrunkTicket("lounge", "http://sfu.test/a/", "tok"), "~nec")
            h.await { presence(h, "enter-room", "~zod", "lounge") > 0 }
            assertEquals(PartyState.Connecting("lounge"), line.state.value)
            // Which host a chat's Record tells (review B5-1): the line's own.
            assertEquals("~zod/lounge", c.lineKey)

            c.joinGranted(line, "~bus", TrunkTicket("lounge", "http://sfu.test/b/", "tok"), "~nec")
            h.await { presence(h, "leave-room", "~zod", "lounge") > 0 }
            h.await { presence(h, "enter-room", "~bus", "lounge") > 0 }
            h.await { asked.any { it.startsWith("http://sfu.test/b/") } }
            assertEquals(PartyState.Connecting("lounge"), line.state.value, "on the new line, the same slug on another host")
            // Its title, once the line is up.
            line.handle(
                kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"type":"joined","kind":"join","username":"~nec","rtcConfiguration":{"iceServers":[]}}""",
                ) as kotlinx.serialization.json.JsonObject,
            )
            assertEquals("Bus lounge", (line.state.value as PartyState.Live).topic)
            assertEquals("~bus/lounge", c.lineKey, "the slug is the same, the host is not")

            // Asked again for the line we are on: nothing to leave.
            c.joinGranted(line, "~bus", TrunkTicket("lounge", "http://sfu.test/b/", "tok"), "~nec")
            delay(300)
            assertEquals(0, presence(h, "leave-room", "~bus", "lounge"), "left the line it was asked to join")
            assertTrue(line.state.value is PartyState.Live)
        } finally {
            line.leave()
            c.stop()
        }
    }

    // The line left kept a recording's badge: its announce ran on until
    // the result dialog closed (review A1-8, 2026-10-17).
    @Test
    fun `a grant for another line ends the recording badge of the line left`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        val line = PartyLine(
            HttpClient(MockEngine { awaitCancellation() }),
            links = { _, _ -> Link() },
        )
        try {
            c.start()
            h.awaitConnected()
            c.joinGranted(line, "~zod", TrunkTicket("lounge", "http://sfu.test/a/", "tok"), "~nec")
            h.await { presence(h, "enter-room", "~zod", "lounge") > 0 }
            line.startRecording("~nec")
            c.beginRecordingAnnounce("~zod", "lounge", line.recording)
            h.await { presence(h, "start-recording", "~zod", "lounge") > 0 }
            c.joinGranted(line, "~bus", TrunkTicket("lounge", "http://sfu.test/b/", "tok"), "~nec")
            h.await { presence(h, "stop-recording", "~zod", "lounge") > 0 }
            assertTrue(!line.recording.value)
        } finally {
            line.leave()
            c.stop()
        }
    }
}
