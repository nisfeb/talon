package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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

    /** [item] from the meeting view's More menu. */
    private fun ComposeUiTest.more(item: String) {
        onNodeWithContentDescription("Meeting options").performClick()
        onNodeWithText(item).performClick()
        waitForIdle()
    }

    /** A right-click on [name]'s row, where an admin's mute for everyone is. */
    private fun ComposeUiTest.rightClick(name: String) {
        onNode(hasText(name) and hasClickAction()).performMouseInput { rightClick() }
        waitForIdle()
    }

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
        more("Stop recording")
        assertEquals(listOf("record"), did)
    }

    // A tap on someone messages them; the ⋯ on each person is gone
    // (sneagan, 2026-11-02). An admin's mute is a right-click or a long press.
    @Test
    fun `a tap on someone messages them, and an admin mutes them with a right-click or a long press`() = full(live(ops = true)) {
        rightClick("Bus")
        onNodeWithText("Mute for everyone").performClick()
        waitForIdle()
        onNode(hasText("Nec") and hasClickAction()).performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText("Mute for everyone").performClick()
        waitForIdle()
        onNode(hasText("Nec") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf("revoke:~bus", "revoke:~nec", "message:~nec"), did)
        assertTrue(onAllNodesWithContentDescription("Options for Bus").fetchSemanticsNodes().isEmpty(), "no ⋯ on a person")
    }

    @Test
    fun `without an admin's rights, someone is only messaged, and we are not`() = full(live(ops = false)) {
        rightClick("Bus")
        assertTrue(!shows("Mute for everyone"), "only an admin mutes others")
        onNode(hasText("Bus") and hasClickAction()).performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasText("Zod (you)") and hasClickAction()).fetchSemanticsNodes().isEmpty(), "no one to message in ourselves")
        assertEquals(listOf("message:~bus"), did)
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
        onNodeWithContentDescription("More call options").performClick()
        onNodeWithText("Who's on the line").performClick()
        waitForIdle()
        block()
    }

    @Test
    fun `opened out, the bar lists who is on and who is muted, with how many listen by link`() = openBar(live()) {
        assertTrue(onAllNodesWithText("Bus").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithText("Nec").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithContentDescription("Muted").fetchSemanticsNodes().isNotEmpty(), "Nec is marked muted")
        assertTrue(onAllNodesWithText("4 listening by link").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("Zod") and hasClickAction()).fetchSemanticsNodes().isEmpty(), "no one to message in ourselves")
    }

    @Test
    fun `an admin mutes someone for everyone from the opened bar, and anyone can message them`() = openBar(live(ops = true)) {
        rightClick("Bus")
        onNodeWithText("Mute for everyone").performClick()
        onNode(hasText("Nec") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf("revoke:~bus", "message:~nec"), did)
    }

    // Record is in the bar's ⋯ menu now, and the bar itself says a recording is on.
    @Test
    fun `the bar records from its menu, and says a recording is on`() = openBar(live(), recordedBy = setOf("~bus")) {
        assertTrue(onAllNodesWithText("● Recording", substring = true).fetchSemanticsNodes().isNotEmpty())
        onNodeWithContentDescription("More call options").performClick()
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

    /** A microphone or speaker the platform reports chosen, its row ticked. */
    private fun ComposeUiTest.ticked(label: String) =
        onAllNodes(hasText(label) and hasContentDescription("Selected")).fetchSemanticsNodes().isNotEmpty()

    /** [zoom] is the size setting, through the shell's own provider. */
    private fun controls(audio: AudioDevices = AudioDevices.Noop, cameraOn: Boolean = false, zoom: Float = 1f, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val window = Window()
        val settings = InMemoryUiSettings().apply { setFontScale(zoom) }
        setContent {
            CompositionLocalProvider(LocalWindowFullScreen provides window, *chatDensityLocals(settings)) {
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

    private class Mics : AudioDevices {
        var mic: String? = null
        override val supported = true
        override fun inputs() = listOf(AudioDevice("usb", "USB mic"), AudioDevice("obs", "OBS virtual mic"))
        override fun outputs() = listOf(AudioDevice("spk", "Speakers"))
        override val selectedInput get() = mic
        override fun selectInput(id: String?) { mic = id }
    }

    // A user report: "Add a mic input selector in meeting view."
    @Test
    fun `the microphone is chosen in the meeting view, beside the speaker`() {
        val mics = Mics()
        controls(mics) {
            more("Devices")
            assertTrue(shows("Microphone") && shows("Speaker") && shows("Camera"))
            onNodeWithText("OBS virtual mic").performClick()
            waitForIdle()
            assertTrue(ticked("OBS virtual mic"))
        }
        assertEquals("obs", mics.mic)
    }

    // A user report: the meeting view's icons "don't scale properly with zoom".
    @Test
    fun `the meeting view's buttons grow with the zoom`() {
        fun circle(zoom: Float): Int {
            var h = 0
            controls(Mics(), zoom = zoom) {
                h = onNode(hasContentDescription("Meeting options"), useUnmergedTree = true).fetchSemanticsNode().size.height
                assertTrue(onAllNodesWithText("Audio").fetchSemanticsNodes().isEmpty() && onAllNodesWithText("Source").fetchSemanticsNodes().isEmpty(), "no words in the circles")
            }
            return h
        }
        val at100 = circle(1f)
        val at160 = circle(1.6f)
        assertEquals(1.6f, at160.toFloat() / at100, 0.03f)
    }

    @Test
    fun `the speaker is picked from the outputs, and the caption says what took`() {
        val speakers = Speakers()
        controls(speakers) {
            more("Devices")
            assertTrue(!ticked("Headset") && ticked("System default"), "nothing chosen is the system's choice")
            onNodeWithText("Headset").performClick()
            waitForIdle()
            assertTrue(ticked("Headset"))
        }
        assertEquals("hs", speakers.chosen)
        controls(Speakers(takes = false)) {
            more("Devices")
            onNodeWithText("Headset").performClick()
            waitForIdle()
            assertTrue(!ticked("Headset"), "a route the platform refused is not claimed")
        }
    }

    @Test
    fun `the camera turns on, its source is picked, and it flips only while on`() {
        controls {
            onNodeWithContentDescription("Meeting options").performClick()
            assertTrue(!shows("Flip camera"), "no flip while the camera is off")
            onNodeWithText("Devices").performClick()
            onNodeWithText("Done").performClick()
            onNodeWithContentDescription("Camera").performClick()
            more("Devices")
            assertTrue(ticked("Front"), "the camera in use")
            onNodeWithText("Back").performClick()
            waitForIdle()
            onNodeWithText("Back").performClick()
            waitForIdle()
        }
        assertEquals(listOf("camera", "source:back"), did.filterNot { it.startsWith("window:") }, "the camera in use picked again restarts nothing")
        did.clear()
        controls(cameraOn = true) {
            onNodeWithContentDescription("Camera off").assertExists()
            more("Flip camera")
        }
        assertEquals(listOf("flip"), did.filterNot { it.startsWith("window:") })
    }

    @Test
    fun `full screen fills the window, and leaving the call gives it back`() {
        controls {
            more("Full screen")
            onNodeWithContentDescription("Meeting options").performClick()
            onNodeWithText("Exit full screen").assertExists()
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

    // ─── sharing a screen ───

    private val screen = io.nisfeb.talon.call.ScreenSource(1, "Built-in display", isWindow = false)
    private val window = io.nisfeb.talon.call.ScreenSource(7, "Notes", isWindow = true)

    private fun shareControl(vararg sources: io.nisfeb.talon.call.ScreenSource, accept: Boolean = true) =
        ScreenShareControl({ sources.toList() }, { s -> did += "share:${s?.title}"; accept })

    private fun shareBar(state: PartyState.Live, control: ScreenShareControl?, sharing: Boolean = false, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineBarContent(
                    state = state, nameFor = { names[it] ?: it }, selfShip = "~zod",
                    screenShare = control, sharing = sharing, partyVideoSupported = true,
                    onOpenMeeting = { did += "meeting" },
                )
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun `a share is picked from the screens and windows, and the bar opens out to show it`() = shareBar(live(), shareControl(screen, window)) {
        onNodeWithContentDescription("Share your screen").performClick()
        waitForIdle()
        onNodeWithText("Built-in display").assertExists()
        onNodeWithText("Notes").performClick()
        waitForIdle()
        assertEquals(listOf("share:Notes"), did)
        assertTrue(shows("4 listening by link"), "opened out")
    }

    @Test
    fun `sharing offers a stop, and a share that would not start says so`() {
        shareBar(live(), shareControl(screen), sharing = true) {
            onNodeWithContentDescription("Stop sharing").performClick()
            waitForIdle()
            assertEquals(listOf("share:null"), did)
        }
        did.clear()
        shareBar(live(), shareControl(screen, accept = false)) {
            onNodeWithContentDescription("Share your screen").performClick()
            waitForIdle()
            assertEquals(listOf("share:Built-in display"), did, "one screen is shared without a menu")
            assertTrue(shows("Couldn't share: nothing to share, or the system refused the capture."))
        }
    }

    @Test
    fun `there is no share button where sharing isn't built, or for a listener`() {
        shareBar(live(), control = null) {
            assertTrue(onAllNodesWithContentDescription("Share your screen").fetchSemanticsNodes().isEmpty())
        }
        shareBar(live(canSpeak = false), shareControl(screen)) {
            assertTrue(onAllNodesWithContentDescription("Share your screen").fetchSemanticsNodes().isEmpty())
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
        assertTrue(shows("4 listening by link"), "opened out")
        onNodeWithContentDescription("More call options").performClick()
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
            rightClick("Nec")
            assertTrue(!shows("Mute for everyone"), "the one already muted is offered their voice back")
            onNodeWithText("Allow speaking").performClick()
            waitForIdle()
        }
        assertEquals(listOf("restore:~nec"), did)
    }

    // sneagan, 2026-11-02: "everything other than mute, camera, screen
    // share, and leave should be in a settings ellipsis in calls".
    @Test
    fun `the bar keeps mute, camera, share and leave, and the rest is in its menu`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineBarContent(
                    state = live(), nameFor = { names[it] ?: it }, selfShip = "~zod",
                    audioDevices = Speakers(), onToggleRecord = { did += "record" }, onOpenMeeting = { did += "meeting" },
                    onToggleCamera = { did += "camera" }, screenShare = shareControl(screen), partyVideoSupported = true,
                )
            }
        }
        waitForIdle()
        for (b in listOf("Mute", "Turn the camera on", "Share your screen", "More call options", "Leave the line")) {
            assertTrue(onAllNodesWithContentDescription(b).fetchSemanticsNodes().isNotEmpty(), "the bar has $b")
        }
        assertTrue(onAllNodesWithContentDescription("Who's on the line").fetchSemanticsNodes().isEmpty(), "no arrow to open it out")
        onNodeWithContentDescription("More call options").performClick()
        waitForIdle()
        for (item in listOf("Who's on the line", "Meeting view", "Devices", "Record")) assertTrue(shows(item), "the menu has $item")
        onNodeWithText("Devices").performClick()
        waitForIdle()
        assertTrue(shows("Speaker") && shows("Headset"), "the devices, in their dialog")
    }

    // A user's iOS screenshot, 2026-11-03: the full-screen call showed the
    // chat beneath it where its video was. iOS cannot hold native video in
    // a Dialog, so where the host draws its own full-window view (App.kt),
    // a phone's full screen is that; the Dialog stays for Android.
    private fun ComposeUiTest.phoneBar(onOpenMeeting: (() -> Unit)?, autoOpenKey: Any? = null) {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineBarContent(
                    state = live(), nameFor = { names[it] ?: it }, selfShip = "~zod",
                    onOpenMeeting = onOpenMeeting, autoOpenKey = autoOpenKey, immersive = true,
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.ownDialog() = onAllNodesWithContentDescription("Minimize the call").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a phone's full screen is the host's own view where it has one, not a dialog`() = runComposeUiTest {
        var opened = 0
        phoneBar(onOpenMeeting = { opened++ })
        onNodeWithContentDescription("More call options").performClick()
        waitForIdle()
        onNodeWithText("Open the full-screen call").performClick()
        waitForIdle()
        assertEquals(1, opened)
        assertFalse(ownDialog(), "no Dialog of its own")
    }

    @Test
    fun `a call that connects on a phone opens the host's view once`() = runComposeUiTest {
        var opened = 0
        phoneBar(onOpenMeeting = { opened++ }, autoOpenKey = "call-1")
        assertEquals(1, opened)
        assertFalse(ownDialog())
    }

    @Test
    fun `without a view of the host's, a phone's full screen is its dialog`() = runComposeUiTest {
        phoneBar(onOpenMeeting = null)
        onNodeWithContentDescription("More call options").performClick()
        waitForIdle()
        onNodeWithText("Open the full-screen call").performClick()
        waitForIdle()
        assertTrue(ownDialog(), "Android's Dialog")
    }

    @Test
    fun `the meeting view keeps mute, camera, share and leave, and the rest is behind More`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalWindowFullScreen provides Window()) {
                TalonTheme(darkTheme = false) {
                    PartyLineFullScreen(
                        state = live(), roomName = "Garden chat", nameFor = { names[it] ?: it }, selfShip = "~zod",
                        onToggleMute = {}, onLeave = {}, onMinimize = {},
                        audioDevices = Speakers(), videoDevices = Cameras, onSelectCamera = {},
                        cameraOn = true, onToggleCamera = {}, onSwitchCamera = {}, screenShare = shareControl(screen),
                        onToggleRecord = {},
                    )
                }
            }
        }
        waitForIdle()
        for (b in listOf("Mute", "Camera off", "Share", "Meeting options", "Leave")) {
            assertTrue(onAllNodesWithContentDescription(b).fetchSemanticsNodes().isNotEmpty(), "the meeting has $b")
        }
        for (gone in listOf("Devices", "Full screen", "Flip", "Record")) {
            assertTrue(onAllNodesWithContentDescription(gone).fetchSemanticsNodes().isEmpty(), "$gone is not a button of its own")
        }
        onNodeWithContentDescription("Meeting options").performClick()
        waitForIdle()
        for (item in listOf("Devices", "Full screen", "Flip camera", "Record")) assertTrue(shows(item), "More has $item")
    }

    @Test
    fun `the meeting's More shows and hides its chat, and has no chat item without one`() = runComposeUiTest {
        var chatOpen by mutableStateOf(false)
        var withChat by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalWindowFullScreen provides Window()) {
                TalonTheme(darkTheme = false) {
                    PartyLineFullScreen(
                        state = live(), roomName = "Garden chat", nameFor = { names[it] ?: it }, selfShip = "~zod",
                        onToggleMute = {}, onLeave = {}, onMinimize = {},
                        onToggleChat = if (withChat) ({ chatOpen = !chatOpen }) else null,
                        chatOpen = chatOpen,
                    )
                }
            }
        }
        more("Show chat")
        assertTrue(chatOpen)
        more("Hide chat")
        assertTrue(!chatOpen)
        withChat = false
        onNodeWithContentDescription("Meeting options").performClick()
        waitForIdle()
        assertTrue(!shows("Show chat") && !shows("Hide chat"))
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

    // ─── a picture filling the window (2026-10-09) ───────────────

    private val onCamera = androidx.compose.runtime.mutableStateOf(setOf("~bus"))

    private fun fill(canFill: Boolean = true, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineFullScreen(
                    state = live(), roomName = "Garden chat", nameFor = { names[it] ?: it }, selfShip = "~zod",
                    onToggleMute = { did += "mute:$it" }, onLeave = { did += "leave" }, onMinimize = { did += "minimize" },
                    partyVideoSupported = true, videoOnShips = onCamera.value,
                    onFocusVideo = { did += "focus:$it" }, canFillWindow = canFill,
                )
            }
        }
        mainClock.advanceTimeBy(100)
        block()
    }

    private fun ComposeUiTest.filled() = onAllNodesWithTag("filled-video").fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.controlsShown() = onAllNodesWithContentDescription("Mute").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a camera or shared screen fills the window, and Escape or the X goes back`() = fill {
        onNodeWithContentDescription("Fill the window").performClick()
        mainClock.advanceTimeBy(100)
        assertTrue(filled())
        assertTrue(!shows("Planting season"), "nothing of the meeting around it")
        assertTrue(shows("Bus"), "whose picture it is")
        assertEquals(emptyList(), did, "nothing pinned: a pin changes nothing on the wire, and leaving kept it")
        onNodeWithTag("filled-video").performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(100)
        assertTrue(!filled() && shows("Planting season"), "Escape is back to the meeting")

        onNodeWithContentDescription("Fill the window").performClick()
        mainClock.advanceTimeBy(100)
        onNodeWithContentDescription("Back to the meeting").performClick()
        mainClock.advanceTimeBy(100)
        assertTrue(!filled() && shows("Planting season"), "so is the X")
    }

    @Test
    fun `the controls fade once the mouse stops, come back when it moves, and stay while it rests on them`() = fill {
        onNodeWithContentDescription("Fill the window").performClick()
        mainClock.advanceTimeBy(1_000)
        assertTrue(controlsShown(), "up at first")
        onNodeWithContentDescription("Mute").performClick()
        mainClock.advanceTimeBy(100)
        assertEquals("mute:true", did.last(), "and they work")
        onNodeWithTag("filled-video").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(50f, 200f)) }
        mainClock.advanceTimeBy(CONTROLS_FADE_MS + 1_000)
        assertTrue(!controlsShown(), "gone once the mouse stops")
        assertTrue(onAllNodesWithContentDescription("Back to the meeting").fetchSemanticsNodes().isEmpty(), "the X with them")

        onNodeWithTag("filled-video").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(60f, 210f)) }
        mainClock.advanceTimeBy(100)
        assertTrue(controlsShown(), "back on a move")
        val mute = onNodeWithContentDescription("Mute").fetchSemanticsNode().boundsInRoot.center
        onNodeWithTag("filled-video").performMouseInput { moveTo(mute) }
        mainClock.advanceTimeBy(CONTROLS_FADE_MS * 3)
        assertTrue(controlsShown(), "kept while the mouse is on them")
        onNodeWithTag("filled-video").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(50f, 200f)) }
        mainClock.advanceTimeBy(CONTROLS_FADE_MS + 1_000)
        assertTrue(!controlsShown(), "and fade once it leaves them")
    }

    @Test
    fun `a picture that goes away gives back the meeting`() = fill {
        onNodeWithContentDescription("Fill the window").performClick()
        mainClock.advanceTimeBy(100)
        assertTrue(filled())
        onCamera.value = emptySet()
        mainClock.advanceTimeBy(100)
        assertTrue(!filled() && shows("Planting season"))
    }

    @Test
    fun `only the meeting view offers to fill the window`() = fill(canFill = false) {
        assertTrue(onAllNodesWithContentDescription("Fill the window").fetchSemanticsNodes().isEmpty())
    }

    // Trunk wire 16: a guest, in from an invite link, has no ship.
    @Test
    fun `a guest is marked, and has no ship to message`() = runComposeUiTest {
        val state = live().let { it.copy(members = it.members + PartyMember(id = "4", ship = "guest-3fa9c07b12de", name = "~wes")) }
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineFullScreen(
                    state = state, roomName = "Garden chat", nameFor = withLineNames({ names[it] ?: it }, state), selfShip = "~zod",
                    onToggleMute = {}, onLeave = {}, onMinimize = {}, onMessage = { did += "message:$it" },
                )
            }
        }
        waitForIdle()
        assertTrue(shows("~wes (guest)"), "marked, though it typed an @p")
        assertTrue(onAllNodes(hasText("~wes (guest)", substring = true) and hasClickAction()).fetchSemanticsNodes().isEmpty(), "nothing to do without ops")
        onNode(hasText("Bus") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf("message:~bus"), did, "anyone with a ship is messaged")
    }

    @Test
    fun `a guest's camera or screen gets a tile, and fills the window under its marked name`() = runComposeUiTest {
        val state = live().let { it.copy(members = it.members + PartyMember(id = "4", ship = "guest-3fa9c07b12de", name = "Grandma")) }
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineFullScreen(
                    state = state, roomName = "Garden chat", nameFor = withLineNames({ names[it] ?: it }, state), selfShip = "~zod",
                    onToggleMute = {}, onLeave = {}, onMinimize = {},
                    partyVideoSupported = true, videoOnShips = setOf("guest-3fa9c07b12de"), canFillWindow = true,
                )
            }
        }
        waitForIdle()
        onNodeWithContentDescription("Fill the window").performClick()
        waitForIdle()
        assertTrue(onAllNodesWithTag("filled-video").fetchSemanticsNodes().isNotEmpty(), "the guest's picture is the one shown")
        assertTrue(shows("Grandma (guest)"))
    }

    @Test
    fun `a key brings the controls back over a filled picture, and resting on the X keeps them`() = fill {
        onNodeWithContentDescription("Fill the window").performClick()
        mainClock.advanceTimeBy(CONTROLS_FADE_MS + 1_000)
        assertTrue(!controlsShown(), "faded")
        onNodeWithTag("filled-video").performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(100)
        assertTrue(controlsShown(), "a keyboard brings them back")
        assertTrue(filled(), "and does not leave")
        val x = onNodeWithContentDescription("Back to the meeting").fetchSemanticsNode().boundsInRoot.center
        onNodeWithTag("filled-video").performMouseInput { moveTo(x) }
        mainClock.advanceTimeBy(CONTROLS_FADE_MS * 3)
        assertTrue(onAllNodesWithContentDescription("Back to the meeting").fetchSemanticsNodes().isNotEmpty(), "the X stays under a resting pointer")
    }

    // A 1:1 call's full screen (the phone's, and desktop's meeting view):
    // its picture had a 480dp cap over a "roster" of the one other person
    // that took the rest. The picture takes the room now, and the row that
    // names them and offers Message stays under it.
    private fun directCall(picture: Boolean, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(width = 400.dp, height = 760.dp)) {
                    PartyLineFullScreen(
                        state = PartyState.Live(room = "~bus", members = listOf(PartyMember(id = "~bus", ship = "~bus")), muted = false, media = MediaState.Live),
                        roomName = "Bus · 01:02", directCall = true, nameFor = { names[it] ?: it }, selfShip = "~zod",
                        onToggleMute = {}, onLeave = { did += "hang up" }, onMinimize = {},
                        onMessage = { did += "message:$it" },
                        videoPane = if (picture) {
                            { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("picture")) }
                        } else null,
                    )
                }
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun `a call's picture takes the full screen, over the row that names them`() = directCall(picture = true) {
        val picture = onNodeWithTag("picture").getBoundsInRoot()
        val hangUp = onNodeWithContentDescription("Hang up").getBoundsInRoot()
        assertTrue(picture.height > MAX_VIDEO_PANE_HEIGHT, "the picture has more than the strip's cap: $picture")
        assertTrue(hangUp.top > picture.bottom, "the controls stay below it: $hangUp")
        assertTrue(!shows("1 person on the line"), "a call is not a line")
        assertTrue(shows("Bus"), "who it is, under the picture")
        onNode(hasText("Bus") and hasClickAction()).performClick()
        assertEquals(listOf("message:~bus"), did)
    }

    @Test
    fun `a call with no picture lists who it is`() = directCall(picture = false) {
        assertTrue(onAllNodesWithTag("picture").fetchSemanticsNodes().isEmpty())
        assertTrue(shows("Bus"))
        onNodeWithContentDescription("Hang up").performClick()
        assertEquals(listOf("hang up"), did)
    }
}
