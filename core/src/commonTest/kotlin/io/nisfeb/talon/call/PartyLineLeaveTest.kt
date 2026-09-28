package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Leaving stops the sound whatever the socket is doing. On a socket that
 * had stalled, the goodbye never went out and the teardown waiting behind
 * it never ran: the screen said the line was left while it went on
 * playing, after Leave and after the app was swiped away (Android,
 * 2026-09-28).
 */
class PartyLineLeaveTest {

    private class Link : PeerLink {
        @kotlin.concurrent.Volatile var closed = false
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() { closed = true }
    }

    /** A socket that takes no frame: every send, the close among them, waits for ever. */
    private class Stalled : WebSocketSession {
        override val coroutineContext: CoroutineContext = Job()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel()
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override suspend fun flush() = awaitCancellation()
        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun msg(s: String) = json.decodeFromString<JsonObject>(s)

    @Test
    fun leavingOnAStalledSocketStillClosesEveryLink() = runBlocking {
        val made = mutableListOf<Link>()
        val line = PartyLine(HttpClient(), links = { _, _ -> Link().also { made += it } })
        // On the line: our mic up, and one speaker coming down.
        line.handle(msg("""{"type":"joined","kind":"join","rtcConfiguration":{"iceServers":[]}}"""))
        line.handle(msg("""{"type":"offer","id":"s1","username":"~bus","sdp":"v=0"}"""))
        assertEquals(2, made.size, "an up link and a down link")
        line.session = Stalled()

        line.leave()
        assertEquals(PartyState.Idle, line.state.value, "the screen says left at once")
        // Two bounded waits on the socket, then the links: well inside this.
        withTimeout(4 * PartyLine.GOODBYE_MS + 2_000) { while (made.any { !it.closed }) delay(20) }
        assertTrue(made.all { it.closed })
    }
}
