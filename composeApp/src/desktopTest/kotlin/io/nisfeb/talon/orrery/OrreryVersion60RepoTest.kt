package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrrerySentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the repo does with orrery 60's instruction route and schema step. */
class OrreryVersion60RepoTest {
    @Volatile private var open = "[]"
    private val schema = """{"style":"Short.","actions":["task"],"payloads":{},"kinds":{"person":{"attrs":["name"],"notes":{}}},"multi":[]}"""
    private val asked = CopyOnWriteArrayList<String>()
    private val put = CopyOnWriteArrayList<String>()

    private fun proposal(id: String, title: String) =
        """{"id":"$id","kind":"merge","title":"$title","payload":{"from":"person/samuel","into":"person/sam"},"about":[],"status":"proposed","by":"owner"}"""

    private fun attached(seed: suspend (AppDatabase) -> Unit = {}, block: suspend (OrreryRepo, AppDatabase, MutableList<ActionNotification>) -> Unit) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-orrery60-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        seed(db)
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath.substringAfter("/apps/orrery/api/")
            asked += "${req.method.value} $path"
            val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            when {
                path == "instruct" -> json("""{"ok":true,"reply":"They look like one person.","actions":[${proposal("p9", "Fold Samuel into Sam")}],"note":""}""")
                path == "actions" -> json(open)
                path == "schema" && req.method == HttpMethod.Put -> req.body.toByteArray().decodeToString().let { put += it; json(it) }
                path == "schema" -> json(schema)
                else -> json("{}")
            }
        })
        val raised = CopyOnWriteArrayList<ActionNotification>()
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply {
            onActions = { raise, _ -> raised += raise }
            attach("https://ship.test", "~zod")
        }
        try {
            withTimeout(5_000) { while ("GET actions" !in asked) delay(20) }
            block(repo, db, raised)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }

    // The owner asked for it, so it is not news: a notification for it
    // arrived while they were still reading the ship's reply.
    @Test
    fun `what Tell Orrery files shows at once, and is not raised as news`() = attached { repo, _, raised ->
        val said = repo.instruct("Sam and Samuel are one person", "a1").getOrThrow()
        assertEquals("They look like one person.", said.reply)
        assertEquals(listOf("p9"), repo.actions.value.map { it.id }, "in the list before the next read")
        open = "[${proposal("p9", "Fold Samuel into Sam")}, ${proposal("p10", "Something else")}]"
        repo.refreshWaiting()
        assertEquals(listOf("p10"), raised.map { it.id }, "only what the owner did not ask for")
    }

    // A key minted before the new kinds cannot see a proposal made of
    // them: once the schema lists them, the next pass measures again.
    @Test
    fun `the schema step writes the whole schema and has the key measured again`() = attached(
        seed = { it.orrerySent().put(OrrerySentEntity("~zod", "scope:checked", "1", 1L)) },
    ) { repo, db, _ ->
        repo.addVersion60Schema().getOrThrow()
        assertEquals(withVersion60(Json.parseToJsonElement(schema).jsonObject).first, Json.parseToJsonElement(put.single()).jsonObject)
        assertEquals(null, db.orrerySent().get("~zod", "scope:checked"))
        assertTrue(withVersion60(repo.schema.value!!).second.isEmpty(), "the page reads the answer, which needs nothing more")
    }
}
