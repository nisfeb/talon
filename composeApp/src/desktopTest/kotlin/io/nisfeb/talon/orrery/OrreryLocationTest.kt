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

/** Where the owner is, from the phone: a place orrery knows, else a name, never the coordinates. */
class OrreryLocationTest {
    private val state = Json.parseToJsonElement(
        """{"me": "person/me", "bodies": [
            {"id": "place/home", "name": "Home", "attrs": {"geo": {"value": "40.6782, -73.9442"}}},
            {"id": "place/office", "name": "Office", "attrs": {"geo": {"value": {"lat": 40.7536, "lng": -73.9832}}}},
            {"id": "place/nowhere", "name": "Nowhere", "attrs": {}},
            {"id": "person/rose", "name": "Rose", "attrs": {"geo": {"value": "1,1"}}}]}""",
    ).jsonObject

    private fun fix(lat: Double, lon: Double, acc: Double = 20.0) = LocationFix(lat, lon, acc, 1_789_000_000_000L)

    @Test
    fun `places with a position the ship has, in either shape`() {
        assertEquals(listOf("place/home", "place/office"), geoPlaces(state).map { it.id })
        val d = metresBetween(40.6782, -73.9442, 40.7536, -73.9832)
        assertTrue(d in 9_000.0..9_300.0, "Brooklyn to midtown is about 9 km, not $d")
    }

    @Test
    fun `a known place, else a name, else leaving is said once`() {
        val places = geoPlaces(state)
        assertEquals(buildJsonObject { put("ref", "place/home") }, locationValue(fix(40.6790, -73.9442), places, "Crown Heights, New York", null))
        assertEquals(JsonPrimitive("Crown Heights, New York"), locationValue(fix(40.6700, -73.9442), places, "Crown Heights, New York", null), "900 m off is not home")
        assertEquals(buildJsonObject { put("ref", "place/home") }, locationValue(fix(40.6810, -73.9442, acc = 400.0), places, null, null), "a loose fix reaches as far as it is loose")
        assertEquals(JsonNull, locationValue(fix(10.0, 10.0), places, null, buildJsonObject { put("ref", "place/home") }), "left, and nowhere known")
        assertNull(locationValue(fix(10.0, 10.0), places, null, JsonNull), "said already")
        assertNull(locationValue(fix(10.0, 10.0), places, null, null))
    }

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
}
