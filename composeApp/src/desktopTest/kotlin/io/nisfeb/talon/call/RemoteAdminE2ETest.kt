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
 * The whole group-admin flow against a line hosted by ANOTHER ship.
 *
 * This is the configuration every reported bug lived in, and the one
 * no earlier test covered: every other E2E has a single ship hosting
 * its own room, where the local branch of each action runs and the
 * remote path is never exercised. An admin of someone else's group
 * goes through relays, an admin check on the host, and announcements
 * coming back — none of which the local path touches.
 *
 * It asserts on the ADMIN's own view after each step, because that is
 * what the switch reads. A change that lands on the host but never
 * reaches the admin's client is exactly a switch that "does nothing".
 *
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*RemoteAdminE2E*'
 */
class RemoteAdminE2ETest {

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

    /** What the admin's own UI would show for this line. */
    private fun view(ctl: CallController, key: String): PartyRoom? =
        ctl.rooms.value[key] ?: ctl.invites.value[key]?.let {
            PartyRoom(it.name, it.title, it.listen, it.sfuBase, it.sfuBase.isNotEmpty())
        }

    @Test
    fun anAdminCanRunALineHostedByAnotherShip() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping remote admin test")
            return
        }
        val hostUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val hostCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val adminUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val adminCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        runBlocking {
            val hostSession = UrbitSession(createAppHttpClient(), MemStore())
            val adminSession = UrbitSession(createAppHttpClient(), MemStore())
            val host = hostSession.login(hostUrl, hostCode).getOrThrow()
            val admin = adminSession.login(adminUrl, adminCode).getOrThrow()

            val hostCtl = CallController(hostSession, DesktopCallEngineProvider)
            val adminCtl = CallController(adminSession, DesktopCallEngineProvider)
            hostCtl.start(); adminCtl.start()
            delay(4_000)

            val room = "remote-admin-${System.currentTimeMillis()}"
            val key = "$host/$room"

            // 1. Turn the line on. The host has never hosted it, so
            //    this creates it on a ship that isn't ours.
            adminCtl.configureRoom(
                host, room, open = true, listen = false,
                title = "Remote Admin",
                members = listOf(host, admin),
                admins = listOf(admin),
            )
            withTimeout(25_000) { adminCtl.invites.first { key in it } }
            println("1. line created on the host, visible to the admin")

            // 2. Turn listening on and see it in our OWN view — the
            //    switch reads this, not the host's state.
            adminCtl.configureRoom(host, room, open = true, listen = true)
            withTimeout(25_000) {
                adminCtl.invites.first { it[key]?.listen == true }
            }
            println("2. listening on, reflected back to the admin")

            // 3. Mint a link. Only the host holds the key.
            adminCtl.clearListenLink()
            adminCtl.shareRoom(host, room)
            val first = withTimeout(25_000) { adminCtl.listenLink.first { it != null } }!!
            assertTrue("/listen/?group=" in first.url, "not a listen page: ${first.url}")
            println("3. link minted by the host")

            // 4. Mint a SECOND one. A link expires, so "create another"
            //    has to work — not just the first press.
            adminCtl.clearListenLink()
            adminCtl.shareRoom(host, room)
            val second = withTimeout(25_000) { adminCtl.listenLink.first { it != null } }!!
            assertTrue("/listen/?group=" in second.url, "second link malformed: ${second.url}")
            println("4. a second link can be minted")

            // 5. Turn listening back off, and see THAT too.
            adminCtl.configureRoom(host, room, open = true, listen = false)
            withTimeout(25_000) {
                adminCtl.invites.first { it[key]?.listen == false }
            }
            println("5. listening off, reflected back to the admin")

            // 6. And sharing must now be refused.
            adminCtl.clearListenLink()
            adminCtl.shareRoom(host, room)
            delay(8_000)
            assertTrue(
                adminCtl.listenLink.value == null,
                "host minted a link after listening was turned off",
            )
            println("6. sharing refused once listening is off")

            // 7. Turn the line off entirely.
            adminCtl.configureRoom(host, room, open = false, listen = false)
            withTimeout(25_000) { adminCtl.invites.first { key !in it } }
            println("7. line closed, gone from the admin's view")

            hostCtl.stop(); adminCtl.stop()
        }
    }
}
