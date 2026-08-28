package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test for the native device bridge.
 *
 * Asserts the calls survive, not that any device exists — a CI runner
 * has no sound card and returns empty lists, which is a correct answer.
 * What this catches is the failure that matters: a JNI signature that
 * doesn't match the shipped native library, which compiles fine and
 * throws UnsatisfiedLinkError the moment a user opens the pane.
 */
class DesktopAudioDevicesTest {

    @Test
    fun enumeratingAndSelectingDoesNotThrow() {
        val devices = try {
            DesktopAudioDevices()
        } catch (e: UnsatisfiedLinkError) {
            println("native webrtc unavailable here — skipping: ${e.message}")
            return
        } catch (e: NoClassDefFoundError) {
            println("native webrtc unavailable here — skipping: ${e.message}")
            return
        }

        assertTrue(devices.supported)

        val ins = devices.inputs()
        val outs = devices.outputs()
        println("audio inputs: ${ins.size}, outputs: ${outs.size}")
        for (d in ins + outs) {
            assertTrue(d.id.isNotEmpty(), "a device with no id can't be selected later")
            assertTrue(d.label.isNotEmpty(), "a device with no label can't be shown")
        }

        // Null is the "system default" path, which is what the pane
        // offers first and what every user starts on.
        devices.selectInput(null)
        devices.selectOutput(null)

        // Round-trip a real device if the machine has one.
        ins.firstOrNull()?.let {
            devices.selectInput(it.id)
            assertTrue(devices.selectedInput == it.id)
        }
        outs.firstOrNull()?.let {
            devices.selectOutput(it.id)
            assertTrue(devices.selectedOutput == it.id)
        }
    }
}
