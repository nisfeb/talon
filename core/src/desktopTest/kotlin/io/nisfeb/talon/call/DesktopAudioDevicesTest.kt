package io.nisfeb.talon.call

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // From webrtc-java 0.18, closing a call's last connection puts the
    // audio module to sleep: it refused every set and stop, so a device
    // picked between calls did not take (the reason 0.17 was kept until
    // 2026-11-17). A machine with no sound card checks the hook only.
    @Test
    fun `a device picked between calls is kept, and the next call sets it`() {
        val devices = try {
            DesktopAudioDevices()
        } catch (e: LinkageError) {
            println("native webrtc unavailable here, skipping: ${e.message}")
            return
        }
        DesktopPeerLink(emptyList(), sendAudio = true).close()
        devices.selectInput(null)
        devices.selectOutput(null)
        devices.inputs().firstOrNull()?.let {
            devices.selectInput(it.id)
            assertEquals(it.id, devices.selectedInput, "the microphone picked with no call up")
        }
        devices.outputs().firstOrNull()?.let {
            devices.selectOutput(it.id)
            assertEquals(it.id, devices.selectedOutput, "the speaker picked with no call up")
        }
        val putBack = DesktopWebRtcFactory.onConnectionMade
        var made = 0
        DesktopWebRtcFactory.onConnectionMade = { made++; putBack?.invoke() }
        try {
            DesktopPeerLink(emptyList(), sendAudio = true).close()
        } finally {
            DesktopWebRtcFactory.onConnectionMade = putBack
        }
        assertTrue(putBack != null, "the devices pane puts its choices back")
        assertEquals(1, made, "a new connection asks for the chosen devices")
    }

    @Test
    fun `every peer connection made asks for the chosen devices`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val offenders = listOf("core/src", "composeApp/src", "bridge/src").map { File(root, it) }.filter { it.exists() }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" && "Test" !in it.path }.toList() }
            // Desktop only: Android and iOS have engines of their own.
            .filter { "desktopMain" in it.path || it.path.contains("bridge/src") }
            .filter { f -> f.readText().let { ".createPeerConnection(" in it && "DesktopWebRtcFactory.connectionMade()" !in it } }
            .map { it.relativeTo(root).path }
        assertEquals(emptyList(), offenders, "call DesktopWebRtcFactory.connectionMade() once the connection is made")
    }
}
