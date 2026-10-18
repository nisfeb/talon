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
    /** From this request on, the model holds its answer until [stall] is let go: a stalled call. */
    @Volatile private var stallFrom = Int.MAX_VALUE
    /** From this request on, the model fails (503): a provider going down mid-run. */
    @Volatile private var failFrom = Int.MAX_VALUE
    private val stall = java.util.concurrent.CountDownLatch(1)
    /** The screen is shown; off, it is left, and its run goes on in the session. */
    private val showing = androidx.compose.runtime.mutableStateOf(true)
    /** A tool call the model makes before it answers, in the OpenAI wire shape. */
    @Volatile private var toolCall: String? = null

    private lateinit var ship: FakeShip

    /** Each calendar poke, when the screen is given a calendar. */
    private val calPokes: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** Rows the calendar's window holds besides the events added, as the ship sends them. */
    @Volatile private var calRows: List<String> = emptyList()

    /** Where a card's taps went: "chat ~bus", "calendar". */
    private val opened: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** The inbox the mail app answers with. */
    @Volatile private var inbox = """{"total":0,"offset":0,"limit":50,"view":"inbox","threads":[]}"""

    /** A mail app whose ship answers every listing with [inbox]. */
    private fun mailOn(scope: CoroutineScope): io.nisfeb.talon.mail.MailRepo {
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
            if (req.url.encodedPath.endsWith("/api/inbox")) {
                respond(io.ktor.utils.io.ByteReadChannel(inbox), io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json"))
            } else {
                respondError(io.ktor.http.HttpStatusCode.NotFound)
            }
        })
        return io.nisfeb.talon.mail.MailRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
    }

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
                    (calRows + calPokes.mapNotNull { Regex("\"name\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.mapIndexed { i, n ->
                        """{"id":"e$i","cal":"default","cat":"timed","kind":"once","all":false,"l":1790848800000,"r":1790852400000,"meta":{"name":"$n"}}"""
                    }).joinToString(",", "{\"rows\":[", "]}"),
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

    private fun assistant(withCalendar: Boolean = false, withMail: Boolean = false, block: ComposeUiTest.(AppDatabase) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            asked += ex.requestBody.readBytes().decodeToString()
            if (asked.size >= stallFrom) stall.await(30, java.util.concurrent.TimeUnit.SECONDS)
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
            val status = if (asked.size >= failFrom) 503 else status
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
        val mail = if (withMail) mailOn(sessionScope) else null
        // The host's, as the shells keep it: it outlives the screen.
        val session = AssistantSession(sessionScope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        if (showing.value) AssistantScreen(
                            db = db, aiSettings = ai, embedder = null, onOpenMessage = { _, _, _ -> }, repo = repo,
                            session = session, forceExpanded = true, calendar = calendar, mail = mail,
                            onOpenChat = { opened += "chat $it" }, onOpenCalendar = { opened += "calendar" },
                        )
                    }
                }
                waitForIdle()
                block(db)
            }
        } finally {
            stall.countDown()
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
        // A failing provider in calm words (B2-3); its own line is in the details.
        waitUntil(timeoutMillis = 10_000) { shows("the model's provider is having trouble") }
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

    // The card named the recipient by nickname only.
    @Test
    fun `the card names whom it goes to by name and ship`() = assistant { db ->
        runBlocking { db.contacts().upsert(io.nisfeb.talon.data.ContactEntity("~bus", "Bus", null, null)) }
        toolCall = postEvent
        ask("tell bus about the seed swap")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        assertTrue(shows("whom: Bus (~bus)"), "name and ship")
        onNodeWithText("Deny").performClick()
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

    // ─── the card under the answer ────────────────────────────────

    private fun call(id: String, name: String, args: String = "{}") =
        """{"id":"$id","type":"function","function":{"name":"$name","arguments":${kotlinx.serialization.json.JsonPrimitive(args)}}}"""

    // sneagan, 2026-10-18: "can the text assistant do everything the voice
    // assistant can including the nice card ui?" The rows a run read are
    // drawn under its answer as the dictation card draws them.
    @Test
    fun `what a run read is drawn under its answer and each row opens where it lives`() = assistant(withCalendar = true, withMail = true) { db ->
        val now = System.currentTimeMillis()
        runBlocking {
            db.contacts().upsert(io.nisfeb.talon.data.ContactEntity("~bus", "Bus", null, null))
            db.messages().upsert(io.nisfeb.talon.data.MessageEntity("~bus", "170.1", "~bus", now - 60_000, """[{"inline":["Seeds are in the shed"]}]""", "/chat"))
        }
        calRows = listOf("""{"id":"d1","cal":"default","cat":"timed","kind":"once","all":false,"l":${now - 60_000},"r":${now + 3_600_000},"meta":{"name":"Seed swap"}}""")
        inbox = """{"total":1,"offset":0,"limit":50,"view":"inbox","threads":[
            {"id":"0v1","subject":"Seed catalogue","from":"~nec","snippet":"The spring list","verdict":"verified",
             "forged":false,"count":1,"last":$now,"unread":true,"participants":[],
             "unreadable":0,"archived":false,"labels":[]}]}"""
        toolCall = listOf(
            call("c1", "find_person", """{"name":"Bus"}"""),
            call("c2", "list_events"),
            call("c3", "read_conversation", """{"whom":"~bus"}"""),
            call("c4", "list_mail"),
        ).joinToString(",")
        answer = "Here is what I found."
        ask("anything about the swap?")
        waitUntil(timeoutMillis = 15_000) { shows("Here is what I found.") && shows("Seed catalogue") }
        // The person by name and ship, the day's event, the message and the mail.
        assertTrue(shows("~bus") && shows("Today") && shows("Seed swap") && shows("Seeds are in the shed"))
        assertTrue(shows("Messages") && shows("Mail") && shows("The spring list"))
        // Under the answer, not over it.
        val answerTop = onNodeWithText("Here is what I found.").fetchSemanticsNode().boundsInRoot.top
        assertTrue(answerTop < onNodeWithText("Seed swap").fetchSemanticsNode().boundsInRoot.top)
        onNodeWithText("~bus").performClick()
        onNodeWithText("Seed swap").performClick()
        assertEquals(listOf("chat ~bus", "calendar"), opened.toList())

        // The next run reads nothing, and shows no card of the last one's.
        answer = "Nothing else."
        ask("thanks")
        waitUntil(timeoutMillis = 10_000) { shows("Nothing else.") }
        assertEquals(1, onAllNodesWithText("Seed catalogue").fetchSemanticsNodes().size)
    }

    // ─── Stop ──────────────────────────────────────────────────────

    // A stalled model call held a run for up to 11 minutes a step, and
    // nothing ended it. On real time: the session runs on Dispatchers.Default.
    @Test
    fun `Stop ends a stalled run at once and gives the question back`() = assistant { db ->
        stallFrom = 1
        ask("when is the book club?")
        waitUntil(timeoutMillis = 5_000) { asked.size == 1 && shows("Stop") }
        onNodeWithText("Stop").performClick()
        // The model has still not answered: the run ended without it.
        waitUntil(timeoutMillis = 2_000) { shows("Stopped.") }
        assertEquals(1L, stall.count, "the model call is still held")
        onNode(hasSetTextAction()).assertTextContains("when is the book club?")
        assertTrue(!shows("It may have done part of it"))
        assertTrue(runBlocking { db.assistantConversations().mostRecent() } == null, "nothing is kept")
        // The box is usable: the same question goes again and is answered.
        stall.countDown()
        onNodeWithText("Send").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Tuesday, at the library.") }
    }

    @Test
    fun `Stop after a write says it may have done part of it and keeps the turn`() = assistant { db ->
        toolCall = postEvent
        stallFrom = 2
        ask("tell bus about the seed swap")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 10_000) { ship.pokesTo("chat").isNotEmpty() && asked.size == 2 }
        onNodeWithText("Stop").performClick()
        waitUntil(timeoutMillis = 2_000) { shows("It may have done part of it") }
        // Not put back: sent again, it would post twice.
        assertEquals("", onNode(hasSetTextAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.assistantConversations().mostRecent() } != null }
        val conv = runBlocking { db.assistantConversations().mostRecent() }!!
        val turn = runBlocking { db.assistantHistory().forConversation(conv.id) }.single()
        assertEquals("tell bus about the seed swap", turn.question)
        assertEquals(io.nisfeb.talon.ui.screens.STOPPED_AFTER_WRITE, turn.answer)
        assertTrue(turn.log.lines().any { it.startsWith("→ send_event (write) args ") }, turn.log)
        assertTrue(turn.log.lines().contains("stopped by the owner"), turn.log)
    }

    // B2-15: the model fails after the owner allowed a send. The turn is
    // kept with its log and the question is not put back: sent again, it
    // would post twice.
    @Test
    fun `a run that fails after a write keeps the turn and not the question`() = assistant { db ->
        toolCall = postEvent
        failFrom = 2
        ask("tell bus about the seed swap")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 15_000) { shows("It may have done part of it") }
        assertTrue(ship.pokesTo("chat").isNotEmpty(), "the send went")
        assertEquals("", onNode(hasSetTextAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.assistantConversations().mostRecent() } != null }
        val conv = runBlocking { db.assistantConversations().mostRecent() }!!
        val turn = runBlocking { db.assistantHistory().forConversation(conv.id) }.single()
        assertEquals(io.nisfeb.talon.ui.screens.FAILED_AFTER_WRITE, turn.answer)
        assertTrue(turn.log.lines().any { it.startsWith("→ send_event (write) args ") }, turn.log)
        assertTrue(turn.log.lines().any { it.startsWith("failed: ") }, turn.log)
    }

    // The run lives on the host's scope, so leaving did not end it; the
    // Stop has to reach it from the screen opened again.
    @Test
    fun `Stop reaches a run after the screen is left and opened again`() = assistant { db ->
        stallFrom = 1
        ask("when is the book club?")
        waitUntil(timeoutMillis = 5_000) { asked.size == 1 }
        showing.value = false
        waitUntil(timeoutMillis = 5_000) { !shows("Stop") }
        showing.value = true
        waitUntil(timeoutMillis = 5_000) { shows("Stop") }
        onNodeWithText("Stop").performClick()
        waitUntil(timeoutMillis = 2_000) { shows("Stopped.") }
        assertEquals(1L, stall.count, "the model call is still held")
        onNode(hasSetTextAction()).assertTextContains("when is the book club?")
        assertTrue(!shows("Working…"), "the box is free")
        assertTrue(runBlocking { db.assistantConversations().mostRecent() } == null)
    }
}

