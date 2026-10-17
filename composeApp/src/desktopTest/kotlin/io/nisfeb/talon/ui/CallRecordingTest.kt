package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.CallEngine
import io.nisfeb.talon.call.CallEngineProvider
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.SessionDesc
import io.nisfeb.talon.call.VideoState
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recording a 1:1 call over a real [CallController] on a [FakeShip]: the
 * peer is told in the chat before any audio is tapped (sneagan chose the
 * chat notice, 2026-10-17), a notice that did not go records nothing,
 * and every end of the call keeps what was recorded.
 */
@OptIn(ExperimentalTestApi::class)
class CallRecordingTest {
    private val ship = FakeShip("~zod").apply {
        scries["trunk/policy"] = "{}"
        scries["trunk/version"] = """{"wire":${io.nisfeb.talon.call.TrunkWire.WIRE_VERSION}}"""
    }

    /** What happened, in order: notices sent, taps on and off. */
    private val events: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private inner class Engine : CallEngine {
        private val desc = SessionDesc("v=0\na=fingerprint:sha-256 AA:BB\n", "sha-256 AA:BB")
        override val state = MutableStateFlow(MediaState.Idle)
        override suspend fun createOffer() = desc
        override suspend fun acceptOffer(remote: SessionDesc) = desc
        override suspend fun setAnswer(remote: SessionDesc) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override val video = MutableStateFlow(VideoState())
        override suspend fun setCameraEnabled(enabled: Boolean) = false
        @Volatile var mic: ((ByteArray, Int) -> Unit)? = null
        @Volatile var remote: ((ByteArray, Int) -> Unit)? = null
        override fun onPcm(mic: ((pcm: ByteArray, sampleRate: Int) -> Unit)?, remote: ((pcm: ByteArray, sampleRate: Int) -> Unit)?) {
            this.mic = mic
            this.remote = remote
            events += if (mic != null || remote != null) "tap" else "untap"
        }
        override fun close() = Unit
    }

    private val engine = Engine()
    @Volatile private var told = true

    private fun call(notice: Boolean = true, block: ComposeUiTest.(CallController) -> Unit) = runComposeUiTest {
        val calls = CallController(
            UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") },
            CallEngineProvider { engine },
        )
        if (notice) calls.recordingNotice = { peer, text -> events += "notice $peer: $text"; told }
        calls.start()
        try {
            waitUntil(timeoutMillis = 10_000) { ship.subscribed.any { it.startsWith("trunk") } }
            setContent {
                TalonTheme(darkTheme = false) {
                    CallOverlay(
                        calls,
                        nameFor = { mapOf("~bus" to "Bus")[it] ?: it },
                        http = ship.http,
                        aiConfig = AiSettings.Config(provider = AiSettings.Provider.Custom, apiKey = "", model = null),
                    )
                }
            }
            block(calls)
        } finally {
            calls.stop()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 10_000) { shows(text) }
    private fun ComposeUiTest.recordButton() = onAllNodesWithText("Record").fetchSemanticsNodes().isNotEmpty()

    private fun fact(json: String) = runBlocking { ship.emit("""{"id":1,"response":"diff","json":$json}""") }

    /** A call from ~bus, answered and live. */
    private fun ComposeUiTest.live(id: String = "r1") {
        fact("""{"recv":{"from":"~bus","sig":{"ring":{"id":"$id"}}}}""")
        showing("Incoming call")
        onNodeWithContentDescription("Answer").performClick()
        showing("Connecting to Bus")
        assertTrue(!recordButton(), "nothing to record before the call is live")
        fact("""{"recv":{"from":"~bus","sig":{"offer":{"id":"$id","sdp":"v=0\na=fingerprint:sha-256 AA:BB\n","fpr":"sha-256 AA:BB"}}}}""")
        engine.state.value = MediaState.Live
        showing("Bus · 00:0")
    }

    private fun ComposeUiTest.startRecording() {
        // Behind the strip's expander on desktop, as a party line's is.
        onNodeWithContentDescription("Call controls").performClick()
        onNodeWithText("Record").performClick()
        showing("in your chat with Bus")
        onNodeWithText("Start recording").performClick()
    }

    private fun tone(samples: Int) = ByteArray(samples * 2) { if (it % 2 == 0) 0x10 else 0x01 }

    @Test
    fun `a recording tells them in the chat first, keeps both voices, and says when it stops`() = call {
        live()
        startRecording()
        waitUntil(timeoutMillis = 10_000) { "tap" in events }
        assertEquals(listOf("notice ~bus: ${CallController.RECORDING_ON}", "tap"), events.toList(), "told before a single frame is tapped")
        showing("● Recording")
        engine.mic!!(tone(480), 48_000)
        engine.remote!!(tone(480), 48_000)
        onNodeWithText("Stop recording").performClick()
        waitUntil(timeoutMillis = 10_000) { "notice ~bus: ${CallController.RECORDING_OFF}" in events }
        assertTrue("untap" in events)
        showing("Recording finished")
        showing("Captured 2 speakers")
        assertTrue(shows("Bus · 00:0"), "the call goes on")
    }

    @Test
    fun `a notice the ship did not take records nothing, and says why`() = call {
        told = false
        live()
        startRecording()
        showing("Not recording")
        showing("Couldn't tell ~bus in your chat")
        assertTrue("tap" !in events && !shows("● Recording"), events.toString())
        // A notice not taken now may go later: its stop goes after it.
        waitUntil(timeoutMillis = 10_000) { "notice ~bus: ${CallController.RECORDING_OFF}" in events }
        assertEquals(
            listOf("notice ~bus: ${CallController.RECORDING_ON}", "notice ~bus: ${CallController.RECORDING_OFF}"),
            events.toList(),
        )
    }

    @Test
    fun `hanging up mid-recording keeps it, and tells them it stopped`() = call {
        live()
        startRecording()
        waitUntil(timeoutMillis = 10_000) { "tap" in events }
        engine.remote!!(tone(480), 48_000)
        onAllNodesWithContentDescription("Hang up")[0].performClick()
        waitUntil(timeoutMillis = 10_000) { "notice ~bus: ${CallController.RECORDING_OFF}" in events }
        showing("Recording finished")
        showing("Captured 1 speaker")
    }

    @Test
    fun `muted, our own voice stays out of the recording`() = call { calls ->
        live()
        startRecording()
        waitUntil(timeoutMillis = 10_000) { "tap" in events }
        calls.setMuted(true)
        engine.mic!!(tone(480), 48_000)
        engine.remote!!(tone(480), 48_000)
        onNodeWithText("Stop recording").performClick()
        showing("Captured 1 speaker")
    }

    @Test
    fun `where the chat cannot be told there is no record control`() = call(notice = false) {
        live()
        waitForIdle()
        assertTrue(onAllNodesWithContentDescription("Call controls").fetchSemanticsNodes().isEmpty(), "nothing to expand to")
        assertTrue(!recordButton())
    }
}
