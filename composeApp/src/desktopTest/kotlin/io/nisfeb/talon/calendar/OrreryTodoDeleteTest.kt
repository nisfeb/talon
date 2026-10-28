package io.nisfeb.talon.calendar

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.AssistantActions
import io.nisfeb.talon.ai.actionTools
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.ContactMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A todo orrery placed (meta.orrery, its action's id) is taken away by
 * dismissing the action, and orrery takes the todo off the calendar. A
 * del-event alone was put back on orrery's next pass, the task being
 * still open: from the task list, and from Archie's delete_event.
 */
class OrreryTodoDeleteTest {
    private val orrTodo = """{"id":"t-orr","cal":"default","cat":"todo","meta":{"name":"Call the dentist","orrery":"a1"}}"""
    private val plainTodo = """{"id":"t-own","cal":"default","cat":"todo","meta":{"name":"Buy milk"}}"""

    /** What the calendar lists; orrery's mirror takes t-orr off once [mirrored]. */
    @Volatile private var mirrored = false
    /** How orrery answers a move of its action. */
    @Volatile private var orreryStatus = HttpStatusCode.OK
    private val orrery = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    private val pokes = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        when {
            path.startsWith("/apps/orrery/api/actions/") && req.method == HttpMethod.Post -> {
                orrery += path to req.body.toByteArray().decodeToString()
                respond("""{"ok":${orreryStatus == HttpStatusCode.OK}}""", orreryStatus, headersOf("Content-Type", "application/json"))
            }
            path.startsWith("/grubbery/api/poke/") -> { pokes += req.body.toByteArray().decodeToString(); json("") }
            path.endsWith("/events.json") -> json(if (mirrored) "[$plainTodo]" else "[$orrTodo,$plainTodo]")
            path.endsWith("/event.json") -> when (req.url.parameters["id"]) {
                "t-orr" -> json(orrTodo)
                "t-own" -> json(plainTodo)
                else -> respond("", HttpStatusCode.NotFound)
            }
            path.endsWith("/window.json") -> json("""{"rows":[]}""")
            path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"}]""")
            path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
            path.endsWith("/outcomes.json") -> json("""{"refusals":[],"faults":{}}""")
            else -> json("[]")
        }
    })

    private fun repo(read: Boolean = true, block: suspend (CalendarRepo) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val repo = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L, readBackPauseMs = 20L).apply { attach("https://ship.test", poll = false) }
        if (read) repo.refresh()
        try { block(repo) } finally { scope.cancel() }
    }

    private val dismissal = "/apps/orrery/api/actions/a1" to """{"status":"dismissed","note":"Removed from the calendar."}"""

    @Test
    fun `a todo orrery placed is dismissed on orrery, not deleted on the calendar`() = repo { repo ->
        val w = repo.writeEvent(deleteBody("t-orr"))
        assertTrue(w.ok)
        assertEquals(listOf(dismissal), orrery.toList())
        assertTrue(pokes.none { "del-event" in it }, pokes.toString())
        assertEquals(listOf("t-own"), repo.tasks.value?.map { it.id }, "off the list at once")
        // The calendar still lists it until orrery's mirror runs: a read then does not bring it back.
        w.shown.await()
        repo.refreshTasks()
        assertEquals(listOf("t-own"), repo.tasks.value?.map { it.id }, "kept off while orrery takes it away")
        mirrored = true
        repo.refreshTasks()
        assertEquals(listOf("t-own"), repo.tasks.value?.map { it.id })
    }

    @Test
    fun `a todo of the owner's own is deleted on the calendar as before`() = repo { repo ->
        assertTrue(repo.writeEvent(deleteBody("t-own")).ok)
        assertTrue(orrery.isEmpty())
        assertTrue(pokes.any { "del-event" in it && "t-own" in it }, pokes.toString())
    }

    @Test
    fun `an action already gone or closed leaves the calendar to delete the todo`() {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Conflict)) {
            orrery.clear(); pokes.clear(); orreryStatus = status
            repo { repo ->
                assertTrue(repo.writeEvent(deleteBody("t-orr")).ok, "$status")
                assertEquals(listOf(dismissal), orrery.toList())
                assertTrue(pokes.any { "del-event" in it && "t-orr" in it }, "$status: $pokes")
            }
        }
    }

    @Test
    fun `orrery not taking it keeps the task and says why`() = repo { repo ->
        orreryStatus = HttpStatusCode.InternalServerError
        val w = repo.writeEvent(deleteBody("t-orr"))
        assertFalse(w.ok)
        assertTrue(pokes.none { "del-event" in it }, "not deleted behind orrery's back")
        assertEquals(setOf("t-orr", "t-own"), repo.tasks.value?.map { it.id }?.toSet(), "back on the list")
        val why = assertNotNull(w.why)
        assertTrue("orrery did not take it" in why, why)
        assertEquals(why, repo.unheard.value)
    }

    /** At a cold start the list is not read yet: the todo's own record says whose it is. */
    @Test
    fun `before the list is read, the todo itself says it is orrery's`() = repo(read = false) { repo ->
        assertEquals(null, repo.tasks.value)
        assertTrue(repo.writeEvent(deleteBody("t-orr")).ok)
        assertEquals(listOf(dismissal), orrery.toList())
        assertTrue(pokes.none { "del-event" in it })
    }

    @Test
    fun `Archie's delete_event dismisses orrery's todo too`() {
        val tmp = createTempDirectory(prefix = "talon-orr-del-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        try {
            repo { repo ->
                val scope = CoroutineScope(SupervisorJob())
                val actions = AssistantActions(
                    db = db, contacts = { ContactMap() },
                    mail = MailRepo(http, scope, pollIntervalMs = 60 * 60_000L),
                    calendar = repo, zone = { TimeZone.UTC },
                )
                val said = actionTools(actions).first { it.spec.name == "delete_event" }.execute(buildJsonObject { put("event", "t-orr") })
                assertEquals("Deleted \"Call the dentist\".", said)
                assertEquals(listOf(dismissal), orrery.toList())
                assertTrue(pokes.none { "del-event" in it })
                scope.cancel()
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }
}
