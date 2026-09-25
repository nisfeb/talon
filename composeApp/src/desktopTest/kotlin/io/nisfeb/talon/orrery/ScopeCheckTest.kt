package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrreryAccountEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A key narrower than the ship's schema is replaced by one with all of
 * it, and the old one given back. A key the ship will not read for is
 * the owner's revocation, and is left alone; a mint the ship refuses is
 * not asked again every pass. Each mistake here costs the ship a key,
 * of the fifty it keeps, or overrules its owner.
 */
class ScopeCheckTest {
    private val full = """{"kinds":{"person":{"attrs":["status"]}},"actions":["task","message"]}"""

    private class Ship(
        val keySchema: String,
        val stateStatus: HttpStatusCode = HttpStatusCode.OK,
        val mintStatus: HttpStatusCode = HttpStatusCode.OK,
        val ownerSchema: String? = null,
    ) {
        val asked = CopyOnWriteArrayList<String>()
        val observed = CopyOnWriteArrayList<String>()
        var minted: String? = null
    }

    private fun run(ship: Ship, passes: Int = 1, seed: suspend (AppDatabase) -> Unit = {}, check: suspend (AppDatabase) -> Unit) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-scope-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "c1.secret"))
            seed(db)
            val http = HttpClient(
                MockEngine { req ->
                    val path = req.url.encodedPath
                    ship.asked += "${req.method.value} $path"
                    when {
                        path.endsWith("/api/schema") -> respond(ship.ownerSchema ?: full, headers = json)
                        path.endsWith("/api/observe") -> {
                            ship.observed += (req.body as TextContent).text
                            respond("""{"bodies":[],"observations":[]}""", headers = json)
                        }
                        path.endsWith("/api/clients") && req.method == HttpMethod.Post -> {
                            ship.minted = (req.body as TextContent).text
                            respond("""{"id":"c2","token":"c2.secret"}""", ship.mintStatus, json)
                        }
                        path.endsWith("/api/state") && req.headers[HttpHeaders.Authorization] == "Bearer c1.secret" ->
                            respond("""{"me":"person/me","rev":1,"bodies":[],"schema":${ship.keySchema}}""", ship.stateStatus, json)
                        path.endsWith("/api/state") -> respond("""{"me":"person/me","rev":1,"bodies":[],"schema":$full}""", headers = json)
                        "/apps/calendar/window.json" in req.url.toString() -> respond("""{"rows":[]}""", headers = json)
                        else -> respond("[]", headers = json)
                    }
                },
            )
            repeat(passes) {
                OrreryRepo(http, scope, db, "test", book = { setOf("~bus") }, bareClient = http).pass("https://ship.test", "~zod")
            }
            check(db)
        } finally {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a narrow key is swapped for one with the whole scope, and the old one given back`() {
        val ship = Ship(keySchema = """{"kinds":{"person":{"attrs":["status"]}},"actions":["task"]}""")
        run(ship) { db ->
            val scope = Json.parseToJsonElement(assertNotNull(ship.minted)).jsonObject["scope"]!!.jsonObject
            assertEquals(listOf("task", "message"), scope["actions"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("c2.secret", db.orreryAccounts().get("~zod")?.token)
            assertTrue("DELETE /apps/orrery/api/clients/c1" in ship.asked, ship.asked.toString())
        }
    }

    @Test
    fun `a key that covers the schema is kept, and not measured again for hours`() {
        val ship = Ship(keySchema = full)
        run(ship, passes = 2) { db ->
            assertEquals(null, ship.minted)
            assertEquals("c1.secret", db.orreryAccounts().get("~zod")?.token)
            assertEquals(1, ship.asked.count { it.endsWith("/api/schema") }, "the second pass trusts the first's measure")
        }
    }

    @Test
    fun `a key the ship will not read for is its owner's doing, and left alone`() {
        val ship = Ship(keySchema = "{}", stateStatus = HttpStatusCode.Forbidden)
        run(ship) { db ->
            assertEquals(null, ship.minted)
            assertEquals("c1.secret", db.orreryAccounts().get("~zod")?.token)
        }
    }

    @Test
    fun `a mint the ship refuses is not asked again on the next pass`() {
        val ship = Ship(keySchema = """{"kinds":{},"actions":[]}""", mintStatus = HttpStatusCode.InternalServerError)
        run(ship, passes = 2) { db ->
            assertEquals(1, ship.asked.count { it == "POST /apps/orrery/api/clients" })
            assertEquals("c1.secret", db.orreryAccounts().get("~zod")?.token)
        }
    }

    // Measured two hours ago: past the hour a blind kind waits, inside the
    // twelve the whole check waits, so only the pass's own filter runs.
    private val measuredEarlier: suspend (AppDatabase) -> Unit = { db ->
        val then = io.nisfeb.talon.util.nowMs() - 2 * 3_600_000L
        db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity("~zod", "scope:checked", then.toString(), then))
        db.contacts().upsert(io.nisfeb.talon.data.ContactEntity("~bus", "Bus", null, null))
    }

    @Test
    fun `a kind outside the owner's vocabulary is not written, and the pass goes on`() {
        val narrow = """{"kinds":{"note":{"attrs":[]}},"actions":[]}"""
        val ship = Ship(keySchema = narrow, ownerSchema = narrow)
        run(ship, seed = measuredEarlier) { db ->
            assertTrue(ship.observed.none { "person/bus" in it }, ship.observed.toString())
            assertEquals(null, ship.minted)
            assertNotNull(db.orrerySent().get("~zod", "scope:checked"), "nothing to measure again")
        }
    }

    @Test
    fun `a kind the owner has and the key cannot see fails the pass and asks for a key that can`() {
        val ship = Ship(keySchema = """{"kinds":{"note":{"attrs":[]}},"actions":[]}""")
        run(ship, seed = measuredEarlier) { db ->
            assertTrue(ship.observed.none { "person/bus" in it }, "no records for facts that never went")
            assertEquals(null, db.orrerySent().get("~zod", "scope:checked"), "measured again next pass")
        }
    }
}
