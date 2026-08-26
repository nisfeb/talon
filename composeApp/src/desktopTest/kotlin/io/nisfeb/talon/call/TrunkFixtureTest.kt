package io.nisfeb.talon.call

import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.createAppHttpClient
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * Not a test — a one-shot fixture for on-device testing. Creates a
 * group with a chat channel on the host ship, invites the other ship,
 * accepts from that side, and points the host at its SFU using an
 * address a phone can actually reach (localhost is useless off-box).
 *
 * Prints the channel to open in the app. Run once:
 *
 *   TRUNK_FIXTURE=1 TRUNK_SFU_KEY=<key> TRUNK_HOST_IP=192.168.9.197 \
 *     ./gradlew :composeApp:desktopTest --tests '*TrunkFixture*' --rerun-tasks
 */
class TrunkFixtureTest {

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
        val dir = createTempDirectory(prefix = "talon-fixture-").toFile()
        return Room.databaseBuilder<AppDatabase>(File(dir, "f.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Test
    fun setUpFixture() {
        if (System.getenv("TRUNK_FIXTURE") == null) {
            println("TRUNK_FIXTURE not set — skipping fixture setup")
            return
        }
        val aUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val aCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val bUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val bCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"
        val hostIp = System.getenv("TRUNK_HOST_IP") ?: "192.168.9.197"
        val sfuKey = System.getenv("TRUNK_SFU_KEY") ?: error("set TRUNK_SFU_KEY")

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
            delay(4_000)

            val flag = repoA.createGroup("Trunkline Test", "party line fixture")
            println("created group $flag")
            delay(6_000)

            repoA.inviteToGroup(flag, shipB)
            println("invited $shipB")
            delay(6_000)

            runCatching { repoB.acceptInvite(flag) }
                .onFailure { println("accept failed (may already be a member): $it") }
            delay(6_000)

            // Find the chat channel the group was created with.
            val group = sessionA.openChannel().scry("groups", "/v2/groups/$flag") as? JsonObject
            val channels = (group?.get("channels") as? JsonObject)?.keys.orEmpty()
            println("channels: $channels")

            // Point the host at its SFU on an address the phone can reach.
            val setup = sessionA.openChannel()
            setup.poke(
                TrunkWire.AGENT, TrunkWire.ACTION_MARK,
                TrunkWire.setSfuAction("http://$hostIp:8444", "talon", sfuKey),
            )
            println("sfu set to http://$hostIp:8444")

            println()
            println("=== FIXTURE READY ===")
            println("host ship : $shipA  ($aUrl)")
            println("guest ship: $shipB  ($bUrl)")
            println("group     : $flag")
            channels.forEach { println("channel   : $it") }
            println("Open that channel in Talon on both ships; the party-line")
            println("icon appears in its header.")

            runCatching { dbA.close() }
            runCatching { dbB.close() }
        }
    }
}
