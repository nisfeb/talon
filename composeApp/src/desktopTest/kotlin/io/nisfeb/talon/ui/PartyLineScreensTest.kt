package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.nisfeb.talon.call.AudioDevice
import io.nisfeb.talon.call.AudioDevices
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.TrunkTicket
import io.nisfeb.talon.call.VideoDevice
import io.nisfeb.talon.call.VideoDevices
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A party line as its screens show it: who is on and who is speaking,
 * and what each control asks of the line.
 */
@OptIn(ExperimentalTestApi::class)
class PartyLineScreensTest {
    private val did = mutableListOf<String>()

    private val names = mapOf("~zod" to "Zod", "~bus" to "Bus", "~nec" to "Nec")

    private fun live(muted: Boolean = false, canSpeak: Boolean = true, ops: Boolean = false, byAdmin: Boolean = false) = PartyState.Live(
        room = "garden-chat", topic = "Planting season",
        members = listOf(
            PartyMember(id = "1", ship = "~zod"),
            PartyMember(id = "2", ship = "~bus", speaking = true),
            PartyMember(id = "3", ship = "~nec", muted = true),
        ),
        muted = muted, media = MediaState.Live, listeners = 4, canSpeak = canSpeak, ops = ops, selfMutedByAdmin = byAdmin,
    )

