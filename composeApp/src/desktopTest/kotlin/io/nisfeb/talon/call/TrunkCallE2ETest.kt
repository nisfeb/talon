package io.nisfeb.talon.call

import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The v0 spike's payoff: a real 1:1 call between two live fake ships,
 * fully headless. Two CallControllers — one logged into each ship —
 * with real webrtc-java engines; callee auto-answers; asserts both
 * sides reach Live media over Tier 0 (loopback host candidates).
 *
 * Opt-in: needs the ships from docs/trunkline.md running, so it only
 * runs with TRUNK_E2E=1 (ports/codes overridable via TRUNK_A_URL etc).
 * Run:
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*TrunkCallE2E*'
 */
class TrunkCallE2ETest {

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
    fun callBetweenTwoShips() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping live two-ship call test")
            return
        }
        val aUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val aCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val bUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val bCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        runBlocking {
            val sessionA = UrbitSession(createAppHttpClient(), MemStore())
            val sessionB = UrbitSession(createAppHttpClient(), MemStore())
            val shipA = sessionA.login(aUrl, aCode).getOrThrow()
            val shipB = sessionB.login(bUrl, bCode).getOrThrow()
            println("logged in: $shipA + $shipB")

            val caller = CallController(sessionA, DesktopCallEngineProvider)
            val callee = CallController(sessionB, DesktopCallEngineProvider)
            caller.start()
            callee.start()
            // Let both /calls subscriptions establish before ringing.
            kotlinx.coroutines.delay(3_000)

            val t0 = System.currentTimeMillis()
            caller.placeCall(shipB)

            // Callee: wait for the ring, answer it.
            withTimeout(30_000) {
                callee.state.first { it is CallUiState.Incoming }
            }
            println("metric ring→incoming: ${System.currentTimeMillis() - t0}ms")
            // The offer poke trails the ring (gathering overlaps); give it
            // a beat to land before answering, mirroring a human's delay.
            kotlinx.coroutines.delay(2_000)
            callee.accept()

            // Both sides live.
            withTimeout(60_000) {
                caller.state.first { it is CallUiState.Active && (it as CallUiState.Active).media == MediaState.Live }
            }
            withTimeout(60_000) {
                callee.state.first { it is CallUiState.Active && (it as CallUiState.Active).media == MediaState.Live }
            }
            println("metric place→both-live: ${System.currentTimeMillis() - t0}ms")

            // Hang up from the caller; callee sees it end.
            caller.hangup()
            withTimeout(30_000) {
                callee.state.first { it is CallUiState.Ended }
            }
            assertEquals("ended", (callee.state.value as CallUiState.Ended).reason)

            caller.stop()
            callee.stop()
        }
    }
}
