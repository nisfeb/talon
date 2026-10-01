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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "I just quickly approved a bunch of facts in the actions pane and they
 * disappeared. I navigated back home and they all reappeared a few
 * seconds later", then were processed after all. Orrery applies an
 * answer after it replies; a list read meanwhile still has it open.
 */
class OrreryAnswersStayTest {
    /** What the ship has applied, by id: its status now. Unset is a proposal. */
    private val applied = ConcurrentHashMap<String, String>()
    /** Answers the ship has taken but not yet applied. */
    private val taken = ConcurrentHashMap<String, String>()
    @Volatile private var refuse = false

    private fun row(id: String, status: String) = """{"id":"$id","kind":"fact","title":"Fact $id","status":"$status"}"""

    private fun ship(block: suspend (OrreryRepo) -> Unit) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-answers-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val json = { status: HttpStatusCode, body: String -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
            when {
                path.startsWith("/apps/orrery/api/actions/") && req.method == HttpMethod.Post -> {
                    if (refuse) return@MockEngine json(HttpStatusCode.Conflict, """{"error":"no"}""")
                    val id = path.substringAfterLast('/')
                    val status = Regex("\"status\":\"(\\w+)\"").find(req.body.toByteArray().decodeToString())!!.groupValues[1]
                    taken[id] = status
                    json(HttpStatusCode.OK, "{}")
                }
                path == "/apps/orrery/api/actions" -> {
                    val want = req.url.parameters["status"] ?: "open"
                    val rows = listOf("a1", "a2", "a3").map { it to (applied[it] ?: "proposed") }
                        .filter { (_, s) -> if (want == "open") s in OrreryRepo.OPEN_STATUSES else s == want }
                    json(HttpStatusCode.OK, rows.joinToString(",", "[", "]") { (id, s) -> row(id, s) })
                }
                else -> json(HttpStatusCode.OK, "{}")
            }
        })
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        try {
            withTimeout(5_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(20) }
            repo.refreshActions()
            withTimeout(5_000) { while (repo.actions.value.size < 3) delay(20) }
            block(repo)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun OrreryRepo.statuses() = actions.value.associate { it.id to it.status }

    @Test
    fun `answers stay given while the ship's list has not caught up, and after`() = ship { repo ->
        repo.answer("a1", "approved")
        repo.answer("a2", "approved")
        repo.answer("a3", "dismissed")
        withTimeout(5_000) { while (taken.size < 3) delay(20) }
        // Home, the poll: a read before the ship has applied any of them.
        repo.refreshActions()
        assertEquals(mapOf("a1" to "approved", "a2" to "approved"), repo.statuses(), "none came back")
        // The ship applies them, and the next read agrees.
        taken.forEach { (id, s) -> applied[id] = s }
        repo.refreshActions()
        assertEquals(mapOf("a1" to "approved", "a2" to "approved"), repo.statuses())
    }

    @Test
    fun `once the ship has shown an answer, its own word wins again`() = ship { repo ->
        repo.answer("a1", "approved")
        withTimeout(5_000) { while (taken.isEmpty()) delay(20) }
        applied["a1"] = "approved"
        repo.refreshActions()
        // Later the ship sends it back to a proposal: that is the ship's to say.
        applied["a1"] = "proposed"
        repo.refreshActions()
        assertEquals("proposed", repo.statuses()["a1"])
    }

    @Test
    fun `an answer the ship refuses comes back at once, and stays back`() = ship { repo ->
        refuse = true
        repo.answer("a1", "approved")
        withTimeout(5_000) { while (repo.answerProblem.value == null) delay(20) }
        assertEquals("proposed", repo.statuses()["a1"])
        repo.refreshActions()
        assertEquals("proposed", repo.statuses()["a1"])
    }
}
