package io.nisfeb.talon.call

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The path the UI actually takes, against a real group: the host taps
 * the party-line icon (startLine → group roster → open-room → join),
 * a member taps it (joinLine), and both end up on the line.
 *
 * [PartyLineE2ETest] drives the agent directly; this one proves the
 * group-derived plumbing in between. Needs the fixture from
 * TrunkFixtureTest, so it takes the channel as an env var:
 *
 *   TRUNK_E2E=1 TRUNK_CHANNEL=chat/~nec/v2mk84do \
 *     ./gradlew :composeApp:desktopTest --tests '*PartyLineUiPath*'
 */
class PartyLineUiPathTest {

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

    private fun tempDb(): AppDatabase {
        val dir = createTempDirectory(prefix = "talon-uipath-").toFile()
        return Room.databaseBuilder<AppDatabase>(File(dir, "u.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Test
    fun hostStartsAndMemberJoins() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping UI-path test")
            return
        }
        val whom = System.getenv("TRUNK_CHANNEL") ?: run {
            println("TRUNK_CHANNEL not set — run TrunkFixtureTest first")
            return
        }
        val aUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val aCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val bUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val bCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        runBlocking {
            val httpA = createAppHttpClient()
            val httpB = createAppHttpClient()
            val sessionA = UrbitSession(httpA, MemStore())
            val sessionB = UrbitSession(httpB, MemStore())
            val shipA = sessionA.login(aUrl, aCode).getOrThrow()
            val shipB = sessionB.login(bUrl, bCode).getOrThrow()
            val dbA = tempDb()
            val dbB = tempDb()
            val repoA = TlonChatRepo(dbA)
            val repoB = TlonChatRepo(dbB)
            repoA.start(sessionA)
            repoB.start(sessionB)
            // Let the bootstrap populate channel→group, which startLine
            // reads to find the room's guest list.
            delay(10_000)

            val partyA = PartyLine(httpA, DesktopPeerLinkFactory)
            val partyB = PartyLine(httpB, DesktopPeerLinkFactory)
            val ctlA = CallController(sessionA, DesktopCallEngineProvider)
            val ctlB = CallController(sessionB, DesktopCallEngineProvider)
            ctlA.onTicket = { partyA.join(it, shipA) }
            ctlB.onTicket = { partyB.join(it, shipB) }
            ctlA.start()
            ctlB.start()
            delay(3_000)

            // Host taps the icon.
            val started = PartyLineHost.startLine(ctlA, repoA, dbA, whom, "Test line")
            assertTrue(started, "startLine should accept a group channel")
            withTimeout(30_000) {
                partyA.state.first { it is PartyState.Live && it.media == MediaState.Live }
            }
            println("host is on the line")

            // Member taps the icon.
            assertTrue(PartyLineHost.joinLine(ctlB, whom))
            withTimeout(30_000) {
                partyB.state.first { it is PartyState.Live && it.media == MediaState.Live }
            }
            withTimeout(20_000) {
                partyA.state.first { it is PartyState.Live && it.members.size == 2 }
            }
            println("roster: ${(partyA.state.value as PartyState.Live).members.map { it.ship }}")

            partyA.leave()
            partyB.leave()
            ctlA.stop()
            ctlB.stop()
            runCatching { dbA.close() }
            runCatching { dbB.close() }
        }
    }
}
