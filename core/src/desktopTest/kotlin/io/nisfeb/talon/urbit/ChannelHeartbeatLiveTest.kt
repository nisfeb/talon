package io.nisfeb.talon.urbit

import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The same claim as [ChannelHeartbeatTest], against a real ship: an
 * idle event stream keeps arriving, so the session watchdog must not
 * decide it is dead. Needs a ship and its code, so it only runs when
 * asked:
 *
 *   TALON_LIVE_SHIP_URL=http://localhost:8081 \
 *   TALON_LIVE_SHIP_CODE=... \
 *   ./gradlew :core:desktopTest --tests '*ChannelHeartbeatLiveTest*'
 *
 * Opens one channel, subscribes to a quiet path, watches for three
 * minutes (well past the 90s the watchdog allows), and deletes the
 * channel on the way out.
 */
class ChannelHeartbeatLiveTest {
    @Test
    fun `an idle real ship keeps its stream alive well past the watchdog window`() {
        val url = System.getenv("TALON_LIVE_SHIP_URL") ?: return
        val code = System.getenv("TALON_LIVE_SHIP_CODE") ?: return
        val http = createAppHttpClient()
        runBlocking {
            val session = UrbitSession(http, MemorySessionStore())
            val ship = session.login(url, code).getOrThrow()
            val ch = session.openChannel()
            var worstIdleMs = 0L
            try {
                val pump = launch { ch.events().collect { } }
                ch.subscribe("settings", "/desk/talon")
                // Three minutes of a quiet ship: no posts, no pokes.
                repeat(18) {
                    delay(10_000)
                    val idle = ch.streamIdleMs
                    if (idle != Long.MAX_VALUE && idle > worstIdleMs) worstIdleMs = idle
                }
                pump.cancel()
            } finally {
                runCatching { ch.delete() }
                http.close()
            }
            println("live ship $ship: worst stream idle over 3 min = ${worstIdleMs}ms")
            assertTrue(
                worstIdleMs in 1 until 90_000,
                "an idle stream must stay live inside the watchdog's 90s; worst was ${worstIdleMs}ms",
            )
        }
    }
}

/** A session store that keeps one entry in memory, for tests. */
private class MemorySessionStore : SessionStore {
    private var entry: SavedSession? = null
    override fun all(): List<SavedSession> = listOfNotNull(entry)
    override fun active(): SavedSession? = entry
    override fun activeShip(): String? = entry?.ship
    override fun save(entry: SavedSession, makeActive: Boolean) { this.entry = entry }
    override fun setActive(ship: String) {}
    override fun remove(ship: String) { entry = null }
    override fun clearAll() { entry = null }
}
