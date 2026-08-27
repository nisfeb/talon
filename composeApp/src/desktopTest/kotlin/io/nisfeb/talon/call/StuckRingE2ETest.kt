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
 * The "busy forever" bug, pinned.
 *
 * A caller that disappears mid-ring — app killed, crash, network drop —
 * used to leave the callee ringing internally with nothing to clear it,
 * and a device that thinks it is ringing answers "busy" to every call
 * afterwards. One interrupted attempt poisoned the device until relaunch.
 *
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*StuckRingE2E*'
 */
class StuckRingE2ETest {

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
    fun anAbandonedRingDoesNotLeaveTheCalleeBusy() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping stuck-ring test")
            return
        }
        val aUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val aCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val bUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val bCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"
        val ring = 4_000L

        runBlocking {
            val httpA = createAppHttpClient()
            val httpB = createAppHttpClient()
            val sessionA = UrbitSession(httpA, MemStore())
            val sessionB = UrbitSession(httpB, MemStore())
            val shipA = sessionA.login(aUrl, aCode).getOrThrow()
            val shipB = sessionB.login(bUrl, bCode).getOrThrow()

            // The callee stays up for the whole test — it's the device
            // that used to get poisoned.
            val callee = CallController(sessionB, DesktopCallEngineProvider, ringTimeoutMs = ring)
            callee.start()

            val doomed = CallController(sessionA, DesktopCallEngineProvider, ringTimeoutMs = ring)
            doomed.start()
            delay(3_000)

            doomed.placeCall(shipB)
            withTimeout(20_000) { callee.state.first { it is CallUiState.Incoming } }
            println("callee is ringing")

            // The caller vanishes: no hangup, no decline, nothing. stop()
            // kills its loop the way a process death would.
            doomed.stop()

            // The ring must expire on its own.
            withTimeout(ring + 15_000) {
                callee.state.first { it is CallUiState.None || it is CallUiState.Ended }
            }
            withTimeout(20_000) { callee.state.first { it is CallUiState.None } }
            println("callee freed itself after the abandoned ring")

            // And a fresh call must now connect rather than bounce.
            val second = CallController(sessionA, DesktopCallEngineProvider, ringTimeoutMs = ring)
            second.start()
            delay(3_000)
            second.placeCall(shipB)
            withTimeout(20_000) { callee.state.first { it is CallUiState.Incoming } }

            val calleeState = callee.state.value
            assertTrue(
                calleeState is CallUiState.Incoming,
                "second call should ring, not bounce: $calleeState",
            )
            // The caller must not have been told "busy".
            val callerState = second.state.value
            assertTrue(
                callerState !is CallUiState.Ended,
                "caller was rejected: $callerState",
            )
            println("second call rings normally")

            second.hangup()
            second.stop()

            // A call answered but never connected must also free the
            // device: only live media may hold it indefinitely.
            val stalling = CallController(
                sessionA, DesktopCallEngineProvider,
                ringTimeoutMs = ring, connectTimeoutMs = ring,
            )
            stalling.start()
            delay(3_000)
            stalling.placeCall(shipB)
            withTimeout(20_000) { callee.state.first { it is CallUiState.Incoming } }
            callee.accept()
            withTimeout(20_000) { callee.state.first { it is CallUiState.Active } }
            // Drop the caller mid-negotiation so media can never come up.
            stalling.stop()
            withTimeout(ring + 30_000) { callee.state.first { it is CallUiState.None } }
            println("callee freed itself after a call that never connected")

            callee.stop()
        }
    }
}
