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
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.OrreryAccountEntity
import io.nisfeb.talon.data.OrrerySentEntity
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who a contact is on the ship: the body that keeps their ship, else
 * one the ship keeps under their name, and only then a new person from
 * the @p. Made from the @p regardless, a person the owner keeps by name
 * got a twin every pass. And which failures make the pipe wait longer.
 */
class PassPeopleTest {
    @Test
    fun `a contact is the body with their ship, else the one with their name`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-people-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val observed = mutableListOf<String>()
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", nowMs().toString(), nowMs()))
            db.contacts().upsert(ContactEntity("~sampel-palnet", "Rosie", null, null))
            db.contacts().upsert(ContactEntity("~bus", "Sam Smith", null, null))
            db.contacts().upsert(ContactEntity("~nec", "Stranger", null, null))
            val state = """{"me":"person/me","rev":1,"bodies":[
                {"id":"person/rose","name":"Rose","ship":"~sampel-palnet"},
                {"id":"person/sam","name":"Sam Smith"}],
                "schema":{"kinds":{},"actions":[]}}"""
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    if ("/api/observe" in url) {
                        observed += (req.body as TextContent).text
                        return@MockEngine respond("""{"bodies":[],"observations":[]}""", headers = json)
                    }
                    val body = when {
                        "/apps/orrery/api/state" in url -> state
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = json)
                },
            )
            OrreryRepo(http, scope, db, "test", book = { setOf("~sampel-palnet", "~bus", "~nec") }, bareClient = http)
                .pass("https://ship.test", "~zod")
            val ids = observed.flatMap { Json.parseToJsonElement(it).jsonObject["bodies"]?.jsonArray.orEmpty() }
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
            assertTrue("person/rose" in ids, "by ship: $ids")
            // Sam is found by name and has nothing new to teach it but his
            // ship name, which is never sent: so nothing goes up for him, and
            // no twin. ~nec, found by neither, is made from the @p.
            assertTrue("person/nec" in ids, "neither: from the @p: $ids")
            assertFalse("person/sampel-palnet" in ids || "person/bus" in ids, "no twin, so Sam was found by name: $ids")
        } finally {
            scope.cancel()
            io.nisfeb.talon.urbit.closeAfter(db)
            dir.deleteRecursively()
        }
    }

    // Seen on a phone 2026-11-10 as "Orrery could not be read: not in
    // scope: aliases": from orrery 125 a key's batch with an alias the
    // ship reads as a ship is refused whole, and every pass sent it again.
    @Test
    fun `contacts go up under a key without a name the ship reads as a ship`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-pass-alias-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val taken = mutableListOf<String>()
        var refused = 0
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", nowMs().toString(), nowMs()))
            db.contacts().upsert(ContactEntity("~sampel-palnet", "Rosie", null, null))
            db.contacts().upsert(ContactEntity("~nec", null, null, null))
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val http = HttpClient(
                MockEngine { req ->
                    val url = req.url.toString()
                    if ("/api/observe" in url) {
                        val text = (req.body as TextContent).text
                        // orrery's +out-of-scope: (lien aliases ship-alias), the whole batch.
                        val shipNamed = Json.parseToJsonElement(text).jsonObject["bodies"]?.jsonArray.orEmpty().any { b ->
                            b.jsonObject["aliases"]?.jsonArray.orEmpty().any { it.jsonPrimitive.content.trim().startsWith("~") }
                        }
                        if (shipNamed) {
                            refused++
                            return@MockEngine respond("not in scope: aliases", HttpStatusCode.Forbidden)
                        }
                        taken += text
                        return@MockEngine respond("""{"bodies":[],"observations":[]}""", headers = json)
                    }
                    val body = when {
                        "/apps/orrery/api/state" in url -> """{"me":"person/me","rev":1,"bodies":[],"schema":{"kinds":{},"actions":[]}}"""
                        "/apps/calendar/window.json" in url -> """{"rows":[]}"""
                        else -> "[]"
                    }
                    respond(body, headers = json)
                },
            )
            OrreryRepo(http, scope, db, "test", book = { setOf("~sampel-palnet", "~nec") }, bareClient = http)
                .pass("https://ship.test", "~zod")
            assertEquals(0, refused, "no batch the ship refuses")
            val ids = taken.flatMap { Json.parseToJsonElement(it).jsonObject["bodies"]?.jsonArray.orEmpty() }
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
            // New to the ship, so each a person from the @p (see the test above).
            assertTrue(ids.containsAll(setOf("person/sampel-palnet", "person/nec")), "both went up: $ids")
        } finally {
            scope.cancel()
            io.nisfeb.talon.urbit.closeAfter(db)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `only a failure that could have cost the ship makes the pipe wait longer`() {
        assertTrue(OrreryRepo.costTheShip(OrreryError.Refused(500, "crash")))
        assertFalse(OrreryRepo.costTheShip(OrreryError.Refused(502, "bad gateway")), "a proxy with no ship behind it")
        assertFalse(OrreryRepo.costTheShip(OrreryError.Refused(503, "unavailable")))
        assertTrue(OrreryRepo.costTheShip(OrreryError.Unreachable(RuntimeException(io.ktor.client.network.sockets.SocketTimeoutException("busy")))), "a wait on its busy thread")
        assertFalse(OrreryRepo.costTheShip(OrreryError.Unreachable(java.net.ConnectException("refused"))))
        // A proxy's page is no answer now, not a refusal: the same rule by its status.
        assertFalse(OrreryRepo.costTheShip(OrreryError.Unreachable(ProxyAnswered(502))))
        assertFalse(OrreryRepo.costTheShip(OrreryError.Unreachable(ProxyAnswered(503))))
        assertTrue(OrreryRepo.costTheShip(OrreryError.Unreachable(ProxyAnswered(504))), "the proxy waited on a busy ship")
        assertTrue(OrreryRepo.costTheShip(IllegalStateException("ours")))
        assertFalse(OrreryRepo.costTheShip(java.net.ConnectException("refused")))
    }
}
