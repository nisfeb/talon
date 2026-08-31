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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A cancelled peek is not a host that failed to answer.
 *
 * peekRoom is launched from the chat screen's composition scope, and
 * its poke awaits an ack. Navigating away mid-flight cancels it, and
 * the catch recorded the cancellation's message — "The coroutine
 * scope left the composition" — into peekFailed, which OUTLIVES the
 * screen. Every later visit to that chat re-rendered the internal
 * message as a permanent "Party line: ..." banner. Fixed in the pump
 * first (same bug, different surface) and missed here; this pins the
 * writer users actually saw.
 */
class PeekCancelTest {

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
    fun aCancelledPeekRecordsNothing() = runBlocking {
        // The subscribe ack flows so the channel comes up; the PEEK
        // poke's ack never arrives, so peekRoom hangs on await until
        // we cancel it — exactly the navigate-away timing.
        val sse = "id: 1\ndata: {\"id\":1,\"response\":\"poke\",\"ok\":true}\n\n"
        val engine = MockEngine { req ->
            when {
                req.method.value == "PUT" -> respond("", HttpStatusCode.NoContent)
                req.url.encodedPath.startsWith("/~/scry") ->
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(
                    sse, HttpStatusCode.OK,
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
                    cookieName = "urbauth-" + ship,
                    cookieValue = "0v1",
                    cookieDomain = "ship.test",
                ),
            ),
        )
        session.tryRestore()
        val controller = CallController(
            session,
            CallEngineProvider { error("no media in a peek") },
        )
        try {
            controller.start()
            // Let the channel establish (subscribe ack id 1 consumed).
            delay(500)
            val peek = launch { controller.peekRoom("~zod", "someline") }
            delay(300)   // in flight, awaiting an ack that never comes
            peek.cancel() // the user navigated away
            peek.join()
            assertTrue(
                controller.peekFailed.value.isEmpty(),
                "a cancellation was recorded as a host failure: " +
                    controller.peekFailed.value,
            )
        } finally {
            controller.stop()
        }
    }

    @Test
    fun aTransportFailureShowsASentenceNotAStackDump() = runBlocking {
        // The PUT itself fails, the way a 30s Ktor timeout does. What
        // lands in peekFailed is rendered verbatim in the UI, so it
        // must be our sentence — rc31 showed users a raw
        // "Request timeout has expired [url=... request_timeout=...]".
        val engine = MockEngine { req ->
            when {
                // The exact exception text rc31 shipped to users: a 504
                // was too polite to catch the leak — its message has no
                // internals in it — so throw what Ktor's timeout throws.
                req.method.value == "PUT" -> throw RuntimeException(
                    "Request timeout has expired " +
                        "[url=https://ship.test/~/channel/1788, request_timeout=30000 ms]",
                )
                req.url.encodedPath.startsWith("/~/scry") ->
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(
                    "id: 1\ndata: {\"id\":1,\"response\":\"poke\",\"ok\":true}\n\n",
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
                    cookieName = "urbauth-" + ship,
                    cookieValue = "0v1",
                    cookieDomain = "ship.test",
                ),
            ),
        )
        session.tryRestore()
        val controller = CallController(
            session,
            CallEngineProvider { error("no media in a peek") },
        )
        try {
            controller.start()
            delay(500)
            controller.peekRoom("~zod", "someline")
            val why = controller.peekFailed.value["~zod/someline"]
            assertTrue(why != null, "a failed peek must be recorded")
            assertTrue(
                !why.contains("url=") && !why.contains("http") &&
                    !why.contains("timeout has expired"),
                "internals leaked into the banner: $why",
            )
        } finally {
            controller.stop()
        }
    }
}
