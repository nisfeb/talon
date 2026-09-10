package io.nisfeb.talon.call

import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claim of [PresenceAnnounceTest], against a real wire-9 ship:
 * entering a line we host announces the roster, leaving announces the
 * empty one, and the occupancy the party tab reads follows it down
 * with nobody asking. This is the leg that was missing when 1.7.7
 * shipped a dot that would not go out.
 *
 * Needs a ship running trunk wire 9 that hosts [TALON_LIVE_ROOM], so
 * it only runs when asked:
 *
 *   TALON_LIVE_SHIP_URL=http://localhost:8081 \
 *   TALON_LIVE_SHIP_CODE=... TALON_LIVE_ROOM=line1 \
 *   ./gradlew :core:desktopTest --tests '*PresenceAnnounceLiveTest*'
 */
class PresenceAnnounceLiveTest {

    @Test
    fun `a real host's announcement moves the count both ways`() {
        val url = System.getenv("TALON_LIVE_SHIP_URL") ?: return
        val code = System.getenv("TALON_LIVE_SHIP_CODE") ?: return
        val room = System.getenv("TALON_LIVE_ROOM") ?: return
        runBlocking {
            val session = UrbitSession(createAppHttpClient(), OneEntryStore())
            val ship = session.login(url, code).getOrThrow()
            val c = CallController(
                session,
                CallEngineProvider { error("no media needed for presence") },
            )
            val key = "$ship/$room"
            suspend fun until(what: String, cond: () -> Boolean) {
                val ok = withTimeoutOrNull(20_000) {
                    while (!cond()) delay(200)
                    true
                }
                assertTrue(
                    ok == true,
                    "timed out waiting for $what; " +
                        "ship=$ship onLine=${c.onLine.value} presence=${c.presence.value} " +
                        "rooms=${c.rooms.value.keys} wire=${c.wire.value}",
                )
            }
            try {
                c.start()
                until("the /calls subscription") { c.connected.value && c.wire.value > 0 }
                assertTrue(
                    c.wire.value >= TrunkWire.WIRE_VERSION_ANNOUNCES,
                    "ship speaks wire ${c.wire.value}; this test needs a host that announces",
                )

                // Start from an empty line. A second %entered from a
                // ship already on it moves its stamp, not the roster,
                // and the host correctly announces nothing — so a rerun
                // against a line we never left would wait forever.
                c.leaveRoom(ship, room)
                delay(1_500)

                c.enterRoom(ship, room)
                until("our own arrival to be announced") {
                    c.onLine.value[key]?.contains(ship) == true
                }
                assertEquals(1, c.presence.value[key], "count follows the roster up")

                c.leaveRoom(ship, room)
                until("the empty roster to be announced") {
                    key in c.onLine.value && c.onLine.value.getValue(key).isEmpty()
                }
                assertEquals(0, c.presence.value[key], "count follows the roster down")
            } finally {
                c.stop()
            }
        }
    }
}

/** A session store that keeps one entry in memory, for this test. */
private class OneEntryStore : SessionStore {
    private var entry: SavedSession? = null
    override fun all(): List<SavedSession> = listOfNotNull(entry)
    override fun active(): SavedSession? = entry
    override fun activeShip(): String? = entry?.ship
    override fun save(entry: SavedSession, makeActive: Boolean) { this.entry = entry }
    override fun setActive(ship: String) {}
    override fun remove(ship: String) { entry = null }
    override fun clearAll() { entry = null }
}
