package io.nisfeb.talon.comet

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots a real comet on this machine: downloads vere, mines an
 * identity, waits for the dojo, reads +code, logs in over HTTP, exits.
 * Network-bound and minutes long, so it only runs when asked:
 *
 *   TALON_COMET_E2E=1 ./gradlew :core:desktopTest --tests '*LocalShipE2ETest*'
 *
 * The pier lands in a temp directory and is deleted afterwards; the
 * comet it mined is simply abandoned.
 */
class LocalShipE2ETest {
    @Test
    fun `a comet boots, answers +code, and accepts that code at login`() {
        if (System.getenv("TALON_COMET_E2E") != "1") return
        val dir = File(System.getProperty("java.io.tmpdir"), "talon-comet-e2e-${System.nanoTime()}")
        val http = HttpClient(OkHttp) { followRedirects = true }
        val ship = DesktopLocalShip(http, dir)
        try {
            runBlocking {
                val ready = ship.setup()
                assertTrue(ready.ship.startsWith("~"), "ship name: ${ready.ship}")
                assertTrue(Regex("[a-z]{6}-[a-z]{6}-[a-z]{6}-[a-z]{6}").matches(ready.code), "code shape")
                assertTrue(ready.url.startsWith("http://127.0.0.1:"), ready.url)
                val resp = http.submitForm(
                    url = "${ready.url}/~/login",
                    formParameters = parameters { append("password", ready.code) },
                )
                assertEquals(200, resp.status.value, "login with the comet's code")
                // Settings: the dojo panel types into the same terminal.
                ship.send("+code")
                val echoed = withTimeoutOrNull(20_000) {
                    ship.terminal.first { it.contains(ready.code) }
                }
                assertTrue(echoed != null, "the dojo answered +code on the terminal flow")
                assertEquals(ready.ship, ship.describe()?.ship, "describe() knows the ship")
                assertEquals(ready.code, ship.keptCode(), "the code is kept for later starts")
                // 4.6 is the newest release at the time of writing; a
                // newer one just means this returns an update.
                runCatching { ship.checkRuntimeUpdate() }
                    .onFailure { throw AssertionError("release check failed: ${it.message}") }
                ship.stop()
                assertEquals(LocalShipState.Stopped, ship.state.value)
                // Second start is the everyday path: same ports, no mining.
                val again = ship.start()
                assertEquals(ready.url, again.url, "ports are stable across starts")
                ship.stop()
            }
        } catch (t: Throwable) {
            // The terminal is the only witness; put its tail in the report.
            val log = File(dir, "terminal.log")
            if (log.isFile) {
                val tail = log.readText().takeLast(4000)
                System.err.println("── terminal.log tail ──\n" + CometTerminal.clean(tail))
            }
            throw t
        } finally {
            runBlocking { runCatching { ship.stop() } }
            http.close()
            dir.deleteRecursively()
        }
    }
}
