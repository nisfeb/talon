package io.nisfeb.talon.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLeavesTest {
    // Checked only at launch, a Talon left running grew the log without bound.
    @Test fun `the log rotates while running and keeps one backup`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "talon-log-${System.nanoTime()}").apply { mkdirs() }
        try {
            val f = File(dir, "talon.log")
            val log = RotatingLog(f, limit = 1_000, every = 10)
            repeat(200) { log.write("line $it " + "x".repeat(40), null) }
            assertTrue(File(dir, "talon.log.1").isFile)
            assertTrue(f.length() <= 1_000 + 10 * 60, "the file stays near its limit: ${f.length()}")
            assertTrue(f.readText().endsWith("line 199 " + "x".repeat(40) + "\n"))
            assertEquals(setOf("talon.log", "talon.log.1"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a small log is not rotated`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "talon-log-${System.nanoTime()}").apply { mkdirs() }
        try {
            val log = RotatingLog(File(dir, "talon.log"), limit = 1_000_000, every = 1)
            repeat(20) { log.write("line $it", RuntimeException("boom $it").takeIf { _ -> it == 5 }) }
            assertFalse(File(dir, "talon.log.1").exists())
            val text = File(dir, "talon.log").readText()
            assertTrue(text.startsWith("line 0\n") && "boom 5" in text && text.endsWith("line 19\n"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // Docker's bridges and veths came and went with containers, and each
    // change dropped a party line.
    @Test fun `container and vm bridges are not a network change and a vpn is`() {
        for (n in listOf("docker0", "veth1a2b3c", "br-5f2e", "virbr0", "vboxnet0", "vmnet8", "lxcbr0", "podman0", "cni0", "flannel.1")) {
            assertFalse(DesktopNetworkWatcher.carriesCalls(n), n)
        }
        for (n in listOf("eth0", "enp3s0", "wlan0", "wlp2s0", "tun0", "wg0", "en0", "utun3", "ppp0", "usb0")) {
            assertTrue(DesktopNetworkWatcher.carriesCalls(n), n)
        }
    }
}
