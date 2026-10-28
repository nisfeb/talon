package io.nisfeb.talon.ai

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
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Archie's brief_action reaching orrery through [VoiceConversation.briefAct]:
 * the status as the Actions screen answers it (done on a proposal goes
 * through approve), then the due (orrery 130 +serve-due), and the first
 * refusal said, with nothing after it sent.
 */
class BriefActTest {
    /** Each action's status on the ship; a1 is proposed, a2 approved. */
    private val status = ConcurrentHashMap(mapOf("a1" to "proposed", "a2" to "approved"))
    private val posted = CopyOnWriteArrayList<String>()
    @Volatile private var refuseStatus = false

    private fun ship(block: suspend (OrreryRepo) -> Unit) = runBlocking {
        val dir = createTempDirectory(prefix = "talon-brief-act-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val json = { code: HttpStatusCode, body: String -> respond(body, code, headersOf(HttpHeaders.ContentType, "application/json")) }
            when {
                path.startsWith("/apps/orrery/api/actions/") && req.method == HttpMethod.Post -> {
                    val body = req.body.toByteArray().decodeToString()
                    posted += "${path.removePrefix("/apps/orrery/api/actions/")} $body"
                    if (path.endsWith("/due")) return@MockEngine json(HttpStatusCode.OK, """{"ok":true,"id":"a2","due":"2026-10-30T00:00:00Z"}""")
                    if (refuseStatus) return@MockEngine json(HttpStatusCode.Conflict, """{"error":"not open"}""")
                    status[path.substringAfterLast('/')] = Regex("\"status\":\"(\\w+)\"").find(body)!!.groupValues[1]
                    json(HttpStatusCode.OK, "{}")
                }
                path == "/apps/orrery/api/actions" -> {
                    val want = req.url.parameters["status"] ?: "open"
                    val rows = status.entries.filter { (_, s) -> if (want == "open") s in OrreryRepo.OPEN_STATUSES else s == want }
                    json(HttpStatusCode.OK, rows.joinToString(",", "[", "]") { (id, s) -> """{"id":"$id","kind":"task","title":"Task $id","status":"$s"}""" })
                }
                else -> json(HttpStatusCode.OK, "{}")
            }
        })
        val repo = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        try {
            withTimeout(5_000) { while (repo.availability.value != OrreryAvailability.PRESENT) delay(20) }
            repo.refreshActions()
            withTimeout(5_000) { while (repo.actions.value.size < 2) delay(20) }
            block(repo)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `done on a proposal is approve then done, as the Actions screen answers it`() = ship { o ->
        assertNull(VoiceConversation.briefAct(o)("a1", "done", null))
        assertEquals(listOf("""a1 {"status":"approved"}""", """a1 {"status":"done"}"""), posted.toList())
    }

    @Test
    fun `a status then a due, each as orrery reads it`() = ship { o ->
        assertNull(VoiceConversation.briefAct(o)("a2", "dismissed", null))
        assertNull(VoiceConversation.briefAct(o)("a2", null, "2026-10-30"))
        assertNull(VoiceConversation.briefAct(o)("a2", null, "none"))
        assertEquals(
            listOf("""a2 {"status":"dismissed"}""", """a2/due {"due":"2026-10-30"}""", """a2/due {"due":null}"""),
            posted.map { it.replace(""","note":""""", "") },
        )
    }

    @Test
    fun `a status refused is said, and the due is not sent after it`() = ship { o ->
        refuseStatus = true
        val said = assertNotNull(VoiceConversation.briefAct(o)("a2", "approved", "2026-10-30"))
        assertTrue("not open" in said, said)
        assertTrue(posted.none { "/due" in it }, posted.toString())
    }
}
