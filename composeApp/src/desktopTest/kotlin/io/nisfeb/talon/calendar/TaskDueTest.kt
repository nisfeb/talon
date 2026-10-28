package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A todo's due moved from the evening review: an edit-event of the whole
 * todo, orrery's own as well. Orrery 130's mirror takes the calendar's due
 * for the action (+due-ops: the side that moved since wins), so there is
 * nothing to tell orrery.
 */
class TaskDueTest {
    private val day31 = 1_793_404_800_000L // 2026-10-31T00:00:00Z
    private val orrMeta = """{"name":"Call the dentist","orrery":"a1"}"""
    private val ownMeta = """{"name":"Buy milk"}"""

    /** The todos' dues as the calendar lists them; an edit-event the calendar takes moves them. */
    @Volatile private var orrDue: Long? = null
    @Volatile private var ownDue: Long? = null
    @Volatile private var pokeStatus = HttpStatusCode.OK
    /** Held open until completed, for leaving midway. */
    @Volatile private var hold: CompletableDeferred<Unit>? = null
    private val pokes = CopyOnWriteArrayList<String>()
    private val orrery = CopyOnWriteArrayList<String>()

    private fun todo(id: String, meta: String, due: Long?) =
        """{"id":"$id","cal":"default","cat":"todo","meta":$meta${due?.let { ",\"due_ms\":$it" } ?: ""}}"""

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        when {
            path.startsWith("/apps/orrery/") -> { orrery += path; json("{}") }
            path.startsWith("/grubbery/api/poke/") -> {
                val body = req.body.toByteArray().decodeToString()
                pokes += body
                hold?.await()
                val poke = Json.parseToJsonElement(body) as JsonObject
                if (pokeStatus == HttpStatusCode.OK && (poke["action"] as? JsonPrimitive)?.content == "edit-event") {
                    val due = (poke["due_ms"] as? JsonPrimitive)?.content?.toLong()
                    if ((poke["id"] as JsonPrimitive).content == "t-own") ownDue = due else orrDue = due
                }
                respond("", pokeStatus)
            }
            path.endsWith("/events.json") -> json("[${todo("t-orr", orrMeta, orrDue)},${todo("t-own", ownMeta, ownDue)}]")
            path.endsWith("/window.json") -> json("""{"rows":[]}""")
            path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"}]""")
            path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
            path.endsWith("/outcomes.json") -> json("""{"refusals":[],"faults":{}}""")
            else -> json("[]")
        }
    })

    private fun repo(block: suspend (CalendarRepo) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val repo = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L, readBackPauseMs = 20L).apply { attach("https://ship.test", poll = false) }
        repo.refresh()
        try { block(repo) } finally { scope.cancel() }
    }

    private fun CalendarRepo.task(id: String) = assertNotNull(tasks.value?.firstOrNull { it.id == id })
    private fun edits() = pokes.map { Json.parseToJsonElement(it) as JsonObject }.filter { (it["action"] as JsonPrimitive).content == "edit-event" }

    @Test
    fun `a todo is edited whole on the calendar with its new due`() = repo { repo ->
        assertNull(repo.setTaskDue(repo.task("t-own"), day31))
        // The calendar's edit-event replaces a todo whole: its cat and all its meta, and the due.
        assertEquals(
            listOf(buildJsonObject {
                put("action", "edit-event"); put("id", "t-own"); put("cat", "todo")
                put("meta", Json.parseToJsonElement(ownMeta)); put("due_ms", day31)
            }),
            edits(),
        )
        assertEquals(day31, repo.task("t-own").dueMs)
        pokes.clear()
        assertNull(repo.setTaskDue(repo.task("t-own"), null))
        assertTrue("due_ms" !in edits().single(), "no due is no due_ms: ${edits()}")
        assertNull(repo.task("t-own").dueMs)
    }

    @Test
    fun `orrery's todo is edited the same, its action's id kept in its meta, and orrery told nothing`() = repo { repo ->
        assertNull(repo.setTaskDue(repo.task("t-orr"), day31))
        val edit = edits().single()
        assertEquals("a1", ((edit["meta"] as JsonObject)["orrery"] as JsonPrimitive).content, "the mirror finds its action by it")
        assertEquals(day31, (edit["due_ms"] as JsonPrimitive).content.toLong())
        assertEquals(emptyList(), orrery.toList())
        assertEquals(day31, repo.task("t-orr").dueMs)
    }

    @Test
    fun `the calendar not taking it puts the due back and says so`() = repo { repo ->
        pokeStatus = HttpStatusCode.InternalServerError
        assertNotNull(repo.setTaskDue(repo.task("t-own"), day31))
        assertNull(repo.task("t-own").dueMs)
    }

    /** The screen's scope ends as the owner leaves, the ship still answering: the edit is the repo's and lands. */
    @Test
    fun `leaving midway, the edit still lands`() = repo { repo ->
        val gate = CompletableDeferred<Unit>().also { hold = it }
        val screen = CoroutineScope(SupervisorJob())
        screen.launch { repo.setTaskDue(repo.task("t-own"), day31) }
        withTimeout(5_000) { while (pokes.isEmpty()) kotlinx.coroutines.delay(10) }
        screen.cancel()
        gate.complete(Unit)
        withTimeout(5_000) { while (ownDue == null) kotlinx.coroutines.delay(10) }
        repo.refreshTasks()
        assertEquals(day31, repo.task("t-own").dueMs)
    }
}
