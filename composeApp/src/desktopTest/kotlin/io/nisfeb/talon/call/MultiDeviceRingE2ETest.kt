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
 * One ship, two devices — a phone and a desktop, which is the normal
 * shape of an Urbit identity.
 *
 * Both devices receive every ring; that is the point. What must not
 * happen is one of them answering *for the whole ship*: a device that
 * is busy used to reply "busy" immediately, cancelling a call the
 * other device was visibly ringing for. It reproduced trivially here
 * because a test client and a real phone were logged into the same
 * ship at once.
 *
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*MultiDeviceRing*'
 */
class MultiDeviceRingE2ETest {

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

    private suspend fun device(url: String, code: String): Pair<UrbitSession, String> {
        val session = UrbitSession(createAppHttpClient(), MemStore())
        val ship = session.login(url, code).getOrThrow()
        return session to ship
    }

    @Test
    fun aBusyDeviceDoesNotCancelTheRingForItsOtherDevices() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping multi-device ring test")
            return
        }
        val aUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val aCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val bUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val bCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        runBlocking {
            val (callerSession, shipA) = device(aUrl, aCode)
            // Two independent clients of the SAME ship.
            val (phoneSession, shipB) = device(bUrl, bCode)
            val (deskSession, _) = device(bUrl, bCode)

            val caller = CallController(callerSession, DesktopCallEngineProvider)
            val phone = CallController(phoneSession, DesktopCallEngineProvider)
            val desktop = CallController(deskSession, DesktopCallEngineProvider)
            caller.start(); phone.start(); desktop.start()
            delay(4_000)

            // Wedge one of the two devices: it is mid-call of its own.
            phone.placeCall(shipA)
            withTimeout(20_000) { phone.state.first { it !is CallUiState.None } }
            println("phone is busy: ${phone.state.value}")

            // Someone calls the ship. The busy device must not answer
            // for the free one.
            caller.placeCall(shipB)
            withTimeout(25_000) {
                desktop.state.first { it is CallUiState.Incoming }
            }
            println("the other device rings anyway")

            // And the caller must not have been told "busy".
            delay(3_000)
            val callerState = caller.state.value
            assertTrue(
                callerState is CallUiState.Outgoing,
                "a busy device cancelled the call: $callerState",
            )

            caller.hangup()
            phone.hangup()
            caller.stop(); phone.stop(); desktop.stop()
        }
    }
}
