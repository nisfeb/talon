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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This install's key on the ship's orrery: minted with everything the
 * ship's schema has, kept here and nowhere else, revoked and forgotten
 * when the pipe is turned off; and a revoke the ship will not do
 * leaves the key and the pipe as they were.
 */
class OrreryKeysTest {
    private val asked = CopyOnWriteArrayList<String>()
    @Volatile private var schemaAnswers = true
    @Volatile private var revokeStatus = 200

    private val schema = """{"kinds":{"person":{"attrs":["name"]},"garden":{"attrs":["plot"]}},"actions":["task","message"]}"""

    private fun keys(block: suspend (OrreryRepo, AppDatabase) -> Unit) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-orrery-keys-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            asked += "${req.method.value} ${req.url.encodedPath} ${req.body.toByteArray().decodeToString()}"
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                "/api/schema" in url ->
                    if (schemaAnswers) respond(schema, headers = json) else respond("down", HttpStatusCode.InternalServerError)
                "/api/clients" in url && req.method == HttpMethod.Post -> respond("""{"id":"c1","token":"k1.secret"}""", headers = json)
                "/api/clients/" in url && req.method == HttpMethod.Delete -> respond("", HttpStatusCode.fromValue(revokeStatus))
                "/api/state" in url -> respond("""{"me":"person/me","rev":1,"bodies":[],"schema":$schema}""", headers = json)
                else -> respond("[]", headers = json)
            }
        })
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        try {
            block(repo, db)
        } finally {
            scope.coroutineContext[Job]!!.let { it.cancel(); it.join() }
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun minted() = asked.single { it.startsWith("POST") && "/api/clients" in it }

    @Test
    fun `turning the pipe on mints a key with the ship's whole scope, and keeps it here`() = keys { repo, db ->
        assertTrue(repo.enable().isSuccess)
        val mint = minted()
        for (part in listOf("\"garden\"", "\"person\"", "\"task\"", "\"message\"", "\"sensitive\":\"write\"")) assertTrue(part in mint, "$part in $mint")
        assertEquals("k1.secret", db.orreryAccounts().get("~zod")?.token)
        assertTrue(repo.enabled.value)
    }

    @Test
    fun `a schema the ship will not give falls back to the built-in lists`() = keys { repo, _ ->
        schemaAnswers = false
        assertTrue(repo.enable().isSuccess)
        assertTrue(OrreryApi.KINDS.all { "\"$it\"" in minted() }, minted())
    }

    @Test
    fun `turning it off revokes the key on the ship and forgets it here`() = keys { repo, db ->
        repo.enable().getOrThrow()
        assertTrue(repo.disable().isSuccess)
        assertTrue(asked.any { it.startsWith("DELETE") && it.contains("/api/clients/c1") }, asked.toString())
        assertNull(db.orreryAccounts().get("~zod"))
        assertTrue(!repo.enabled.value)
    }

    @Test
    fun `a key the ship already dropped is forgotten all the same`() = keys { repo, db ->
        repo.enable().getOrThrow()
        revokeStatus = 404
        assertTrue(repo.disable().isSuccess)
        assertNull(db.orreryAccounts().get("~zod"))
    }

    @Test
    fun `a revoke the ship will not do leaves the key and the pipe on`() = keys { repo, db ->
        repo.enable().getOrThrow()
        revokeStatus = 500
        assertTrue(repo.disable().isFailure)
        assertNotNull(db.orreryAccounts().get("~zod"), "a key still good on the ship stays held")
        assertTrue(repo.enabled.value)
    }
}
