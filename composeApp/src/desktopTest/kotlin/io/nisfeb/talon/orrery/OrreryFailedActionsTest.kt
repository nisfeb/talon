package io.nisfeb.talon.orrery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An approved action the ship could not carry out. The ship marks it
 * failed with a note, and it leaves the open list like one done: Talon
 * showed nothing, and the owner never learned it had not happened.
 */
class OrreryFailedActionsTest {
    @Volatile private var open = """[${row("m1", "approved", "Tell Bus about lunch")}, ${row("t1", "approved", "Buy goggles")}]"""
    @Volatile private var failed = "[]"
    private val asked = CopyOnWriteArrayList<String>()

    private fun row(id: String, status: String, title: String, note: String = "", at: String = "2026-09-28T09:00:00Z") =
        """{"id":"$id","kind":"message","title":"$title","payload":{},"about":[],"due":null,"by":"generator",""" +
            """"proposed":"2026-09-27T09:00:00Z","status":"$status","note":"$note","history":[{"at":"$at","status":"$status","by":"executor"}]}"""

    @Test
    fun `an approved action that failed notifies with the ship's reason, and one done does not`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-failed-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { req ->
            val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val status = req.url.parameters["status"]
            if (status != null) asked += status
            when {
                req.url.encodedPath.endsWith("/api/actions") && status == "failed" -> json(failed)
                req.url.encodedPath.endsWith("/api/actions") -> json(open)
                else -> json("{}")
            }
        })
        val raised = CopyOnWriteArrayList<ActionNotification>()
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply {
            onActions = { raise, _ -> raised += raise }
            attach("https://ship.test", "~zod")
        }
        try {
            withTimeout(5_000) { while (repo.actions.value.size != 2) delay(20) }
            assertTrue("failed" !in asked, "nothing left, so nothing is asked")

            // The executor ran: the lunch message failed, the goggles were done.
            val now = java.time.Instant.now().toString()
            failed = """[${row("m1", "failed", "Tell Bus about lunch", note = "no DM with ~bus", at = now)}, ${row("x9", "failed", "Long ago", at = "2026-01-01T00:00:00Z")}]"""
            open = "[]"
            repo.refreshWaiting()
            withTimeout(5_000) { while (raised.isEmpty()) delay(20) }
            assertEquals(listOf(ActionNotification("failed:m1", "Did not go through: Tell Bus about lunch", "no DM with ~bus")), raised.toList())
            assertEquals(listOf("m1"), repo.failed.value.map { it.id }, "a failure from January is not news")
            assertEquals("no DM with ~bus", repo.failed.value.single().note)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }
}
