package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrreryAccountEntity
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sending half: what goes up, under which key, and what is kept between passes. */
class OrreryHealthSendTest {
    // 2026-10-06 15:20 UTC, 11:20 in New York.
    private val now = 1_791_300_000_000L
    private val hour = 60 * 60_000L
    private val zone = TimeZone.of("America/New_York")

    private class Ship(var status: Int = 200) {
        val posts = mutableListOf<io.ktor.client.request.HttpRequestData>()
        fun days() = posts.map { Json.parseToJsonElement((it.body as TextContent).text).jsonObject["day"]!!.jsonPrimitive.content }
    }

    private fun client(ship: Ship) = HttpClient(MockEngine { req ->
        ship.posts += req
        respond(
            if (ship.status == 200) """{"ok":true}""" else """{"error":"no"}""",
            HttpStatusCode.fromValue(ship.status), headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })

    /** Every day readable, with steps that say which day it was. */
    private class Source(var readableUntil: LocalDate? = null) : HealthSource {
        val asked = mutableListOf<String>()
        override suspend fun day(day: LocalDate, zone: TimeZone, partial: Boolean): HealthDay? {
            if (readableUntil != null && day > readableUntil!!) return null
            asked += day.toString()
            return HealthDay(day, day.day.toLong(), null, emptyList(), emptyList(), partial)
        }
    }

    private fun withDb(token: Boolean = true, block: suspend (AppDatabase) -> Unit) = runTest {
        val dir = createTempDirectory(prefix = "talon-health-test-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        if (token) db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
        try { block(db) } finally { db.close(); dir.deleteRecursively() }
    }

    private val owner = HttpClient(MockEngine { error("health goes under the key, never the owner's cookie") })

    @Test
    fun `the first pass sends two weeks and today, oldest first, under the key, to the health route`() = withDb { db ->
        val ship = Ship()
        val n = sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now, client(ship)).getOrThrow()
        assertEquals(15, n)
        assertEquals("2026-09-22", ship.days().first())
        assertEquals(listOf("2026-10-05", "2026-10-06"), ship.days().takeLast(2), "today by the owner's clock")
        val r = ship.posts.first()
        assertEquals("https://ship/apps/orrery/api/health", r.url.toString())
        assertEquals("POST", r.method.value)
        assertEquals("Bearer k1.secret", r.headers[HttpHeaders.Authorization])
        assertTrue((ship.posts.last().body as TextContent).text.endsWith(""""partial":true}"""))
    }

    @Test
    fun `a pass soon after sends nothing, three hours on sends today only, and the next morning sends yesterday too`() = withDb { db ->
        val ship = Ship()
        val c = client(ship)
        sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now, c).getOrThrow()
        ship.posts.clear()
        assertEquals(0, sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now + hour, c).getOrThrow())
        assertEquals(1, sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now + 3 * hour, c).getOrThrow())
        assertEquals(listOf("2026-10-06"), ship.days())
        ship.posts.clear()
        sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now + 20 * hour, c).getOrThrow()
        assertEquals(listOf("2026-10-06", "2026-10-07"), ship.days(), "yesterday finished, and the new today")
    }

    @Test
    fun `a day that cannot be read stops the pass and stays due`() = withDb { db ->
        val ship = Ship()
        val c = client(ship)
        val blocked = Source(readableUntil = LocalDate(2026, 9, 30))
        assertEquals(9, sendHealth(owner, db, "https://ship", "~zod", blocked, zone, now, c).getOrThrow())
        ship.posts.clear()
        sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now + hour, c).getOrThrow()
        assertEquals("2026-10-01", ship.days().first(), "picks up where it stopped")
        assertEquals("2026-10-06", ship.days().last())
    }

    @Test
    fun `an orrery without the route is asked again in a day, and a refusal is a failure that keeps the day due`() = withDb { db ->
        val ship = Ship(status = 404)
        val c = client(ship)
        assertEquals(0, sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now, c).getOrThrow())
        assertEquals(1, ship.posts.size)
        sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now + 2 * hour, c).getOrThrow()
        assertEquals(1, ship.posts.size, "not asked again within the day")
        ship.status = 503
        assertTrue(sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now + 25 * hour, c).isFailure)
        ship.status = 200
        ship.posts.clear()
        sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now + 26 * hour, c).getOrThrow()
        assertEquals("2026-09-23", ship.days().first(), "the refused day went up on the next pass")
    }

    @Test
    fun `no key, nothing sent`() = withDb(token = false) { db ->
        val ship = Ship()
        assertEquals(0, sendHealth(owner, db, "https://ship", "~zod", Source(), zone, now, client(ship)).getOrThrow())
        assertEquals(0, ship.posts.size)
    }
}
