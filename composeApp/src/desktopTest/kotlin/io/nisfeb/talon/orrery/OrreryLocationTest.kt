package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrreryAccountEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fix sent under this install's key: once, and not twice for the same place. */
class OrreryLocationTest {
    private val state = Json.parseToJsonElement(
        """{"me": "person/me", "bodies": [
            {"id": "place/home", "name": "Home", "attrs": {"geo": {"value": "40.6782, -73.9442"}}},
            {"id": "place/office", "name": "Office", "attrs": {"geo": {"value": {"lat": 40.7536, "lng": -73.9832}}}},
            {"id": "place/nowhere", "name": "Nowhere", "attrs": {}},
            {"id": "person/rose", "name": "Rose", "attrs": {"geo": {"value": "1,1"}}}]}""",
    ).jsonObject

    private fun fix(lat: Double, lon: Double, acc: Double = 20.0) = LocationFix(lat, lon, acc, 1_789_000_000_000L)

    // The pure half is in LocationValueTest, in commonTest, so every
    // target runs it. What is left needs a database.
    @Test
    fun `a fix goes up once, as one observation on the owner, and the same place twice is said once`() = runTest {
        val dir = createTempDirectory(prefix = "talon-location-test-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
        val observed = mutableListOf<String>()
        val bare = HttpClient(MockEngine { req ->
            val body = if (req.url.encodedPath.endsWith("/observe")) {
                observed += (req.body as TextContent).text
                """{"bodies": [], "observations": [{"id": "o1", "ok": true}]}"""
            } else state.toString()
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val owner = HttpClient(MockEngine { respond("{}") })
        val at = fix(40.6790, -73.9442)
        sendLocation(owner, db, "https://ship", "~zod", at, null, bare).getOrThrow()
        sendLocation(owner, db, "https://ship", "~zod", at.copy(atMs = at.atMs + 60_000), null, bare).getOrThrow()
        assertEquals(1, observed.size, "the same place twice is said once")
        val row = Json.parseToJsonElement(observed.single()).jsonObject["observations"]!!.jsonArray.single().jsonObject
        assertEquals("person/me", row["subject"]!!.jsonPrimitive.content)
        assertEquals("location", row["attr"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("ref", "place/home") }, row["value"])
        assertEquals("device", row["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertTrue("40.67" !in observed.single(), "never the coordinates")
        db.close()
        dir.deleteRecursively()
    }

    // ─── the position, for the leave alert (orrery 69) ──────────────

    private class Ship(var position: Int = 200, var stateOk: Boolean = true) {
        val positions = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val order = mutableListOf<String>()
        var observed = 0
    }

    private fun shipClient(ship: Ship, state: kotlinx.serialization.json.JsonObject) = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        ship.order += path.substringAfterLast("/api/")
        when {
            path.endsWith("/api/position") -> {
                ship.positions += req
                respond(if (ship.position == 200) """{"ok":true}""" else """{"error":"no such route"}""",
                    io.ktor.http.HttpStatusCode.fromValue(ship.position), headersOf(HttpHeaders.ContentType, "application/json"))
            }
            path.endsWith("/observe") -> {
                ship.observed++
                respond("""{"bodies": [], "observations": [{"id": "o1", "ok": true}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            !ship.stateOk -> respond("""{"error":"busy"}""", io.ktor.http.HttpStatusCode.ServiceUnavailable)
            else -> respond(state.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    })

    private fun withDb(block: suspend (AppDatabase) -> Unit) = runTest {
        val dir = createTempDirectory(prefix = "talon-position-test-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
        try { block(db) } finally { db.close(); dir.deleteRecursively() }
    }

    @Test
    fun `the position goes on every fix, first, under the key, and never as an observation`() = withDb { db ->
        val ship = Ship()
        val bare = shipClient(ship, state)
        val owner = HttpClient(MockEngine { error("the position is the key's, never the owner's cookie") })
        val at = fix(40.6790, -73.9442, acc = 11.6)
        sendLocation(owner, db, "https://ship", "~zod", at, null, bare).getOrThrow()
        sendLocation(owner, db, "https://ship", "~zod", at.copy(atMs = at.atMs + 60_000), null, bare).getOrThrow()
        assertEquals(2, ship.positions.size, "every fix, though the place did not change")
        assertEquals(1, ship.observed, "the place is still said once")
        assertEquals("position", ship.order.first(), "before the state read, the slow part")
        val req = ship.positions.first()
        assertEquals("https://ship/apps/orrery/api/position", req.url.toString())
        assertEquals("Bearer k1.secret", req.headers[HttpHeaders.Authorization])
        assertEquals("""{"lat":40.679000,"lon":-73.944200,"acc":12,"at":"2026-09-10T00:26:40Z"}""", (req.body as TextContent).text)
    }

    @Test
    fun `a busy ship still gets the position, and the send fails so it is tried again`() = withDb { db ->
        val ship = Ship(stateOk = false)
        val result = sendLocation(HttpClient(MockEngine { respond("{}") }), db, "https://ship", "~zod", fix(40.6790, -73.9442), null, shipClient(ship, state))
        assertEquals(1, ship.positions.size, "the position landed before the state read failed")
        assertTrue(result.isFailure, "a failed send is retried by the worker")
    }

    @Test
    fun `an orrery before 69 is asked once a day, and the place still goes`() = withDb { db ->
        val ship = Ship(position = 404)
        val bare = shipClient(ship, state)
        val owner = HttpClient(MockEngine { respond("{}") })
        val at = fix(40.6790, -73.9442)
        sendLocation(owner, db, "https://ship", "~zod", at, null, bare).getOrThrow()
        assertEquals(1, ship.observed, "a ship with no position route still hears the place")
        sendLocation(owner, db, "https://ship", "~zod", at.copy(lat = 40.7536, lon = -73.9832, atMs = at.atMs + 3_600_000), null, bare).getOrThrow()
        assertEquals(1, ship.positions.size, "not asked again within the day")
        ship.position = 200
        sendLocation(owner, db, "https://ship", "~zod", at.copy(atMs = at.atMs + 25 * 3_600_000L), null, bare).getOrThrow()
        assertEquals(2, ship.positions.size, "asked again a day on: the ship may have been updated")
    }

    @Test
    fun `any other refusal of the position fails the send`() = withDb { db ->
        val ship = Ship(position = 500)
        val result = sendLocation(HttpClient(MockEngine { respond("{}") }), db, "https://ship", "~zod", fix(40.6790, -73.9442), null, shipClient(ship, state))
        assertTrue(result.isFailure)
        assertEquals(0, ship.observed, "nothing else is said on a send that will be tried again whole")
    }
}
