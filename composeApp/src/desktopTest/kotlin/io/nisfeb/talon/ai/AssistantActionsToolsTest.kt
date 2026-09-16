package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.ContactMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The assistant's mail/calendar write tools against a mock ship, in the
 * Room + MockEngine pattern of [ToolCatalogTest]. What is pinned here is
 * the guard rail, not the happy path: validation and the read-only /
 * ambiguity / missing-occurrence checks must short-circuit BEFORE any
 * poke, and a scoped update's two pokes must go in the safe order (add
 * the one-off first, then skip the original) with a partial failure
 * reported honestly.
 */
class AssistantActionsToolsTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-assistant-tools-test-").toFile()
        db = Room.databaseBuilder<AppDatabase>(name = File(tmpDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @AfterTest
    fun tearDown() {
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    private fun argsOf(vararg p: Pair<String, String>): JsonObject =
        buildJsonObject { p.forEach { (k, v) -> put(k, v) } }

    /** The tools under test plus every poke they made, in order. */
    private class Harness(
        val tools: List<Tool>,
        val pokes: MutableList<JsonObject>,
    ) {
        fun run(name: String, args: JsonObject): String = runBlocking {
            tools.first { it.spec.name == name }.execute(args)
        }
    }

    private fun withHarness(
        window: String = """{"rows":[]}""",
        calendars: String = """[{"id":"default","name":"Personal","kind":"local"}]""",
        events: String = """[]""",
        shares: String = """{"shares":{},"offers":{},"accepted":{}}""",
        event: String = """{}""",
        pokeSucceeds: (JsonObject) -> Boolean = { true },
        block: (Harness) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob())
        val pokes = mutableListOf<JsonObject>()
        fun MockRequestHandleScope.json(body: String) =
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        val http = HttpClient(
            MockEngine { req ->
                val path = req.url.encodedPath
                when {
                    path.startsWith("/grubbery/api/poke/") -> {
                        val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                        pokes += body
                        if (pokeSucceeds(body)) json("") else respondError(HttpStatusCode.InternalServerError, "refused")
                    }
                    path.endsWith("/window.json") -> json(window)
                    path.endsWith("/calendars.json") -> json(calendars)
                    path.endsWith("/events.json") -> json(events)
                    path.endsWith("/share/shares.json") -> json(shares)
                    path.endsWith("/google/conflicts.json") -> json("[]")
                    path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
                    path.endsWith("/caldav/subscriptions.json") -> json("[]")
                    path.endsWith("/tags.json") -> json("[]")
                    path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
                    path.endsWith("/event.json") -> json(event)
                    else -> respondError(HttpStatusCode.NotFound, """{"error":"not found"}""")
                }
            },
        )
        try {
            // A poll interval far past the test, so only explicit refreshes run.
            val calendar = CalendarRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L)
            calendar.attach("https://ship.example")
            // The poller's opening read races this one; both are gated on
            // the repo's mutex and read the same canned ship, and the tools
            // below only read the settled state.
            runBlocking { calendar.refresh() }
            val actions = AssistantActions(
                db = db,
                contacts = { ContactMap() },
                mail = MailRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L),
                calendar = calendar,
                zone = { TimeZone.UTC },
            )
            block(Harness(actionTools(actions), pokes))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a bad @p is rejected before anything is sent`() = withHarness { h ->
        val out = h.run("send_mail", argsOf("to" to "~zod, alice", "body" to "hi"))
        assertTrue(out.startsWith("Error: not ships: alice"), out)
        assertTrue(h.pokes.isEmpty(), "a rejected send must not poke: ${h.pokes}")

        val share = h.run("share_calendar", argsOf("calendar" to "default", "ship" to "alice"))
        assertTrue(share.startsWith("Error: ship must be an @p"), share)
        assertTrue(h.pokes.isEmpty(), "a rejected share must not poke: ${h.pokes}")
    }

    @Test
    fun `a write to a read-only shared calendar is rejected before any poke`() = withHarness(
        calendars = """[{"id":"default","name":"Personal","kind":"local"},{"id":"shared1","name":"Shared","kind":"ship"}]""",
        shares = """{"shares":{},"offers":{},"accepted":{"shared1":{"key":"~host/shared1","mode":"read","last_ms":0,"error":""}}}""",
    ) { h ->
        val out = h.run("create_event", argsOf("name" to "Dinner", "date" to "2026-09-20", "calendar" to "Shared"))
        assertTrue(out.startsWith("Error: calendar shared1 is shared with the user read-only"), out)
        assertTrue(h.pokes.isEmpty(), "a read-only refusal must not poke: ${h.pokes}")
    }

    @Test
    fun `an ambiguous task name asks which one and ticks nothing`() = withHarness(
        events = """[
            {"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"},"done":false},
            {"id":"t2","cal":"default","cat":"todo","meta":{"name":"Milk the cows"},"done":false}
        ]""",
    ) { h ->
        val out = h.run("complete_task", argsOf("task" to "milk"))
        assertTrue(out.startsWith("Several match; which one?"), out)
        assertTrue(out.contains("task=t1 Buy milk") && out.contains("task=t2 Milk the cows"), out)
        assertTrue(h.pokes.isEmpty(), "an ambiguous tick must not poke: ${h.pokes}")
    }

    @Test
    fun `an occurrence the event does not have is an error, not a write`() = withHarness(
        // Standup repeats weekly on Mondays 09:00 UTC; 2026-10-05 is not
        // one of its days in the canned window.
        window = """{"rows":[{"id":"r1","cal":"default","idx":0,"cat":"timed","kind":"weekly","all":false,"l":1789981200000,"r":1789983000000,"meta":{"name":"Standup"}}]}""",
        event = """{"cat":"timed","kind":"weekly","start_ms":1789948800000,"dur_min":30,"cal":"default","meta":{"name":"Standup"},"args":{"at":540,"days":["mon"]}}""",
    ) { h ->
        val out = h.run("update_event", argsOf("event" to "r1", "occurrence" to "2026-10-05", "name" to "Moved"))
        assertEquals("""Error: "Standup" has no occurrence on 2026-10-05.""", out)
        assertTrue(h.pokes.isEmpty(), "a missing occurrence must not poke: ${h.pokes}")
    }

    @Test
    fun `a scoped update adds the one-off before skipping the original, and a failed skip is reported`() = withHarness(
        window = """{"rows":[{"id":"r1","cal":"default","idx":0,"cat":"timed","kind":"weekly","all":false,"l":1789981200000,"r":1789983000000,"meta":{"name":"Standup"}}]}""",
        event = """{"cat":"timed","kind":"weekly","start_ms":1789948800000,"dur_min":30,"cal":"default","meta":{"name":"Standup"},"args":{"at":540,"days":["mon"]}}""",
        pokeSucceeds = { body -> body["action"]?.jsonPrimitive?.content != "skip-event" },
    ) { h ->
        val out = h.run("update_event", argsOf("event" to "r1", "occurrence" to "2026-09-21", "name" to "Standup (moved)"))
        // The order is the safety: skip-first would have LOST the
        // occurrence when the skip is the poke that fails here.
        assertEquals(listOf("add-event", "skip-event"), h.pokes.map { it["action"]!!.jsonPrimitive.content })
        assertEquals("Standup (moved)", h.pokes[0]["meta"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(out.startsWith("Half done"), out)
        assertTrue(out.contains("2026-09-21"), out)
    }
}
