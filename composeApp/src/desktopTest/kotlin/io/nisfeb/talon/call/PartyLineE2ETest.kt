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
 * A real party line: two ships, one host-minted ticket each, both
 * clients on a live Galène SFU, both hearing the same roster.
 *
 * Needs the fixture from docs/trunkline.md — two fake ships plus a
 * Galène with a `talon` group (authKeys + auto-subgroups) — so it is
 * opt-in via TRUNK_E2E=1, like the 1:1 test:
 *
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*PartyLineE2E*'
 */
class PartyLineE2ETest {

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
    fun twoShipsOnOneLine() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping live party-line test")
            return
        }
        val aUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val aCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val bUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val bCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"
        val sfuBase = System.getenv("TRUNK_SFU") ?: "http://localhost:8444"
        val sfuKey = System.getenv("TRUNK_SFU_KEY") ?: error("set TRUNK_SFU_KEY")
        val roomName = "e2e-line"

        runBlocking {
            val httpA = createAppHttpClient()
            val httpB = createAppHttpClient()
            val sessionA = UrbitSession(httpA, MemStore())
            val sessionB = UrbitSession(httpB, MemStore())
            val shipA = sessionA.login(aUrl, aCode).getOrThrow()
            val shipB = sessionB.login(bUrl, bCode).getOrThrow()

            // Host side: point the ship at its sidecar and open a room
            // with the other ship as the sole member.
            val setup = sessionA.openChannel()
            setup.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.setSfuAction(sfuBase, "talon", sfuKey),
            )
            setup.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.openRoomAction(roomName, "E2E Line", listOf(shipB)),
            )

            val partyA = PartyLine(httpA, DesktopPeerLinkFactory)
            val partyB = PartyLine(httpB, DesktopPeerLinkFactory)
            val ctlA = CallController(sessionA, DesktopCallEngineProvider)
            val ctlB = CallController(sessionB, DesktopCallEngineProvider)
            ctlA.onTicket = { partyA.join(it, shipA) }
            ctlB.onTicket = { partyB.join(it, shipB) }
            ctlA.start()
            ctlB.start()
            delay(3_000) // let both /calls subscriptions land

            val t0 = System.currentTimeMillis()
            ctlA.joinRoom(shipA, roomName) // host joins its own room
            withTimeout(30_000) {
                partyA.state.first { it is PartyState.Live }
            }
            println("metric host join->live: ${System.currentTimeMillis() - t0}ms")

            ctlB.joinRoom(shipA, roomName) // member asks the host over ames
            withTimeout(30_000) {
                partyB.state.first { it is PartyState.Live }
            }

            // Both sides' upstream audio reaches the SFU...
            withTimeout(45_000) {
                partyA.state.first { it is PartyState.Live && it.media == MediaState.Live }
            }
            withTimeout(45_000) {
                partyB.state.first { it is PartyState.Live && it.media == MediaState.Live }
            }
            println("metric both-publishing: ${System.currentTimeMillis() - t0}ms")

            // ...and each sees the other on the line. Assert membership
            // rather than an exact count: the SFU can still be holding a
            // dead socket from an earlier run, and a ghost row says
            // nothing about whether these two can hear each other.
            val both = listOf(shipA, shipB)
            withTimeout(30_000) {
                partyA.state.first {
                    it is PartyState.Live && it.members.map { m -> m.ship }.containsAll(both)
                }
            }
            val seen = (partyA.state.value as PartyState.Live).members.map { it.ship }.sorted()
            assertTrue(seen.containsAll(both.sorted()), "host roster missing someone: $seen")
            assertTrue(
                (partyB.state.value as PartyState.Live).members
                    .map { it.ship }.containsAll(both),
            )
            println("roster: $seen")

            partyB.leave()
            withTimeout(20_000) {
                partyA.state.first {
                    it is PartyState.Live && it.members.none { m -> m.ship == shipB }
                }
            }
            println("roster after leave: ok")

            partyA.leave()
            ctlA.stop()
            ctlB.stop()
        }
    }
}