    private fun full(state: PartyState.Live, recording: Boolean = false, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineFullScreen(
                    state = state, roomName = "Garden chat", nameFor = { names[it] ?: it }, selfShip = "~zod",
                    onToggleMute = { did += "mute:$it" }, onLeave = { did += "leave" }, onMinimize = { did += "minimize" },
                    onRevokeSpeaking = { did += "revoke:$it" }, onRestoreSpeaking = { did += "restore:$it" },
                    onMessage = { did += "message:$it" },
                    recording = recording, recordedBy = if (recording) setOf("~bus") else emptySet(),
                    onToggleRecord = { did += "record" },
                )
            }
        }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the line shows who is on, who is speaking and who is muted`() = full(live()) {
        for (t in listOf("Planting season", "3 people on the line · 4 listening", "Zod (you)", "Bus", "Speaking", "Nec", "Muted")) {
            assertTrue(shows(t), "shows $t")
        }
    }

    @Test
    fun `mute, leave and minimize each ask the line`() {
        full(live(muted = false)) {
            onNodeWithContentDescription("Mute").performClick()
            // The word under a button is part of it: a click there works too.
            onNodeWithText("Leave", useUnmergedTree = true).performClick()
            onNodeWithContentDescription("Minimize the call").performClick()
            waitForIdle()
        }
        assertEquals(listOf("mute:true", "leave", "minimize"), did)
        did.clear()
        full(live(muted = true)) {
            onNodeWithText("Unmute", useUnmergedTree = true).performClick()
            waitForIdle()
        }
        assertEquals(listOf("mute:false"), did)
    }

    @Test
    fun `a recording says who records, and Stop asks the line`() = full(live(), recording = true) {
        assertTrue(shows("Recording · Bus"))
        onNodeWithContentDescription("Stop").performClick()
        waitForIdle()
        assertEquals(listOf("record"), did)
    }

    @Test
    fun `an admin can take a voice or send a message from someone's options`() = full(live(ops = true)) {
        onNodeWithContentDescription("Options for Bus").performClick()
        onNodeWithText("Mute for everyone").performClick()
        waitForIdle()
        onNodeWithContentDescription("Options for Nec").performClick()
        onNodeWithText("Message").performClick()
        waitForIdle()
        assertEquals(listOf("revoke:~bus", "message:~nec"), did)
    }

    @Test
    fun `without an admin's rights, the options only message`() = full(live(ops = false)) {
        onNodeWithContentDescription("Options for Bus").performClick()
        waitForIdle()
        assertTrue(shows("Message"))
        assertTrue(!shows("Mute for everyone"), "only an admin mutes others")
    }

    @Test
    fun `a listener has no mic to switch, and is told they are listening`() = full(live(canSpeak = false)) {
        assertTrue(shows("Listening"))
        assertTrue(!shows("Mute") && !shows("Unmute"))
    }

    private fun bar(state: PartyState, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineBarContent(
                    state = state, onToggleMute = { did += "mute:$it" }, onLeave = { did += "leave" },
                    nameFor = { names[it] ?: it },
                )
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun `the bar says who is on, and its mute and leave ask the line`() = bar(live()) {
        assertTrue(onAllNodesWithText("3 on the line · 4 listening", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Zod, Bus, Nec", substring = true).fetchSemanticsNodes().isNotEmpty(), "by name")
        onNodeWithContentDescription("Mute").performClick()
        onNodeWithContentDescription("Leave the line").performClick()
        waitForIdle()
        assertEquals(listOf("mute:true", "leave"), did)
    }

    @Test
    fun `the bar says it is joining, and says why a line failed`() {
        bar(PartyState.Connecting("garden-chat")) {
            assertTrue(onAllNodesWithText("Joining the line…").fetchSemanticsNodes().isNotEmpty())
            onNodeWithContentDescription("Leave the line").performClick() // joining can still be called off
            waitForIdle()
        }
        assertEquals(listOf("leave"), did)
        bar(PartyState.Failed("garden-chat", "the host is gone")) {
            assertTrue(onAllNodesWithText("Party line: the host is gone").fetchSemanticsNodes().isNotEmpty())
        }
    }

    // ─── the bar opened out (desktop) ─────────────────────────────

    private fun openBar(state: PartyState, recording: Boolean = false, recordedBy: Set<String> = emptySet(), block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineBarContent(
                    state = state, nameFor = { names[it] ?: it }, selfShip = "~zod",
                    onRevokeSpeaking = { did += "revoke:$it" }, onRestoreSpeaking = { did += "restore:$it" },
                    onMessage = { did += "message:$it" },
                    recording = recording, recordedBy = recordedBy, onToggleRecord = { did += "record" },
                )
            }
        }
        onNodeWithContentDescription("Who's on the line").performClick()
        waitForIdle()
        block()
    }

    @Test
    fun `opened out, the bar lists who is on and who is muted, with how many listen by link`() = openBar(live()) {
        assertTrue(onAllNodesWithText("Bus").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithText("Nec").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithContentDescription("Muted").fetchSemanticsNodes().isNotEmpty(), "Nec is marked muted")
        assertTrue(onAllNodesWithText("4 listening by link").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithContentDescription("Options for Zod").fetchSemanticsNodes().isEmpty(), "no options on ourselves")
    }

    @Test
    fun `an admin mutes someone for everyone from the opened bar, and anyone can message them`() = openBar(live(ops = true)) {
        onNodeWithContentDescription("Options for Bus").performClick()
        onNodeWithText("Mute for everyone").performClick()
        onNodeWithContentDescription("Options for Nec").performClick()
        onNodeWithText("Message").performClick()
        waitForIdle()
        assertEquals(listOf("revoke:~bus", "message:~nec"), did)
    }

    @Test
    fun `the opened bar records, and says who else is`() = openBar(live(), recordedBy = setOf("~bus")) {
        assertTrue(onAllNodesWithText("Recording · Bus").fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Record").performClick()
        waitForIdle()
        assertEquals(listOf("record"), did)
    }

    // ─── the devices, the window, and a line that failed ──────────

    private class Speakers(val takes: Boolean = true) : AudioDevices {
        var chosen: String? = null
        override val supported = true
        override fun outputs() = listOf(AudioDevice("spk", "Speakers"), AudioDevice("hs", "Headset"))
        override val selectedOutput get() = chosen
        override fun selectOutput(id: String?) { if (takes) chosen = id }
    }

    private object Cameras : VideoDevices {
        override val supported = true
        override fun cameras() = listOf(VideoDevice("front", "Front"), VideoDevice("back", "Back"))
        override val selectedCamera = "front"
    }

    private inner class Window : WindowFullScreen {
        var on by mutableStateOf(false)
        override fun isFullScreen() = on
        override fun set(full: Boolean) { did += "window:$full"; on = full }
    }

    private fun controls(audio: AudioDevices = AudioDevices.Noop, cameraOn: Boolean = false, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val window = Window()
        setContent {
            CompositionLocalProvider(LocalWindowFullScreen provides window) {
                TalonTheme(darkTheme = false) {
                    PartyLineFullScreen(
                        state = live(), roomName = "Garden chat", nameFor = { names[it] ?: it }, selfShip = "~zod",
                        onToggleMute = {}, onLeave = {}, onMinimize = {},
                        audioDevices = audio, videoDevices = Cameras, onSelectCamera = { did += "source:$it" },
                        cameraOn = cameraOn, onToggleCamera = { did += "camera" }, onSwitchCamera = { did += "flip" },
                    )
                }
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun `the speaker is picked from the outputs, and the caption says what took`() {
        val speakers = Speakers()
        controls(speakers) {
            assertTrue(shows("System default"), "nothing chosen is the system's choice")
            onNodeWithText("Audio").performClick()
            onNodeWithText("Headset").performClick()
            waitForIdle()
            assertTrue(shows("Headset"))
        }
        assertEquals("hs", speakers.chosen)
        controls(Speakers(takes = false)) {
            onNodeWithText("Audio").performClick()
            onNodeWithText("Headset").performClick()
            waitForIdle()
            assertTrue(shows("System default") && !shows("Headset"), "a route the platform refused is not claimed")
        }
    }

    @Test
    fun `the camera turns on, its source is picked, and it flips only while on`() {
        controls {
            assertTrue(onAllNodesWithContentDescription("Flip").fetchSemanticsNodes().isEmpty())
            onNodeWithContentDescription("Camera").performClick()
            assertTrue(shows("Front"), "the camera in use")
            onNodeWithText("Source").performClick()
            onNodeWithText("Back").performClick()
            waitForIdle()
            onNodeWithText("Source").performClick()
            onAllNodesWithText("Back").onLast().performClick()
            waitForIdle()
        }
        assertEquals(listOf("camera", "source:back"), did.filterNot { it.startsWith("window:") }, "the camera in use picked again restarts nothing")
        did.clear()
        controls(cameraOn = true) {
            onNodeWithContentDescription("Camera off").assertExists()
            onNodeWithContentDescription("Flip").performClick()
            waitForIdle()
        }
        assertEquals(listOf("flip"), did.filterNot { it.startsWith("window:") })
    }

    @Test
    fun `full screen fills the window, and leaving the call gives it back`() {
        controls {
            onNodeWithContentDescription("Full screen").performClick()
            waitForIdle()
            onNodeWithContentDescription("Exit full screen").assertExists()
        }
        assertEquals(listOf("window:true", "window:false"), did)
    }

    @Test
    fun `a join that failed says why, and Dismiss clears it on the line itself`() {
        val line = PartyLine(HttpClient(MockEngine { throw IllegalStateException("sfu unreachable") }), links = { _, _ -> error("no media") })
        line.join(TrunkTicket("garden-chat", "http://sfu.test/group/g/r/", "tok"), "~zod")
        runComposeUiTest {
            setContent { TalonTheme(darkTheme = false) { PartyLineBar(line) } }
            waitUntil(timeoutMillis = 5_000) { shows("Party line: sfu unreachable") }
            onNodeWithText("Dismiss").performClick()
            waitForIdle()
            assertEquals(PartyState.Idle, line.state.value)
            assertFalse(shows("Party line: sfu unreachable"))
        }
    }

    // ─── the bar's camera, the meeting view, and the asking row ───

    private fun cameraBar(state: PartyState.Live, cameraError: Boolean = false, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineBarContent(
                    state = state, nameFor = { names[it] ?: it }, selfShip = "~zod",
                    onToggleCamera = { did += "camera" }, cameraError = cameraError, partyVideoSupported = true,
                    onOpenMeeting = { did += "meeting" },
                )
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun `turning the camera on opens the bar out, where your own picture is`() = cameraBar(live()) {
        onNodeWithContentDescription("Turn the camera on").performClick()
        waitForIdle()
        onNodeWithContentDescription("Hide who's on the line").assertExists()
        onNodeWithText("Meeting view").performClick()
        waitForIdle()
        assertEquals(listOf("camera", "meeting"), did)
    }

    @Test
    fun `a listener has no camera to turn on, and a camera that would not start says so`() {
        cameraBar(live(canSpeak = false)) {
            assertTrue(onAllNodesWithContentDescription("Turn the camera on").fetchSemanticsNodes().isEmpty())
        }
        cameraBar(live(), cameraError = true) {
            assertTrue(shows("The camera wouldn't start — no camera, or permission refused."))
        }
    }

    @Test
    fun `an admin lets someone they muted speak again`() {
        val state = live(ops = true).let { s -> s.copy(members = s.members.map { if (it.ship == "~nec") it.copy(mutedByAdmin = true) else it }) }
        openBar(state) {
            onNodeWithContentDescription("Muted by an admin").assertExists()
            onNodeWithContentDescription("Options for Nec").performClick()
            assertTrue(!shows("Mute for everyone"), "the one already muted is offered their voice back")
            onNodeWithText("Allow speaking").performClick()
            waitForIdle()
        }
        assertEquals(listOf("restore:~nec"), did)
    }

    @Test
    fun `asking the host can be called off`() = runComposeUiTest {
        setContent { TalonTheme(darkTheme = false) { PartyLineAsking(onCancel = { did += "cancel" }) } }
        assertTrue(shows("Asking the host…"))
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(listOf("cancel"), did)
    }

    @Test
    fun `a meeting on a line that is not live closes itself`() = runComposeUiTest {
        val line = PartyLine(HttpClient(MockEngine { error("unused") }), links = { _, _ -> error("no media") })
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineMeeting(line, nameFor = { it }, selfShip = "~zod", audioDevices = AudioDevices.Noop, videoDevices = VideoDevices.Noop, onClose = { did += "close" })
            }
        }
        waitUntil(timeoutMillis = 5_000) { "close" in did }
    }
}
