package io.nisfeb.talon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.AudioDevice
import io.nisfeb.talon.call.AudioDevices
import io.nisfeb.talon.call.AudioRouteKind
import io.nisfeb.talon.call.CallProximity
import io.nisfeb.talon.call.CallUiState
import io.nisfeb.talon.call.MediaState
import io.nisfeb.talon.call.PartyMember
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * sneagan, 2026-11-22: "during a call on phone there should be a speaker
 * option. also when using the earpiece on the phone the screen should
 * turn off when the phone is held to the head." The route list was only
 * behind ⋯ → Devices, and nothing turned the screen off.
 */
@OptIn(ExperimentalTestApi::class)
class CallSpeakerTest {
    /** A phone's routes as its platform reports them; [takes] false is a pick it refuses. */
    private class Phone(var routes: List<AudioDevice>, start: AudioRouteKind = AudioRouteKind.Earpiece) : AudioDevices {
        val picked = mutableListOf<String?>()
        var takes = true
        val now = MutableStateFlow(start)
        override val supported = true
        override val unifiedRoute = true
        override val route = now
        override fun inputs() = routes
        override fun outputs() = routes
        override val selectedInput: String? get() = routes.firstOrNull { it.kind == now.value }?.id
        override val selectedOutput: String? get() = selectedInput
        override fun selectInput(id: String?) = selectOutput(id)
        override fun selectOutput(id: String?) {
            picked += id
            if (takes) now.value = routes.firstOrNull { it.id == id }?.kind ?: AudioRouteKind.Other
        }
    }

    private val ear = AudioDevice("ear", "Earpiece", AudioRouteKind.Earpiece)
    private val speaker = AudioDevice("spk", "Speaker", AudioRouteKind.Speaker)
    private val buds = AudioDevice("bt", "Pixel Buds")

    private val line = PartyState.Live(
        room = "garden", members = listOf(PartyMember(id = "1", ship = "~zod")), muted = false, media = MediaState.Live,
    )

    private fun ComposeUiTest.shows(description: String) = onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun bar(devices: AudioDevices, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent { TalonTheme(darkTheme = false) { PartyLineBarContent(state = line, audioDevices = devices) } }
        waitForIdle()
        block()
    }

