package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import io.nisfeb.talon.call.AudioDevice
import io.nisfeb.talon.call.AudioDevices
import io.nisfeb.talon.call.VideoDevice
import io.nisfeb.talon.call.VideoDevices
import io.nisfeb.talon.ui.theme.TalonTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Choosing the microphone, speaker and camera for a call, in the Devices
 * dialog of its ⋯ menu: "System default" first and always there, one
 * list where the phone routes both ways at once, a long name shown
 * whole, and nothing where the platform offers no choice.
 */
@OptIn(ExperimentalTestApi::class)
class AudioDeviceControlsTest {
    private val did = CopyOnWriteArrayList<String>()

    private inner class Desk(
        override val unifiedRoute: Boolean = false,
        private val mics: List<AudioDevice> = listOf(AudioDevice("usb", "USB mic")),
        private val speakers: List<AudioDevice> = listOf(AudioDevice("hp", "Headphones")),
    ) : AudioDevices {
        override val supported = true
        override fun inputs() = mics
        override fun outputs() = speakers
        override val selectedInput: String? = null
        override val selectedOutput = "hp"
        override fun selectInput(id: String?) { did += "in:$id" }
        override fun selectOutput(id: String?) { did += "out:$id" }
    }

    private val cameras = object : VideoDevices {
        override val supported = true
        override fun cameras() = listOf(VideoDevice("cam", "Webcam"))
        override fun selectCamera(id: String?) { did += "camera:$id" }
    }

    private fun dialog(devices: AudioDevices, camera: Boolean = false, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                DevicesDialog(devices, cameras, if (camera) ({ did += "switch:$it" }) else null) { did += "done" }
            }
        }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the microphone and speaker are listed, a pick goes to the platform, and Done closes`() = dialog(Desk()) {
        assertTrue(shows("Devices") && shows("Microphone") && shows("Speaker"))
        assertEquals(2, onAllNodesWithContentDescription("Selected").fetchSemanticsNodes().size, "the system's mic, and the headphones")
        onNodeWithText("USB mic").performClick()
        onAllNodesWithText("System default")[1].performClick()
        waitForIdle()
        assertEquals(listOf("in:usb", "out:null"), did.toList(), "the default stays reachable")
        onNodeWithText("Done").performClick()
        assertEquals("done", did.last())
    }

    @Test
    fun `where one choice routes both ways there is one list`() = dialog(Desk(unifiedRoute = true)) {
        assertTrue(shows("Route") && !shows("Speaker"))
        onNodeWithText("USB mic").performClick()
        waitForIdle()
        assertEquals(listOf("in:usb"), did.toList())
    }

    @Test
    fun `a camera is a third list, and none found says so`() = dialog(Desk(mics = emptyList()), camera = true) {
        assertTrue(shows("No devices found."), "no microphone")
        onNodeWithText("Webcam").performClick()
        onAllNodesWithText("System default").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForIdle()
        assertEquals(listOf("switch:cam", "camera:null"), did.toList(), "a live camera is switched; the default only remembered")
    }

    // A long name was cut off at the edge of the menu it was in
    // (sneagan, 2026-11-02): it wraps now.
    @Test
    fun `a long device name is shown whole`() {
        val long = "Built-in Audio Analog Stereo through the docking station's rear headphone jack, " +
            "by way of the monitor's own speakers and a Bluetooth adapter that names itself at length"
        dialog(Desk(speakers = listOf(AudioDevice("dock", long)))) {
            val layout = mutableListOf<TextLayoutResult>()
            onNodeWithText(long).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertTrue(!layout.single().hasVisualOverflow && layout.single().lineCount > 1, "wrapped, not cut")
        }
    }

    @Test
    fun `devices are offered where there is any choice, and nowhere else`() {
        assertTrue(hasDeviceChoices(Desk(), VideoDevices.Noop, null))
        assertTrue(hasDeviceChoices(AudioDevices.Noop, cameras) {}, "a camera alone is a choice")
        assertTrue(!hasDeviceChoices(AudioDevices.Noop, cameras, null), "not without a way to switch it")
        assertTrue(!hasDeviceChoices(AudioDevices.Noop, VideoDevices.Noop, null))
    }
}
