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
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.OrreryAccountEntity
import io.nisfeb.talon.data.OrrerySentEntity
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plan a status line fixes in time becomes a calendar proposal on the
 * ship: once, in the ship's own payload shape, and only where the ship
 * lists the calendar action at all.
 */
class OrreryProposalsTest {
    private val acts = CopyOnWriteArrayList<JsonObject>()
    private val starts = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs() + 3 * 86_400_000L).let {
        kotlinx.datetime.Instant.fromEpochSeconds(it.epochSeconds)
    }

    private fun state(actions: String, calendar: String = """{"title":"required","starts":"required; ISO 8601","location":"optional"}""") =
        """{"me":"person/me","rev":1,"bodies":[
            {"id":"person/rose","name":"Rose","ship":"~sampel-palnet"}],
            "schema":{"kinds":{},"actions":$actions,"payloads":{"calendar":$calendar}}}"""

    private val model = object : LocalModel {
        override val rung = "fake"
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int) =
            """{"claims":[],"plan":{"title":"Dinner at Luigi's","starts":"$starts","location":"Luigi's"}}"""
        override fun close() = Unit
    }

    /** Two passes over Rose's status against a ship answering [stateJson]. */
    private fun passes(stateJson: String) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-proposals-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        try {
            db.orreryAccounts().upsert(OrreryAccountEntity("~zod", "c1", "k1.secret"))
            db.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", nowMs().toString(), nowMs()))
            db.contacts().upsert(ContactEntity("~sampel-palnet", "Rose", null, null, status = "Dinner at Luigi's on Friday at 8", statusUpdatedMs = nowMs() - 60_000))
            val http = HttpClient(MockEngine { req ->
                val url = req.url.toString()
                val json = { body: String -> respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
                when {
                    "/api/act" in url -> {
                        acts += Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                        json("""{"id":"act${acts.size}","status":"proposed"}""")
                    }
                    "/api/observe" in url -> json("""{"bodies":[],"observations":[]}""")
                    "/apps/orrery/api/state" in url -> json(stateJson)
                    else -> json("[]")
                }
            })
            repeat(2) {
                // A fresh status each time, so the second pass reads it again.
                db.contacts().upsert(ContactEntity("~sampel-palnet", "Rose", null, null, status = "Dinner at Luigi's on Friday at 8", statusUpdatedMs = nowMs() - 60_000 + it))
                OrreryRepo(http, scope, db, "test", book = { setOf("~sampel-palnet") }, bareClient = http, readWith = model).pass("https://ship.test", "~zod")
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a plan in a status line is proposed to the calendar once, in the ship's shape`() {
        passes(state("""["calendar"]"""))
        val act = acts.single()
        assertEquals("calendar", act["kind"]!!.jsonPrimitive.content)
        val payload = act["payload"]!!.jsonObject
        assertEquals("Dinner at Luigi's" to starts.toString(), payload["title"]!!.jsonPrimitive.content to payload["starts"]!!.jsonPrimitive.content)
        assertEquals("Luigi's", payload["location"]!!.jsonPrimitive.content)
        assertTrue(act["about"]!!.jsonArray.any { it.jsonPrimitive.content == "person/rose" }, act.toString())
    }

    @Test
    fun `a ship with no calendar action, or a shape the plan does not fill, is proposed nothing`() {
        passes(state("[]"))
        assertTrue(acts.isEmpty(), "no calendar action listed")
        passes(state("""["calendar"]""", calendar = """{"title":"required","starts":"required; ISO 8601","ends":"required; ISO 8601"}"""))
        assertTrue(acts.isEmpty(), "the ship's shape wants an end the plan does not have")
    }
}
