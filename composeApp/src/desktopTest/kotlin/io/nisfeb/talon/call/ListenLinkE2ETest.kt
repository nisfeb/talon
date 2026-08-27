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
 * Anonymous listen links: off by default, on only when asked for.
 *
 * The negative case is the one that matters. A party line is gated by
 * the host's membership list — that is the trust boundary the whole
 * design leans on — and a public link deliberately punches through it.
 * So a room must refuse to mint one until its admins have said yes,
 * and refusal is silence rather than an error.
 *
 *   TRUNK_E2E=1 ./gradlew :composeApp:desktopTest --tests '*ListenLinkE2E*'
 */
class ListenLinkE2ETest {

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
    fun listeningIsOffUntilAskedFor() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping listen link test")
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

            val room = "listen-e2e-${System.currentTimeMillis()}"
            ctl.openRoom(room, "Listen E2E", listOf(ship), admins = listOf(ship))
            delay(3_000)

            // A new room must not hand out links, however nicely asked.
            ctl.clearListenLink()
            ctl.shareRoom(ship, room)
            delay(8_000)
            assertEquals(
                null, ctl.listenLink.value,
                "a room minted a listen link without listening being enabled",
            )
            println("listening off: no link, as required")

            // Turned on, the link appears and points at this room.
            ctl.setRoomListen(room, true)
            delay(3_000)
            ctl.shareRoom(ship, room)
            val link = withTimeout(20_000) { ctl.listenLink.first { it != null } }!!
            assertEquals(room, link.room)
            assertTrue("token=" in link.url, "link carries no token: ${link.url}")
            assertTrue(room in link.url, "link points at the wrong room: ${link.url}")
            // The link must open Trunk's own one-button page, not
            // Galène's conference UI — that UI hides audio-only
            // publishers and can't autoplay, so a plain group URL is
            // unusable to whoever you send it to.
            assertTrue("/listen/?group=" in link.url, "not a listen page: ${link.url}")
            assertTrue(
                link.expiresSecs > 0,
                "link has no expiry — the ttl is the only thing that can revoke it",
            )
            println("listening on: ${link.url.substringBefore("&token=")} (exp ${link.expiresSecs})")

            // And turning it back off must stop new links immediately.
            ctl.setRoomListen(room, false)
            delay(3_000)
            ctl.clearListenLink()
            ctl.shareRoom(ship, room)
            delay(8_000)
            assertEquals(
                null, ctl.listenLink.value,
                "a room kept minting links after listening was turned off",
            )
            println("listening off again: no link")

            ctl.stop()
        }
    }

    /**
     * An admin of a line hosted by *another* ship must be able to mint
     * a link. Only the host holds the signing key, so the request has
     * to travel — %share-room used to look only at our own rooms and
     * silently do nothing, which is what "create listen link does
     * nothing" turned out to be.
     */
    @Test
    fun anAdminCanShareALineHostedElsewhere() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping remote share test")
            return
        }
        val aUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val aCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val bUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val bCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        runBlocking {
            val hostSession = UrbitSession(createAppHttpClient(), MemStore())
            val adminSession = UrbitSession(createAppHttpClient(), MemStore())
            val hostShip = hostSession.login(aUrl, aCode).getOrThrow()
            val adminShip = adminSession.login(bUrl, bCode).getOrThrow()

            val hostCtl = CallController(hostSession, DesktopCallEngineProvider)
            val adminCtl = CallController(adminSession, DesktopCallEngineProvider)
            hostCtl.start(); adminCtl.start()
            delay(4_000)

            val room = "remote-share-${System.currentTimeMillis()}"
            // The host opens it and names the other ship an admin.
            hostCtl.openRoom(room, "Remote Share", listOf(adminShip), admins = listOf(adminShip))
            delay(3_000)
            hostCtl.setRoomListen(room, true)
            delay(3_000)

            // The admin — who holds no key — asks the host for a link.
            adminCtl.clearListenLink()
            adminCtl.shareRoom(hostShip, room)
            val link = withTimeout(30_000) { adminCtl.listenLink.first { it != null } }!!
            assertTrue("token=" in link.url, "no token in ${link.url}")
            assertEquals(room, link.room)
            // The subgroup must be the HOST's — a listener has to land
            // in the same Galène room the members are in, and the
            // subgroup is host-qualified. Get this wrong and the link
            // opens an empty room with no error anywhere.
            assertTrue(
                "${hostShip.removePrefix("~")}-$room" in link.url,
                "link points at the wrong host's subgroup: ${link.url.substringBefore("&token=")}",
            )
            assertTrue("/listen/?group=" in link.url, "not a listen page: ${link.url}")
            println("remote admin got a link: ${link.url.substringBefore("&token=")}")

            hostCtl.configureRoom(hostShip, room, open = false, listen = false)
            hostCtl.stop(); adminCtl.stop()
        }
    }
}
