package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sun.net.httpserver.HttpServer
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.screens.AssistantScreen
import io.nisfeb.talon.ui.screens.AssistantSession
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The assistant screen against a model of our own: a local server that
 * speaks the OpenAI chat API, records what it is asked, and answers.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantScreenTest {
    /** Each request body the model was sent. */
    private val asked: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    @Volatile private var answer = "Tuesday, at the library."

    @Volatile private var status = 200
    /** A tool call the model makes before it answers, in the OpenAI wire shape. */
    @Volatile private var toolCall: String? = null

    private lateinit var ship: FakeShip

    /** Each calendar poke, when the screen is given a calendar. */
    private val calPokes: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** A calendar whose ship takes every write and shows every event added. */
    private fun calendarOn(scope: CoroutineScope): io.nisfeb.talon.calendar.CalendarRepo {
        fun io.ktor.client.engine.mock.MockRequestHandleScope.json(body: String) = respond(
            io.ktor.utils.io.ByteReadChannel(body), io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json"),
        )
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.startsWith("/grubbery/api/poke/") -> { calPokes += (req.body as io.ktor.http.content.TextContent).text; json("") }
                path.endsWith("/window.json") -> json(
                    calPokes.mapNotNull { Regex("\"name\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.mapIndexed { i, n ->
                        """{"id":"e$i","cal":"default","cat":"timed","kind":"once","all":false,"l":1790848800000,"r":1790852400000,"meta":{"name":"$n"}}"""
                    }.joinToString(",", "{\"rows\":[", "]}"),
                )
                path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"}]""")
                path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
                path.endsWith("/share/shares.json") -> json("""{"shares":{},"offers":{},"accepted":{}}""")
                path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
                path.endsWith(".json") -> json("[]")
                else -> respondError(io.ktor.http.HttpStatusCode.NotFound)
            }
        })
        return io.nisfeb.talon.calendar.CalendarRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
    }

    private fun assistant(withCalendar: Boolean = false, block: ComposeUiTest.(AppDatabase) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            asked += ex.requestBody.readBytes().decodeToString()
            // A tool call waiting to be made goes first, once; then the answer.
            val call = toolCall
            toolCall = null
            val body = (if (call != null) {
                """{"id":"x","object":"chat.completion","choices":[{"index":0,"finish_reason":"tool_calls",
                    "message":{"role":"assistant","content":null,"tool_calls":[$call]}}]}"""
            } else {
                """{"id":"x","object":"chat.completion","choices":[{"index":0,"finish_reason":"stop",
                    "message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(answer)}}}]}"""
            }).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            val out = if (status == 200) body else """{"error":{"message":"the model is down for maintenance"}}""".toByteArray()
            ex.sendResponseHeaders(status, out.size.toLong())
            ex.responseBody.use { it.write(out) }
            return@createContext
        }
        server.start()
        val ai = FakeAiSettings(AiSettings.Config(
            provider = AiSettings.Provider.Custom, apiKey = "k", model = "test-model",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", agentEnabled = true,
        ))
        val tmp = createTempDirectory(prefix = "talon-assistant-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        ship.channel.events().launchIn(sessionScope)
        val calendar = if (withCalendar) calendarOn(sessionScope) else null
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        AssistantScreen(
                            db = db, aiSettings = ai, embedder = null, onOpenMessage = { _, _, _ -> }, repo = repo,
                            session = AssistantSession(sessionScope), forceExpanded = true, calendar = calendar,
                        )
                    }
                }
                waitForIdle()
                block(db)
            }
        } finally {
            runBlocking { sessionScope.coroutineContext.job.cancelAndJoin() }
            server.stop(0)
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.ask(question: String) {
        onNode(hasSetTextAction()).performTextInput(question)
        onNodeWithText("Send").performClick()
    }

    @Test
    fun `a question goes to the model, its answer shows, and the conversation is kept`() = assistant { db ->
        assertTrue(shows("No conversations yet."))
        ask("when is the book club?")
        waitUntil(timeoutMillis = 10_000) { shows("Tuesday, at the library.") }
        val request = asked.first()
        assertTrue("when is the book club?" in request && "test-model" in request, request.take(400))
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.assistantConversations().mostRecent() } != null }
        assertTrue(!shows("No conversations yet."), "the conversation is listed")
    }

    @Test
    fun `a model that fails is said so, not answered for`() = assistant {
        status = 503
        ask("anything?")
        waitUntil(timeoutMillis = 10_000) { shows("down for maintenance") }
        assertTrue(!shows("Tuesday, at the library."))
    }

    @Test
    fun `a model that returns nothing is asked once more, then said so, with the question kept`() = assistant { db ->
        answer = ""
        ask("file this dump about Rose")
        waitUntil(timeoutMillis = 10_000) { shows("returned nothing, twice") }
        assertEquals(2, asked.size, "asked once more, not again and again")
        assertTrue(asked[1].contains("You returned an empty message"), "the second ask carries the nudge")
        assertTrue(shows("asking it once more"), "the transcript says why it asked again")
        assertTrue(!shows("(no reply)"), "a blank is not shown as an answer")
        onNode(hasSetTextAction()).assertTextContains("file this dump about Rose", substring = true)
        assertTrue(runBlocking { db.assistantConversations().mostRecent() } == null, "nothing is filed as an answer")
    }

    @Test
    fun `a turn keeps what its run did`() = assistant { db ->
        toolCall = postEvent
        answer = "Posted it to Bus."
        ask("tell bus about the seed swap")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Posted it to Bus.") }
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.assistantConversations().mostRecent() } != null }
        val conv = runBlocking { db.assistantConversations().mostRecent() }!!
        val turn = runBlocking { db.assistantHistory().forConversation(conv.id) }.single()
        val log = turn.log.lines()
        assertTrue(log.any { it.startsWith("→ send_event (write) args ") }, turn.log)
        assertTrue(log.any { Regex("✓ send_event \\d+ms, result \\d+ chars").matches(it) }, turn.log)
        assertTrue(log.contains("answer ${"Posted it to Bus.".length} chars"), turn.log)
    }

    @Test
    fun `a new conversation starts clear`() = assistant {
        ask("when is the book club?")
        waitUntil(timeoutMillis = 10_000) { shows("Tuesday, at the library.") }
        onNodeWithText("New conversation").performClick()
        waitUntil(timeoutMillis = 5_000) { !shows("Tuesday, at the library.") } // the transcript is cleared
        answer = "It is on Thursday now."
        ask("and the next one?")
        waitUntil(timeoutMillis = 10_000) { shows("It is on Thursday now.") }
        val last = asked.last()
        assertTrue("when is the book club?" !in last, "a new conversation carries none of the old: ${last.take(400)}")
    }

    // ─── what it does, and what it had said ───────────────────────

    private val postEvent = """{"id":"c1","type":"function","function":{"name":"send_event",""" +
        """"arguments":"{\"whom\":\"~bus\",\"name\":\"Seed swap\",\"date\":\"2026-10-03\"}"}}"""

    @Test
    fun `an action that writes asks first, and Deny does nothing`() = assistant {
        toolCall = postEvent
        answer = "All right, I won't post it."
        ask("tell bus about the seed swap")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        assertTrue(shows("send_event"))
        onNodeWithText("Deny").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("All right, I won't post it.") }
        assertTrue(shows("declined send_event"))
        assertTrue(ship.pokesTo("chat").isEmpty(), "nothing was posted")
    }

    @Test
    fun `an action allowed is done, and the answer follows`() = assistant {
        toolCall = postEvent
        answer = "Posted it to Bus."
        ask("tell bus about the seed swap")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Posted it to Bus.") }
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("chat").any { "Seed swap" in it.json.toString() } }
    }

    @Test
    fun `a past conversation opens with what was said`() = assistant {
        ask("when is the book club?")
        waitUntil(timeoutMillis = 10_000) { shows("Tuesday, at the library.") }
        onNodeWithText("New conversation").performClick()
        waitUntil(timeoutMillis = 5_000) { !shows("Tuesday, at the library.") }
        onAllNodesWithText("when is the book club?", substring = true)[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Tuesday, at the library.") }
    }

    // A batch of plain adds asked for one at a time, and slowly. Allow all
    // lets the rest of the answer's adds and edits through; a delete still
    // asks, and the next question starts asking again.
    private fun add(id: String, name: String) =
        """{"id":"$id","type":"function","function":{"name":"create_event","arguments":"{\"name\":\"$name\",\"date\":\"2026-10-01\",\"time\":\"10:00\"}"}}"""

    @Test
    fun `Allow all lets the rest of the answer's calendar adds through, and a delete still asks`() = assistant(withCalendar = true) {
        toolCall = listOf(
            add("c1", "Dentist"), add("c2", "Haircut"), add("c3", "Seed swap"),
            """{"id":"c4","type":"function","function":{"name":"delete_event","arguments":"{\"event\":\"e0\"}"}}""",
        ).joinToString(",")
        answer = "Added three; left the dentist."
        ask("add these three, and drop the dentist")
        waitUntil(timeoutMillis = 10_000) { shows("Allow all") }
        onNodeWithText("Allow all").performClick()
        // The other two go without asking, and say they were let through.
        waitUntil(timeoutMillis = 15_000) { shows("delete_event") && shows("Allow this action?") }
        assertTrue(!shows("Allow all lets"), "a delete is not one Allow all covers")
        assertEquals(3, calPokes.count { "add-event" in it }, calPokes.toString())
        assertEquals(2, onAllNodesWithText("allowed with Allow all", substring = true).fetchSemanticsNodes().size)
        onNodeWithText("Deny").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Added three; left the dentist.") }
        assertTrue(calPokes.none { "del-event" in it }, calPokes.toString())

        // The next answer asks again.
        toolCall = add("c5", "Picnic")
        answer = "Added the picnic."
        ask("and a picnic")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        assertTrue(calPokes.none { "Picnic" in it })
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Added the picnic.") }
    }
}

