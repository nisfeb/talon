package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signing in to another ship from the phone, with a ship already open
 * there. The sign-in went through the open ship's session: a failed one
 * wiped that session's cookie, and one answered with no cookie of its own
 * took the open ship's (eyre puts it back on every answer), so the owner
 * landed back on the open ship with nothing said.
 */
class SessionLoginTest {
    private class Store : SessionStore {
        val saved = mutableMapOf<String, SavedSession>()
        var current: String? = null
        override fun all() = saved.values.toList()
        override fun active() = current?.let { saved[it] }
        override fun activeShip() = current
        override fun save(entry: SavedSession, makeActive: Boolean) {
            saved[entry.ship] = entry
            if (makeActive) current = entry.ship
        }
        override fun setActive(ship: String) { current = ship }
        override fun remove(ship: String) { saved.remove(ship); if (current == ship) current = null }
        override fun clearAll() { saved.clear(); current = null }
    }

    /** Eyre's session cookie name for a (fake) ship. */
    private fun auth(ship: String) = "urbauth-~$ship"

    /** The Cookie header on each request to the open ship. */
    private val sentToZod = CopyOnWriteArrayList<String>()

    private fun open(live: Boolean = true, login: () -> Pair<HttpStatusCode, String?>): Pair<UrbitSession, Store> {
        lateinit var session: UrbitSession
        val http = HttpClient(MockEngine { req ->
            if (req.url.host == "zod.test") {
                sentToZod += req.headers[HttpHeaders.Cookie].orEmpty()
                // Eyre refreshes the session cookie on every answer.
                return@MockEngine respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "${auth("zod")}=0vzod; Path=/; Max-Age=604800"))
            }
            // While the other ship answers, the open ship's own traffic
            // goes on through its session, and its cookie lands in the jar.
            if (live) session.http.get("https://zod.test/~/scry/y.json")
            val (status, cookie) = login()
            respond("0v1", status, cookie?.let { headersOf(HttpHeaders.SetCookie, it) } ?: headersOf())
        })
        val store = Store().apply {
            save(SavedSession(shipUrl = "https://zod.test", ship = "~zod", cookieName = auth("zod"), cookieValue = "0vzod", cookieDomain = "zod.test"))
        }
        session = UrbitSession(http, store).also { it.tryRestore("~zod") }
        return session to store
    }

    private fun UrbitSession.touchZod() = runBlocking { http.get("https://zod.test/~/scry/x.json") }

    @Test
    fun `a sign-in that fails leaves the ship open here as it was`() = runBlocking {
        // A quiet moment: nothing of the open ship's puts its cookie back.
        val (session, store) = open(live = false) { HttpStatusCode.BadRequest to null }
        assertTrue(session.login("https://bus.test", "wrong-code").isFailure)
        assertEquals("~zod" to "https://zod.test", session.shipName to session.baseUrl)
        assertEquals("~zod", store.activeShip())
        session.touchZod()
        assertEquals("${auth("zod")}=0vzod", sentToZod.last(), "still signed in")
    }

    @Test
    fun `an answer with no cookie of its own is a failure, whatever the open ship put in the jar`() = runBlocking {
        val (session, store) = open { HttpStatusCode.OK to null }
        val r = session.login("https://bus.test", "0000")
        assertTrue(r.isFailure, "not ${r.getOrNull()}")
        assertEquals("https://zod.test", store.saved.getValue("~zod").shipUrl, "the open ship's entry is not pointed at the other address")
        assertEquals(setOf("~zod"), store.saved.keys)
    }

    @Test
    fun `a sign-in keeps the cookie from its own answer and only that`() = runBlocking {
        val (session, store) = open { HttpStatusCode.OK to "${auth("bus")}=0vbus; Path=/; Max-Age=604800" }
        assertEquals("~bus", session.login("https://bus.test", "lidlut-tabwed").getOrThrow())
        assertEquals("~bus" to "https://bus.test", session.shipName to session.baseUrl)
        assertEquals("0vbus", store.saved.getValue("~bus").cookieValue)
        assertEquals("~bus", store.activeShip())
        session.touchZod()
        assertEquals("${auth("bus")}=0vbus", sentToZod.last(), "one cookie in the jar, the new ship's")
    }
}
