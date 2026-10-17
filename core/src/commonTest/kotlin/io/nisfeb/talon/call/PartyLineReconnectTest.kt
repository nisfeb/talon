package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A drop is not a leave. Each retry after a wifi blip ran the whole
 * leave: it finalized a recording mid-line (the room still showed us
 * recording), turned the camera and the share off, and a moment of
 * Idle ended the presence heartbeat for a line we were still on.
 */
class PartyLineReconnectTest {
    private class Link : PeerLink {
        override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Idle)
        @Volatile var sink: ((ByteArray, Int) -> Unit)? = null
        override fun onLocalCandidate(callback: (IceCandidate) -> Unit) = Unit
        override suspend fun offer(): String = "v=0"
        override suspend fun answerTo(remoteSdp: String): String = "v=0"
        override suspend fun applyAnswer(remoteSdp: String) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun onPcm(sink: ((pcm: ByteArray, sampleRate: Int) -> Unit)?) { this.sink = sink }
        override suspend fun setCameraEnabled(enabled: Boolean): Boolean = true
        override fun close() = Unit
    }

    private class Ws : WebSocketSession {
        override val coroutineContext: CoroutineContext = Job()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override suspend fun send(frame: Frame) = Unit
        override suspend fun flush() = Unit
        @Deprecated("unused in tests")
        override fun terminate() = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val joined = json.parseToJsonElement(
        """{"type":"joined","kind":"join","username":"~nec","permissions":["present","op"],"rtcConfiguration":{"iceServers":[]}}""",
    ) as kotlinx.serialization.json.JsonObject

    private fun tone(samples: Int) = ByteArray(samples * 2) { if (it % 2 == 0) 0x10 else 0x01 }

    @Test
    fun aDropKeepsTheRecordingAndTheCameraAndNeverReadsIdle() = runBlocking<Unit> {
        val drop = CompletableDeferred<Unit>()
        var asks = 0
        // The first session's .status read is the one the network drops;
        // the retry's never answers, which holds the line Connecting.
        val engine = MockEngine {
            if (++asks == 1) drop.await()
            awaitCancellation()
        }
        val ups = mutableListOf<Link>()
        val line = PartyLine(HttpClient(engine), links = { _, send -> Link().also { if (send) ups += it } })
        val seen = mutableListOf<PartyState>()
        line.join(TrunkTicket("room", "http://sfu.test/x/", "tok"), "~nec")
        // Unconfined, so every state is seen as it is set, a brief one too.
        val watch = launch(Dispatchers.Unconfined) { line.state.collect { seen += it } }
        try {
            line.session = Ws()
            line.handle(joined)
            withTimeout(5_000) { while (line.state.value !is PartyState.Live || ups.isEmpty()) delay(10) }
            line.startRecording("~nec")
            line.setMuted(false)
            assertTrue(line.setCameraEnabled(true))
            ups.last().sink!!(tone(480), 48_000)

            drop.completeExceptionally(IOException("connection reset"))
            withTimeout(5_000) { while (asks < 2) delay(10) }
            assertTrue(line.recording.value, "a drop finalized the recording mid-line")
            assertNull(line.lastRecording.value)
            assertTrue(line.cameraOn.value, "a drop turned the camera off")
            assertTrue(PartyState.Idle !in seen, "the line read Idle while it rejoined: $seen")

            // The retry's session: our voice goes on into the same file.
            line.session = Ws()
            line.handle(joined)
            withTimeout(5_000) { while (ups.size < 2 || ups.last().sink == null) delay(10) }
            ups.last().sink!!(tone(480), 48_000)

            line.leave()
            withTimeout(5_000) { while (line.state.value != PartyState.Idle || line.lastRecording.value == null) delay(10) }
            val rec = assertNotNull(line.lastRecording.value)
            assertEquals(setOf("~nec"), rec.clips.keys)
            assertTrue(rec.clips.getValue("~nec").count { it != 0.toByte() } >= 2 * 960, "both sessions' voice kept")
            assertTrue(!line.recording.value && !line.cameraOn.value, "leaving ends what the drop kept")
        } finally {
            watch.cancel()
            line.leave()
        }
    }
}
