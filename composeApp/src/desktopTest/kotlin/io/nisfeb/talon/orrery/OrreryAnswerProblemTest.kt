package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A refused answer stays said until the next answer: in the pipe's
 * error it went with the next pass or probe, often before the person
 * who tapped Approve had looked up. And a probe clears only a failure
 * of its own.
 */
class OrreryAnswerProblemTest {
    @Volatile private var probeStatus = HttpStatusCode.OK

    @Test
    fun `a refused answer outlasts a probe, and a probe clears only its own failure`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-answer-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val json = { status: HttpStatusCode, body: String -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
            when {
                path.startsWith("/apps/orrery/api/actions/") && req.method == HttpMethod.Post -> json(HttpStatusCode.Conflict, """{"error":"already answered"}""")
                path == "/apps/orrery/api/state" -> json(probeStatus, "{}")
                else -> json(HttpStatusCode.OK, "[]")
            }
        })
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        try {
            withTimeout(5_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(20) }
            repo.answer("a1", "approved")
            withTimeout(5_000) { while (repo.answerProblem.value == null) delay(20) }
            val refused = repo.answerProblem.value!!
            assertTrue(refused.startsWith("Orrery did not take that answer") && "already answered" in refused, refused)
            repo.probe()
            assertEquals(refused, repo.answerProblem.value, "a probe is not an answer")

            probeStatus = HttpStatusCode.InternalServerError
            repo.probe()
            assertEquals(OrreryAvailability.UNKNOWN, repo.availability.value)
            val failed = repo.error.value
            probeStatus = HttpStatusCode.OK
            repo.probe()
            assertNull(repo.error.value, "its own failure ($failed) is the probe's to clear")
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }
}
