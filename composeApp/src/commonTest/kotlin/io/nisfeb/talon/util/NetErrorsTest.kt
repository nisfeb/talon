package io.nisfeb.talon.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.urbit.PokeNacked
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.urbit.notServed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A proxy's 502, 503 or 504 on a scry, an app's own request, a thread, a
 * poke or a sign-in is the ship down or slow, said so: a note save on one
 * read "Your ship couldn't take it (PUT /notes/...: HTTP 502)". A scry's
 * 500 is not, since it is the path a read falls back from. The messages
 * are the ones the real channel and session give, through a mock engine.
 */
class NetErrorsTest {
    private val store = object : SessionStore {
        private val s = SavedSession("https://zod.test", "~zod", "test-session", "0v1", "zod.test")
        override fun all() = listOf(s)
        override fun active() = s
        override fun activeShip() = s.ship
        override fun save(entry: SavedSession, makeActive: Boolean) {}
        override fun setActive(ship: String) {}
        override fun remove(ship: String) {}
        override fun clearAll() {}
    }

    /** Every request answered [status], with the proxy's HTML page. */
    private fun session(status: HttpStatusCode) =
        UrbitSession(HttpClient(MockEngine { respond("<html><body>Bad Gateway</body></html>", status) }), store)

    private suspend fun failure(block: suspend () -> Unit): Throwable =
        runSuspendCatching { block() }.exceptionOrNull() ?: error("it did not fail")

    @Test
    fun `a proxy's 502 to 504 is the ship down on every road to it`() = runTest {
        val bad = session(HttpStatusCode.BadGateway).apply { tryRestore("~zod") }.openChannel()
        val gone = session(HttpStatusCode.ServiceUnavailable).apply { tryRestore("~zod") }.openChannel()
        val slow = session(HttpStatusCode.GatewayTimeout).apply { tryRestore("~zod") }.openChannel()
        val got = listOf(
            failure { bad.scry("notes", "/v0/notebooks") },
            failure { bad.apiJson("PUT", "/notes/~/v1/notebooks/~bus/recipes/notes/11", JsonObject(emptyMap())) },
            failure { slow.runThread("groups", "group-create", "group-create-thread", "json", JsonObject(emptyMap())) },
            failure { gone.poke("hood", "helm-hi", JsonObject(emptyMap())) },
            session(HttpStatusCode.BadGateway).login("https://zod.test", "lidlut-tabwed").exceptionOrNull()!!,
        )
        assertEquals(
            listOf(
                "scry notes/v0/notebooks: HTTP 502",
                "PUT /notes/~/v1/notebooks/~bus/recipes/notes/11: HTTP 502",
                "thread group-create-thread: HTTP 504",
                "channel PUT: HTTP 503",
                "login HTTP 502",
            ),
            got.map { it.message },
        )
        got.forEach { e ->
            assertTrue(isShipSlow(e), e.message)
            assertEquals("your ship is down or out of reach", readableReason(e.message), e.message)
            val p = problemOf("Couldn't save", e)
            assertEquals("Couldn't save: your ship is slow or out of reach. Try again when it's back.", p.line, e.message)
            assertTrue(p.calm)
            assertFalse(notServed(e), "a 50x says nothing of the path: ${e.message}")
        }
        // The event stream's own, as UrbitChannel words it.
        assertTrue(isShipSlow(IllegalStateException("channel SSE: HTTP 502")))
    }

    @Test
    fun `a scry's 500 is a path not served and not the ship down`() = runTest {
        val ch = session(HttpStatusCode.InternalServerError).apply { tryRestore("~zod") }.openChannel()
        val e = failure { ch.scry("notes", "/v0/notebooks") }
        assertEquals("scry notes/v0/notebooks: HTTP 500", e.message)
        assertFalse(isShipSlow(e))
        assertTrue(notServed(e), "the fallback walk goes on")
        // A channel PUT's 5xx was the ship slow before, and stays so.
        assertTrue(isShipSlow(failure { ch.poke("hood", "helm-hi", JsonObject(emptyMap())) }))
    }

    @Test
    fun `another service's 502 or a proxy's page is not the ship`() {
        listOf(
            "S3 PUT failed: HTTP 502",
            "memex upload-url failed: HTTP 502",
            "mcp tools/list: HTTP 502 — (empty response body)",
            "HTTP 502 <html><body>Bad Gateway</body></html>",
            "HTTP 502",
            "scry notes/v0/notebooks: HTTP 501",
            "scry notes/v0/notebooks: HTTP 5020",
            "say scry x: HTTP 502",
        ).forEach { m ->
            assertFalse(isShipSlow(IllegalStateException(m)), m)
            assertTrue(readableReason(m) != "your ship is down or out of reach", m)
        }
        assertNull(readableReason("HTTP 502 <html><body>Bad Gateway</body></html>"))
        // A refusal is never the ship slow, whatever it says.
        assertFalse(isShipSlow(PokeNacked("notes", "notes-action", "PUT /x: HTTP 502")))
    }
}
