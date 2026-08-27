package io.nisfeb.talon.call

import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A ship without %trunk must be offered the desk, not left with a dead
 * call button — and accepting must actually install it.
 *
 * Destructive, so it is opt-in beyond TRUNK_E2E: it uninstalls %trunk
 * from ship B and reinstalls it from the publisher. Point TRUNK_B_URL
 * at a ship you don't mind rebuilding.
 *
 *   TRUNK_E2E=1 TRUNK_INSTALL=1 TRUNK_PUBLISHER=~nec \
 *     ./gradlew :composeApp:desktopTest --tests '*TrunkInstallE2E*'
 */
class TrunkInstallE2ETest {

    private class MemStore : SessionStore {
        private var s: SavedSession? = null
        private var active: String? = null
        override fun all() = listOfNotNull(s)
        override fun active() = s
        override fun activeShip() = active
        override fun save(entry: SavedSession, makeActive: Boolean) {
            s = entry
            if (makeActive) active = entry.ship
        }
        override fun setActive(ship: String) { active = ship }
        override fun remove(ship: String) { s = null; active = null }
        override fun clearAll() { s = null; active = null }
    }

    @Test
    fun aShipWithoutTrunkIsOfferedItAndCanInstall() {
        if (System.getenv("TRUNK_E2E") == null || System.getenv("TRUNK_INSTALL") == null) {
            println("TRUNK_E2E / TRUNK_INSTALL not both set — skipping install test")
            return
        }
        val url = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val code = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        runBlocking {
            val session = UrbitSession(createAppHttpClient(), MemStore())
            session.login(url, code).getOrThrow()
            val ctl = CallController(session, DesktopCallEngineProvider)
            ctl.start()
            delay(5_000)

            // Precondition: this test only means anything if the desk is
            // genuinely absent. The harness uninstalls it beforehand.
            assertTrue(
                ctl.policy.value == null,
                "%trunk is still installed — uninstall it before running this",
            )

            // Tapping call must not silently do nothing.
            ctl.placeCall("~zod")
            withTimeout(10_000) { ctl.install.first { it is TrunkInstall.Offered } }
            println("offered the install")

            // And it must not have placed a call.
            assertTrue(
                ctl.state.value is CallUiState.None,
                "placed a call without the desk: ${ctl.state.value}",
            )

            // Fakes can't reach the real publisher, so install from
            // whichever ship this harness is using as one.
            ctl.installTrunk(System.getenv("TRUNK_PUBLISHER") ?: "~nec")
            withTimeout(15_000) { ctl.install.first { it is TrunkInstall.Installing } }
            println("installing…")

            val done = withTimeout(150_000) {
                ctl.install.first { it is TrunkInstall.Hidden || it is TrunkInstall.Failed }
            }
            assertTrue(done is TrunkInstall.Hidden, "install failed: $done")
            assertTrue(ctl.policy.value != null, "installed but the policy still won't read")
            println("installed; policy = ${ctl.policy.value}")

            ctl.stop()
        }
    }
}
