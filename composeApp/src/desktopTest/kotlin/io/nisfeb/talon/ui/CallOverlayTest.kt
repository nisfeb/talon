package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The call surface over a real [CallController] on a [FakeShip]: a ring
 * answered or declined, a call placed and cancelled, and the offer to
 * fetch %trunk when the ship's is too old.
 */
@OptIn(ExperimentalTestApi::class)
class CallOverlayTest {
    private val ship = FakeShip("~zod").apply {
        scries["trunk/policy"] = "{}"
        scries["trunk/version"] = """{"wire":9}"""
    }

    /** Media that goes where the test says. */
    private class Engine : CallEngine {
        private val desc = SessionDesc("v=0\na=fingerprint:sha-256 AA:BB\n", "sha-256 AA:BB")
        override val state = MutableStateFlow(MediaState.Idle)
        override suspend fun createOffer() = desc
        override suspend fun acceptOffer(remote: SessionDesc) = desc
        override suspend fun setAnswer(remote: SessionDesc) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override val video: StateFlow<VideoState> = MutableStateFlow(VideoState())
        override suspend fun setCameraEnabled(enabled: Boolean) = false
        override fun close() = Unit
    }

    private val engine = Engine()

    private fun overlay(block: ComposeUiTest.(CallController) -> Unit) = runComposeUiTest {
        val calls = CallController(
            UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") },
            CallEngineProvider { engine },
        )
        calls.start()
        try {
            waitUntil(timeoutMillis = 10_000) { ship.subscribed.any { it.startsWith("trunk") } }
            setContent {
                TalonTheme(darkTheme = false) {
                    CallOverlay(calls, nameFor = { mapOf("~bus" to "Bus")[it] ?: it })
                }
            }
            block(calls)
        } finally {
            calls.stop()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 10_000) { shows(text) }

    private fun fact(json: String) = runBlocking { ship.emit("""{"id":1,"response":"diff","json":$json}""") }

    private fun ring(id: String) = fact("""{"recv":{"from":"~bus","sig":{"ring":{"id":"$id"}}}}""")

    /** The id of the last [kind] signal sent to the other side. */
    private fun ComposeUiTest.sent(kind: String): String {
        fun find() = ship.pokesTo("trunk").lastOrNull { it.json.toString().contains("\"$kind\"") }
        waitUntil(timeoutMillis = 10_000) { find() != null }
        return find()!!.json.jsonObject["send"]!!.jsonObject["sig"]!!.jsonObject[kind]!!.jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `a ring says who, and Decline tells them and ends it here`() = overlay {
        ring("c1")
        showing("Incoming call")
        assertTrue(shows("Bus"), "named as the app names them")
        onNodeWithContentDescription("Decline").performClick() // the word under it is a label
        assertEquals("c1", sent("reject"))
        showing("Bus — declined")
        assertTrue(!shows("Incoming call"))
    }

    @Test
    fun `an answered call connects, shows its time once live, and hangs up`() = overlay {
        ring("c2")
        showing("Incoming call")
        onNodeWithContentDescription("Answer").performClick()
        showing("Connecting to Bus")
        fact("""{"recv":{"from":"~bus","sig":{"offer":{"id":"c2","sdp":"v=0\na=fingerprint:sha-256 AA:BB\n","fpr":"sha-256 AA:BB"}}}}""")
        assertEquals("c2", sent("accept"))
        engine.state.value = MediaState.Live
        showing("Bus · 00:0")
        onAllNodesWithContentDescription("Leave the line")[0].performClick()
        assertEquals("c2", sent("hangup"))
        waitUntil(timeoutMillis = 10_000) { !shows("Bus · 00:0") }
    }

    @Test
    fun `a placed call rings out until cancelled`() = overlay { calls ->
        calls.placeCall("~bus")
        showing("Calling…")
        val id = sent("ring")
        onNodeWithContentDescription("Cancel").performClick()
        assertEquals(id, sent("hangup"))
        waitUntil(timeoutMillis = 10_000) { !shows("Calling…") }
    }

    @Test
    fun `an old calling app is offered an update, and Not now leaves it`() {
        ship.scries["trunk/version"] = """{"wire":5}"""
        overlay {
            showing("Your ship's calling app is out of date")
            onNodeWithText("Not now").performClick()
            waitUntil(timeoutMillis = 5_000) { !shows("out of date") }
            assertTrue(ship.pokesTo("hood").isEmpty(), "nothing installed")
        }
    }

    @Test
    fun `updating fetches trunk and closes once it answers`() {
        ship.scries["trunk/version"] = """{"wire":5}"""
        overlay {
            showing("out of date")
            onNodeWithText("Update").performClick()
            showing("Installing…")
            val install = ship.pokesTo("hood").single()
            assertEquals("trunk", install.json.jsonObject["desk"]?.jsonPrimitive?.content)
            waitUntil(timeoutMillis = 10_000) { !shows("out of date") }
        }
    }

    @Test
    fun `a refused update says so and offers to try again`() {
        ship.scries["trunk/version"] = """{"wire":5}"""
        ship.refuse = { if (it.app == "hood") "no" else null }
        overlay {
            showing("out of date")
            onNodeWithText("Update").performClick()
            showing("your ship refused the install")
            onNodeWithText("Try again").performClick()
            waitUntil(timeoutMillis = 5_000) { ship.pokesTo("hood").size == 2 }
        }
    }
}
