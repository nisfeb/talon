package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.call.RecordedCall
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recording a party line: asked first, and afterwards the only copy of
 * the audio, which is published, kept, or thrown away only when asked
 * twice.
 */
@OptIn(ExperimentalTestApi::class)
class CallRecordingDialogsTest {
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    @Volatile private var hangs = false

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        did += "${req.method.value} ${req.url.host}$path"
        when {
            path.endsWith("/audio/transcriptions") -> {
                if (hangs) awaitCancellation()
                respond("""{"text":"hello there","segments":[{"start":0.0,"text":"hello there"}]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            else -> respond("", HttpStatusCode.OK)
        }
    }) { install(HttpTimeout) }

    private val oneVoice = RecordedCall(clips = mapOf("~bus" to ByteArray(3_200)), sampleRate = 16_000)
    private val withKey = AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = null, baseUrl = null, sttApiKey = "sk-whisper")

    private fun result(rec: RecordedCall = oneVoice, stt: AiSettings.Config = withKey, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                RecordingResultDialog(
                    rec = rec, http = http, sttConfig = stt, shipUrl = "https://zod.test", cookie = "test-cookie",
                    ourShip = "~zod", title = "Standup", nameFor = { if (it == "~bus") "Bus" else it },
                    onClose = { did += "closed" },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { shows("Recording finished") }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `recording is asked for, and Cancel is a no`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                RecordConsentDialog(show = true, onDismiss = { did += "no" }, onConfirm = { did += "yes" })
            }
        }
        assertTrue(shows("everyone on the line sees a recording badge"))
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Start recording").performClick()
        assertEquals(listOf("no", "yes"), did.toList())
    }

    @Test
    fun `nothing captured has nothing to keep, and Done closes at once`() = result(rec = RecordedCall(emptyMap(), 16_000)) {
        assertTrue(shows("No audio was captured."))
        for (b in listOf("Publish transcript", "Save mixed recording", "Save one file per speaker")) onNodeWithText(b).assertIsNotEnabled()
        onNodeWithText("Done").performClick()
        assertEquals(listOf("closed"), did.toList())
    }

    @Test
    fun `without a transcription key nothing publishes, and discarding asks twice`() = result(stt = AiSettings.Config(provider = AiSettings.Provider.Anthropic, apiKey = "sk-ant", model = null, baseUrl = null)) {
        assertTrue(shows("Captured 1 speaker.") && shows("set an OpenAI-compatible transcription key"))
        onNodeWithText("Publish transcript").assertIsNotEnabled()
        onNodeWithText("Discard").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("This is the only copy.") }
        assertTrue("closed" !in did)
        onNodeWithText("Discard").performClick()
        assertTrue("closed" in did)
    }

    @Test
    fun `a transcript is published once, and then the dialog is done`() = result {
        assertTrue(shows("anyone who can read your Lattice namespace can see it"))
        onNodeWithText("Publish transcript").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Published to urb://~zod/") }
        assertTrue(did.any { it == "POST api.openai.com/v1/audio/transcriptions" }, did.toString())
        assertTrue(did.any { it == "POST zod.test/apps/lattice/save" }, did.toString())
        onNodeWithText("Publish transcript").assertIsNotEnabled()
        onNodeWithText("Done").performClick()
        assertTrue("closed" in did)
    }

    @Test
    fun `a transcription that hangs can be stopped, and the audio is still there`() {
        hangs = true
        result {
            onNodeWithText("Publish transcript").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Stop") }
            onNodeWithText("Stop").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Stopped.") }
            // The cancelled publish winding down must not speak over it.
            Thread.sleep(300)
            waitForIdle()
            assertTrue(shows("Stopped.") && !shows("failed"))
            onNodeWithText("Publish transcript").assertIsEnabled()
            assertTrue(shows("Discard"), "nothing was kept")
        }
    }
}
