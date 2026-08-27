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
 * The group-admin switch, end to end: on, off, and on again.
 *
 * This is the exact path the toggle takes — `configureRoom`, not
 * `openRoom`/`closeRoom` — because that is where the switch failed
 * before: %configure bailed when the room did not exist, so it could
 * only ever modify, never create. Turning it *off* has to work too, or
 * the switch flips and nothing happens.
 *
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*RoomToggleE2E*'
 */
class RoomToggleE2ETest {

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
    fun theAdminSwitchTurnsALineOnAndOff() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping room toggle test")
            return
        }
        val url = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val code = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"

        runBlocking {
            val session = UrbitSession(createAppHttpClient(), MemStore())
            val ship = session.login(url, code).getOrThrow()
            val ctl = CallController(session, DesktopCallEngineProvider)
            ctl.start()
            delay(4_000)

            val room = "toggle-e2e-${System.currentTimeMillis()}"
            val key = "$ship/$room"

            // On: creates a line the ship has never hosted.
            ctl.configureRoom(
                ship, room, open = true, listen = false,
                title = "Toggle E2E",
                members = listOf(ship),
                admins = listOf(ship),
            )
            withTimeout(20_000) { ctl.rooms.first { key in it } }
            println("on: line exists")

            // Off: the switch has to actually remove it, or the party
            // icon keeps showing for a line nobody can join.
            ctl.configureRoom(ship, room, open = false, listen = false)
            withTimeout(20_000) { ctl.rooms.first { key !in it } }
            println("off: line gone")

            // And on again, to prove closing didn't wedge anything.
            ctl.configureRoom(
                ship, room, open = true, listen = false,
                title = "Toggle E2E",
                members = listOf(ship),
                admins = listOf(ship),
            )
            withTimeout(20_000) { ctl.rooms.first { key in it } }
            println("on again: line exists")

            // Each switch must take on ONE call. The host used to be
            // excluded from its own announcements, so the only path to
            // the UI was a re-scry racing the poke and an admin's first
            // click looked like nothing.
            ctl.setRoomListen(room, true)
            withTimeout(20_000) { ctl.rooms.first { it[key]?.listen == true } }
            println("listen on after one call")

            // And sharing works immediately after — the failure people
            // actually hit was clicking "create listen link" while the
            // ship still had listening off.
            ctl.clearListenLink()
            ctl.shareRoom(room)
            val link = withTimeout(20_000) { ctl.listenLink.first { it != null } }!!
            assertTrue("token=" in link.url, "no token in ${link.url}")
            println("link minted right after enabling")

            ctl.setRoomListen(room, false)
            withTimeout(20_000) { ctl.rooms.first { it[key]?.listen == false } }
            println("listen off after one call")

            ctl.configureRoom(ship, room, open = false, listen = false)
            withTimeout(20_000) { ctl.rooms.first { key !in it } }
            assertTrue(key !in ctl.rooms.value, "line survived being turned off")

            // The admin screen shows which server a group is on; with
            // no room override that is the ship's own, and it must be
            // readable rather than a placeholder.
            assertTrue(
                ctl.shipSfuBase.value.isNotEmpty(),
                "ship sidecar base is unknown, so the UI can only say 'the host's'",
            )
            println("ship sidecar: ${ctl.shipSfuBase.value}")

            ctl.stop()
        }
    }
}
