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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who may ring us, enforced by the agent rather than the client.
 *
 * The check that matters is negative: a refused caller must produce no
 * ring at all on the callee, on any of its devices. Refusal is silent
 * on purpose — a rejection would confirm to a stranger that the ship
 * is live and filtering — so the caller just sits in Outgoing until
 * its own watchdog gives up, exactly as if nobody were home.
 *
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*CallPolicyE2E*'
 */
class CallPolicyE2ETest {

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

    /** No ring may reach [callee] within [ms]. */
    private suspend fun assertNoRing(callee: CallController, ms: Long, why: String) {
        delay(ms)
        val got = callee.state.value
        assertTrue(got is CallUiState.None, "$why — callee rang anyway: $got")
    }

    @Test
    fun policyDecidesWhoMayRing() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping call policy test")
            return
        }
        val aUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val aCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val bUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val bCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        runBlocking {
            // B calls A, not the other way round: every ring in this
            // test lands on A, so pointing A at a ship nobody is using
            // keeps the noise off a real device.
            val callerSession = UrbitSession(createAppHttpClient(), MemStore())
            val calleeSession = UrbitSession(createAppHttpClient(), MemStore())
            val shipA = calleeSession.login(aUrl, aCode).getOrThrow()
            val shipB = callerSession.login(bUrl, bCode).getOrThrow()

            val caller = CallController(callerSession, DesktopCallEngineProvider, ringTimeoutMs = 6_000L)
            val callee = CallController(calleeSession, DesktopCallEngineProvider, ringTimeoutMs = 6_000L)
            caller.start(); callee.start()
            delay(4_000)

            // Leave the callee's ship as we found it, whatever happens.
            try {
                // Baseline: open policy rings.
                callee.setCallMode(CallPolicy.Mode.Open)
                withTimeout(15_000) { callee.policy.first { it.mode == CallPolicy.Mode.Open } }
                caller.placeCall(shipA)
                withTimeout(20_000) { callee.state.first { it is CallUiState.Incoming } }
                callee.reject()
                withTimeout(20_000) { callee.state.first { it is CallUiState.None } }
                println("open: rings")

                // Blocked, still in open mode: no ring.
                callee.setCallMode(CallPolicy.Mode.Open)
                callee.setBlocked(shipB, true)
                withTimeout(15_000) { callee.policy.first { shipB in it.block } }
                caller.placeCall(shipA)
                assertNoRing(callee, 8_000, "a blocked ship must not ring us")
                caller.hangup()
                println("blocked: silent")

                callee.setBlocked(shipB, false)
                withTimeout(15_000) { callee.policy.first { shipB !in it.block } }

                // Allow-list mode, caller not on it: no ring.
                callee.setCallMode(CallPolicy.Mode.Allow)
                withTimeout(15_000) { callee.policy.first { it.mode == CallPolicy.Mode.Allow } }
                caller.placeCall(shipA)
                assertNoRing(callee, 8_000, "allow mode must refuse a ship not on the list")
                caller.hangup()
                println("allow mode, not listed: silent")

                // Same mode, now on the list: rings.
                callee.setAllowed(shipB, true)
                withTimeout(15_000) { callee.policy.first { shipB in it.allow } }
                caller.placeCall(shipA)
                withTimeout(20_000) { callee.state.first { it is CallUiState.Incoming } }
                println("allow mode, listed: rings")
                callee.reject()

                // Blocking must outrank an explicit allow entry.
                callee.setBlocked(shipB, true)
                val pol = withTimeout(15_000) { callee.policy.first { shipB in it.block } }
                assertEquals(
                    emptySet(), pol.allow intersect setOf(shipB),
                    "blocking a ship must drop it from the allow list",
                )
                caller.placeCall(shipA)
                assertNoRing(callee, 8_000, "a block must outrank an allow entry")
                caller.hangup()
                println("blocked while allowed: silent")
            } finally {
                callee.setBlocked(shipB, false)
                callee.setAllowed(shipB, false)
                callee.setCallMode(CallPolicy.Mode.Open)
                delay(2_000)
                caller.stop(); callee.stop()
            }
        }
    }
}