    private fun full(devices: AudioDevices, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                PartyLineFullScreen(
                    state = line, roomName = "Garden", directCall = true, nameFor = { it }, selfShip = "~zod",
                    onToggleMute = {}, onLeave = {}, onMinimize = {}, audioDevices = devices,
                )
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun `on a phone the bar has a speaker beside the mic, and it flips the route both ways`() {
        val phone = Phone(listOf(ear, speaker))
        bar(phone) {
            assertTrue(shows("Speaker off"))
            onNodeWithContentDescription("Speaker off").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Speaker on") }
            onNodeWithContentDescription("Speaker on").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Speaker off") }
            // Off picks the earpiece by name, never "the system's choice".
            assertEquals(listOf<String?>("spk", "ear"), phone.picked)
        }
    }

    @Test
    fun `the full-screen call has the same speaker, and a refused pick shows as refused`() {
        val phone = Phone(listOf(ear, speaker)).apply { takes = false }
        full(phone) {
            assertTrue(onAllNodesWithText("Speaker").fetchSemanticsNodes().isNotEmpty())
            onNodeWithContentDescription("Speaker off").performClick()
            waitForIdle()
            assertTrue(shows("Speaker off"), "the platform said no, so it stays off")
            assertEquals(listOf<String?>("spk"), phone.picked)
        }
    }

    @Test
    fun `the button follows the route the platform moves, not only the press`() {
        val phone = Phone(listOf(ear, speaker))
        bar(phone) {
            phone.now.value = AudioRouteKind.Speaker
            waitUntil(timeoutMillis = 5_000) { shows("Speaker on") }
        }
    }

    // Telecom taking the call renames the same earpiece and speaker
    // ("route:1" for the audio manager's "7"): the kind holds, the ids do not.
    @Test
    fun `a press picks from the routes as they are then, not as they were drawn`() {
        val phone = Phone(listOf(ear, speaker))
        bar(phone) {
            phone.routes = listOf(ear.copy(id = "route:1"), speaker.copy(id = "route:8"))
            onNodeWithContentDescription("Speaker off").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Speaker on") }
            assertEquals(listOf<String?>("route:8"), phone.picked)
        }
    }

    // A phone opens a call full screen: its five buttons stay one row at 360 dp.
    @Test
    fun `the full-screen call's buttons stay one row on a phone`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(360.dp)) {
                    PartyLineFullScreen(
                        state = line, roomName = "Garden", directCall = true, nameFor = { it }, selfShip = "~zod",
                        onToggleMute = {}, onLeave = {}, onMinimize = {}, audioDevices = Phone(listOf(ear, speaker)),
                        cameraOn = true, onToggleCamera = {},
                    )
                }
            }
        }
        waitForIdle()
        fun top(word: String) = onAllNodesWithText(word).fetchSemanticsNodes().single().boundsInRoot.top
        assertEquals(top("Mute"), top("Hang up"), "Hang up wrapped onto a row of its own")
        assertEquals(top("Mute"), top("Speaker"))
    }

    @Test
    fun `with a headset in as well, the button opens the route list`() {
        val phone = Phone(listOf(ear, speaker, buds), start = AudioRouteKind.Other)
        bar(phone) {
            onNodeWithContentDescription("Choose where the sound goes").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Pixel Buds").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(phone.picked.isEmpty(), "the list picks, not the button")
        }
    }

    @Test
    fun `a computer's devices give no speaker button in either view`() {
        val desk = Phone(listOf(AudioDevice("a", "Speakers"), AudioDevice("b", "Headphones")), start = AudioRouteKind.Other)
        bar(desk) { assertTrue(!shows("Speaker off") && !shows("Choose where the sound goes")) }
        full(desk) { assertTrue(onAllNodesWithText("Speaker").fetchSemanticsNodes().isEmpty()) }
    }

    // ─── the screen off at the ear ───

    private class Sensor : CallProximity {
        val said = mutableListOf<Boolean>()
        var held = false
        override fun screenOffNearFace(on: Boolean) { said += on; held = on }
    }

    @Test
    fun `the screen goes off near the face on the earpiece, and comes back on the speaker and when the call ends`() = runComposeUiTest {
        val call = MutableStateFlow<CallUiState>(CallUiState.Incoming("~bus"))
        val phone = Phone(listOf(ear, speaker))
        val sensor = Sensor()
        setContent { CallProximityEffect(call, null, phone, sensor) }
        waitForIdle()
        assertTrue(!sensor.held, "ringing in: the screen answers it")
        call.value = CallUiState.Active("~bus", MediaState.Live, muted = false)
        waitUntil(timeoutMillis = 5_000) { sensor.held }
        phone.now.value = AudioRouteKind.Speaker
        waitUntil(timeoutMillis = 5_000) { !sensor.held }
        phone.now.value = AudioRouteKind.Earpiece
        waitUntil(timeoutMillis = 5_000) { sensor.held }
        // Hung up from the far end: the call goes, and the lock with it.
        call.value = CallUiState.Ended("~bus", "they hung up")
        waitUntil(timeoutMillis = 5_000) { !sensor.held }
    }

    @Test
    fun `a line on the earpiece holds it, and the lock goes with the shell that held it`() = runComposeUiTest {
        val party = MutableStateFlow<PartyState>(PartyState.Idle)
        val sensor = Sensor()
        var shown by mutableStateOf(true)
        setContent { if (shown) CallProximityEffect(null, party, Phone(listOf(ear, speaker)), sensor) }
        party.value = line
        waitUntil(timeoutMillis = 5_000) { sensor.held }
        shown = false
        waitUntil(timeoutMillis = 5_000) { !sensor.held }
    }

    @Test
    fun `both shells hold the screen off at the ear`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        for (shell in listOf("composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt", "composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt")) {
            assertTrue("CallProximityEffect(" in File(root, shell).readText(), "$shell does not call CallProximityEffect")
        }
        assertTrue("callProximity = io.nisfeb.talon.call.IosCallProximity" in File(root, "composeApp/src/iosMain/kotlin/io/nisfeb/talon/MainViewController.kt").readText(), "iOS gives App its sensor")
    }
}
