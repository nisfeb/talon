package io.nisfeb.talon.comet

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Leaving Settings while the comet starts. The start ran on the screen's
 * scope: leaving cancelled it, the boot's catch destroyed the comet, and
 * Start stayed greyed out. A shell script stands in for vere here; it
 * never prints a prompt, so nothing is asked over HTTP. Real time.
 */
class LocalShipLeaveTest {
    @Test
    fun `leaving while the ship starts does not stop it and its end un-greys start`() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        val dir = File(System.getProperty("java.io.tmpdir"), "talon-leave-${System.nanoTime()}")
        File(dir, "pier/.urb").mkdirs()
        val asset = VereRelease.assetFor(System.getProperty("os.name"), System.getProperty("os.arch"))!!
        val bin = File(dir, VereRelease.binaryName(asset))
        // Past the 1 MB a real runtime is checked for; the padding is never read.
        bin.writeText(
            "#!/bin/sh\necho \$\$ > \"\$(dirname \"\$0\")/pid\"\necho 'booting'\n" +
                "while [ ! -f \"\$(dirname \"\$0\")/exit-now\" ]; do sleep 0.2; done\nexit 1\n#" + "x".repeat(1_100_000) + "\n",
        )
        bin.setExecutable(true)
        val ship = DesktopLocalShip(HttpClient(MockEngine { error("no HTTP in this test") }), dir)
        try {
            runBlocking {
                val screen = launch(Dispatchers.Default) { runCatching { ship.start() } }
                waitFor { ship.state.value is LocalShipState.Booting && File(dir, "pid").isFile }
                screen.cancelAndJoin()
                delay(1_500)
                val pid = File(dir, "pid").readText().trim().toLong()
                assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the comet still runs")
                assertIs<LocalShipState.Booting>(ship.state.value)
                // The terminal records the +code answer: the owner's alone.
                val log = File(dir, "terminal.log").toPath()
                assertEquals("rw-------", PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(log)))
                File(dir, "exit-now").createNewFile()
                waitFor { ship.state.value is LocalShipState.Failed }
                assertTrue((ship.state.value as LocalShipState.Failed).why.startsWith("The ship stopped during boot."))
            }
        } finally {
            File(dir, "exit-now").createNewFile()
            dir.deleteRecursively()
        }
    }

    private suspend fun waitFor(probe: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!probe()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            delay(100)
        }
    }
}
