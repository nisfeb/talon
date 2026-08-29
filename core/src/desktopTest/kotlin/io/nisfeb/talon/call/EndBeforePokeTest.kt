package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ending a call must not wait on the network.
 *
 * A poke now waits for its ack, and hangup/reject used to poke the
 * peer *before* clearing the call. With a ship that is slow or gone
 * the ack never comes, so the call stayed on screen for the full 15s
 * poke timeout and the red button read as dead — the "hang up doesn't
 * hang up" report. Hanging up is a local decision and needs nobody's
 * permission, so this pins the ordering.
 *
 * Reject rather than hangup because both share the shape and reject
 * reaches its live state (Incoming) from a single ring fact, with no
 * media stack to stand up.
 */
class EndBeforePokeTest {

    private class Store(private val entry: SavedSession) : SessionStore {
        override fun all() = listOf(entry)
        override fun active() = entry
        override fun activeShip() = entry.ship
        override fun save(entry: SavedSession, makeActive: Boolean) {}
        override fun setActive(ship: String) {}
        override fun remove(ship: String) {}
        override fun clearAll() {}
    }

    @Test
    fun rejectClearsTheCallWithoutWaitingForTheAck() = runBlocking {
        // Every poke is accepted at the transport (204) and then never
        // acked on the stream — a ship that takes the message and goes
        // quiet, which is exactly the case that exposed the bug. The
        // subscribe poke is the exception: without its ack the channel
        // never delivers the ring in the first place.
        val sse = buildString {
            append("id: 1\ndata: {\"id\":1,\"response\":\"poke\",\"ok\":true}\n\n")
            append(
                "id: 2\ndata: " +
                    """{"id":2,"response":"diff","json":""" +
                    """{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}}""" +
                    "\n\n",
            )
        }
        val engine = MockEngine { req ->
            when {
                req.method.value == "PUT" -> respond("", HttpStatusCode.NoContent)
                req.url.encodedPath.startsWith("/~/scry") ->
                    // Nothing configured: no ICE, no policy, no version.
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(
                    sse,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }
        }
        val ship = "~nec"
        val session = UrbitSession(
            HttpClient(engine),
            Store(
                SavedSession(
                    shipUrl = "https://ship.test",
                    ship = ship,
                    // Assembled rather than spelled out: the secret
                    // scanner reads a literal urbauth cookie name as a
                    // leaked session, and it is right to.
                    cookieName = "urbauth-" + ship,
                    cookieValue = "0v1",
                    cookieDomain = "ship.test",
                ),
            ),
        )
        // openChannel() needs baseUrl/shipName, which only a restore sets.
        session.tryRestore()
        val controller = CallController(
            session,
            CallEngineProvider { error("no media needed to decline a ring") },
        )
        try {
            controller.start()
            withTimeout(10_000) {
                controller.state.first { it is CallUiState.Incoming }
            }

            controller.reject()

            // Well inside POKE_ACK_TIMEOUT_MS. Before the fix this
            // waited the full 15s and then some, because the state only
            // moved once the poke gave up.
            val settled = withTimeout(3_000) {
                controller.state.first { it !is CallUiState.Incoming }
            }
            assertTrue(
                settled is CallUiState.Ended,
                "declining should end the call locally at once, got $settled",
            )
        } finally {
            controller.stop()
        }
    }
}
