package io.nisfeb.talon.ui

import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
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
        // A ship as current as the app: an older one is offered the update instead of the call.
        scries["trunk/version"] = """{"wire":${io.nisfeb.talon.call.TrunkWire.WIRE_VERSION}}"""
    }

    /** Media that goes where the test says. */
    private class Engine : CallEngine {
        private val desc = SessionDesc("v=0\na=fingerprint:sha-256 AA:BB\n", "sha-256 AA:BB")
        override val state = MutableStateFlow(MediaState.Idle)
        override suspend fun createOffer() = desc
        override suspend fun acceptOffer(remote: SessionDesc) = desc
        override suspend fun setAnswer(remote: SessionDesc) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override val video = MutableStateFlow(VideoState())
        override suspend fun setCameraEnabled(enabled: Boolean) = false
        override fun close() = Unit
    }

    private val engine = Engine()

    private fun overlay(width: androidx.compose.ui.unit.Dp? = null, block: ComposeUiTest.(CallController) -> Unit) = runComposeUiTest {
        val calls = CallController(
            UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") },
            CallEngineProvider { engine },
        )
        calls.start()
        try {
            waitUntil(timeoutMillis = 10_000) { ship.subscribed.any { it.startsWith("trunk") } }
            setContent {
                TalonTheme(darkTheme = false) {
                    androidx.compose.foundation.layout.Box(if (width == null) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.size(width, 900.dp)) {
                        CallOverlay(calls, nameFor = { mapOf("~bus" to "Bus")[it] ?: it })
                    }
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
        onAllNodesWithContentDescription("Hang up")[0].performClick() // a call, not a party line
        assertEquals("c2", sent("hangup"))
        waitUntil(timeoutMillis = 10_000) { !shows("Bus · 00:0") }
    }

    // On a wide window the picture pane was taller than its cap in the
    // picture's shape, the cap gave way, and it spilled over the call's
    // controls and down over the chat (sneagan, 2026-10-09).
    @Test
    fun `a call's pictures fit above its controls on a wide window`() = overlay(width = 1000.dp) {
        ring("c3")
        showing("Incoming call")
        onNodeWithContentDescription("Answer").performClick()
        fact("""{"recv":{"from":"~bus","sig":{"offer":{"id":"c3","sdp":"v=0\na=fingerprint:sha-256 AA:BB\n","fpr":"sha-256 AA:BB"}}}}""")
        assertEquals("c3", sent("accept"))
        engine.state.value = MediaState.Live
        engine.video.value = VideoState(localOn = true, remoteOn = true)
        showing("Bus · 00:0")
        waitForIdle()
        val pane = onNodeWithTag("call-video").getBoundsInRoot()
        val hangUp = onAllNodesWithContentDescription("Hang up")[0].getBoundsInRoot()
        assertTrue(pane.height <= MAX_VIDEO_PANE_HEIGHT, "the pane keeps its cap: $pane")
        assertTrue(pane.top >= 0.dp && pane.bottom <= hangUp.top, "the pane sits above the controls: $pane, hang up at $hangUp")
        assertEquals((pane.height * (16f / 9f)).value, pane.width.value, 1f, "in the picture's shape, 16:9 until a frame says otherwise")
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
