package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
 * Choosing the microphone, speaker and camera for a call: folded away
 * until asked, "System default" first and always there, one list where
 * the phone routes both ways at once, and nothing at all where the
 * platform offers no choice.
 */
@OptIn(ExperimentalTestApi::class)
class AudioDeviceControlsTest {
    private val did = CopyOnWriteArrayList<String>()

    private inner class Desk(override val unifiedRoute: Boolean = false, private val mics: List<AudioDevice> = listOf(AudioDevice("usb", "USB mic"))) : AudioDevices {
        override val supported = true
        override fun inputs() = mics
        override fun outputs() = listOf(AudioDevice("hp", "Headphones"))
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

    private fun controls(devices: AudioDevices, camera: Boolean = false, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                AudioDeviceControls(devices, videoDevices = cameras, onSelectCamera = if (camera) ({ did += "switch:$it" }) else null)
            }
        }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the devices open on asking, and a pick goes to the platform`() = controls(Desk()) {
        assertTrue(shows("Microphone and speaker") && !shows("USB mic"), "folded away until asked")
        onNodeWithContentDescription("Choose audio devices").performClick()
        waitForIdle()
        assertTrue(shows("Microphone") && shows("Speaker"))
        assertEquals(2, onAllNodesWithContentDescription("Selected").fetchSemanticsNodes().size, "the system's mic, and the headphones")
        onNodeWithText("USB mic").performClick()
        onAllNodesWithText("System default")[1].performClick()
        waitForIdle()
        assertEquals(listOf("in:usb", "out:null"), did.toList(), "the default stays reachable")
        onNodeWithContentDescription("Hide audio devices").performClick()
        waitForIdle()
        assertTrue(!shows("USB mic"))
    }

    @Test
    fun `where one choice routes both ways there is one list`() = controls(Desk(unifiedRoute = true)) {
        assertTrue(shows("Audio device"))
        onNodeWithContentDescription("Choose audio devices").performClick()
        waitForIdle()
        assertTrue(shows("Route") && !shows("Speaker"))
        onNodeWithText("USB mic").performClick()
        waitForIdle()
        assertEquals(listOf("in:usb"), did.toList())
    }

    @Test
    fun `a camera is a third list, and none found says so`() = controls(Desk(mics = emptyList()), camera = true) {
        assertTrue(shows("Microphone, speaker and camera"))
        onNodeWithContentDescription("Choose audio devices").performClick()
        waitForIdle()
        assertTrue(shows("No devices found."), "no microphone")
        onNodeWithText("Webcam").performClick()
        onAllNodesWithText("System default").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForIdle()
        assertEquals(listOf("switch:cam", "camera:null"), did.toList(), "a live camera is switched; the default only remembered")
    }

    @Test
    fun `where the platform offers no choice there is nothing`() = controls(AudioDevices.Noop) {
        assertTrue(!shows("Microphone and speaker") && !shows("Audio device"))
    }
}
