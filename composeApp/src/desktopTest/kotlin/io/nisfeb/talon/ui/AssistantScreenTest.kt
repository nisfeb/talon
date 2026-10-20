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
import io.nisfeb.talon.ai.AgentPrompt
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
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

    /** The calendar's zone. */
    @Volatile private var calZone = "UTC"

    /** The calendar the screen was given, when it was given one. */
    @Volatile private var calRepo: io.nisfeb.talon.calendar.CalendarRepo? = null

    /** Today's brief, as orrery's ship answers /api/brief/last. */
    @Volatile private var brief = "{}"

    /** A zone where it is about noon now, so "later today" stays today. */
    private fun noonZone(): String {
        val h = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).hour
        val off = (12 - h).let { if (it > 14) it - 24 else if (it < -12) it + 24 else it }
        return "UTC" + (if (off >= 0) "+" else "-") + kotlin.math.abs(off).toString().padStart(2, '0') + ":00"
    }

    /** Where a card's taps went: "chat ~bus", "calendar". */
    private val opened: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** The inbox the mail app answers with. */
    @Volatile private var inbox = """{"total":0,"offset":0,"limit":50,"view":"inbox","threads":[]}"""

    /** Each POST orrery's ship was sent: its path and body. */
    private val orreryPosts: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()

    /** What orrery's ship answers an instruct with. */
    @Volatile private var instructed = """{"ok":true,"reply":"","actions":[],"note":""}"""

    /** How long orrery's ship takes to answer an instruct. */
    @Volatile private var holdInstruct = 0L

    /** The host's session, for its dot. */
    private lateinit var session: AssistantSession

    /** How orrery's ship answers the owner's word on an action. */
    @Volatile private var answerStatus = io.ktor.http.HttpStatusCode.OK

    /** What orrery's ship answers each read of a body, in turn, and the last again once they run out. */
    private val bodies = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    /** What orrery's ship answers each read of what is open, in turn, and the last again. */
    private val openLists: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf("[]"))

    /** Each POST's path and whether it carried this install's key. */
    private val orreryKeyed: MutableList<Pair<String, Boolean>> = java.util.concurrent.CopyOnWriteArrayList()

    /** How orrery's ship answers the owner's word on an action, and an observe. */
    @Volatile private var answerBody = """{"ok":true}"""
    @Volatile private var observeAnswer = """{"bodies":[],"observations":[{"id":"1-a","ok":true,"existing":false}]}"""

    /** An observe the ship never answers. */
    @Volatile private var observeDown = false

    /** What GET /api/watches answers; null, an orrery older than watches. */
    @Volatile private var watchesDoc: String? = null

    /** Each read of the watches, and whether it carried this install's key. */
    private val watchReads: MutableList<Boolean> = java.util.concurrent.CopyOnWriteArrayList()

    private fun <T> MutableList<T>.next(): T = synchronized(this) { if (size > 1) removeAt(0) else single() }

    /** Lattice on the ship (sneagan, 2026-10-19: memory parity with Claude Code): what key-mint answers, and what each key route answers. */
    @Volatile private var latticeMint: Pair<io.ktor.http.HttpStatusCode, String> = io.ktor.http.HttpStatusCode.OK to """{"key":{"id":"k1"},"token":"k1.s3cret"}"""
    @Volatile private var latticeAnswer: (String) -> Pair<io.ktor.http.HttpStatusCode, String> = { route ->
        when (route) {
            "know-index" -> io.ktor.http.HttpStatusCode.OK to "# Lattice memory\n\n## /feedback/say-the-ship\nAlways name the ship."
            "know-recall" -> io.ktor.http.HttpStatusCode.OK to """{"recall":{"weak":false,"results":[{"key":"/user/trips","strength":80,"score":20000,"snippet":"Flies Delta out of JAX."}]}}"""
            else -> io.ktor.http.HttpStatusCode.OK to """{"ok":true}"""
        }
    }
    /** Each request to lattice on the owner's login (which also carries the ship's MCP), and each with the key. */
    private val latticeOwned: MutableList<io.ktor.client.request.HttpRequestData> = java.util.concurrent.CopyOnWriteArrayList()
    private val latticeKeyed: MutableList<io.ktor.client.request.HttpRequestData> = java.util.concurrent.CopyOnWriteArrayList()
    private val latticeOwner = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
        if ("/apps/lattice/" in req.url.encodedPath) latticeOwned += req
        respond(latticeMint.second, latticeMint.first, io.ktor.http.headersOf("Content-Type", "application/json"))
    })
    private val latticeBare = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
        latticeKeyed += req
        val (status, body) = latticeAnswer(req.url.encodedPath.substringAfterLast('/'))
        respond(body, status, io.ktor.http.headersOf("Content-Type", "application/json"))
    })

    /** The orrery the screen was given. */
    private lateinit var orrery: io.nisfeb.talon.orrery.OrreryRepo

    /** Orrery on a ship of its own, built as the Actions tests build it, with this install's key. */
    private fun orreryOn(scope: CoroutineScope, db: AppDatabase): io.nisfeb.talon.orrery.OrreryRepo {
        runBlocking {
            db.orreryAccounts().upsert(io.nisfeb.talon.data.OrreryAccountEntity("~zod", "c1", "k1.secret"))
            // The key's scope counts as checked, so the pipe asks nothing of it.
            db.orrerySent().put(io.nisfeb.talon.data.OrrerySentEntity("~zod", "scope:checked", io.nisfeb.talon.util.nowMs().toString(), io.nisfeb.talon.util.nowMs()))
        }
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
            val path = req.url.encodedPath
            val json = io.ktor.http.headersOf("Content-Type", "application/json")
            if (req.method == io.ktor.http.HttpMethod.Post) {
                orreryPosts += path to req.body.toByteArray().decodeToString()
                orreryKeyed += path to (req.headers[io.ktor.http.HttpHeaders.Authorization] != null)
            }
            when {
                path.endsWith("/api/instruct") -> { kotlinx.coroutines.delay(holdInstruct); respond(instructed, io.ktor.http.HttpStatusCode.OK, json) }
                path.endsWith("/api/brief/last") -> respond(brief, io.ktor.http.HttpStatusCode.OK, json)
                path.endsWith("/api/version") -> respond("""{"version":110}""", io.ktor.http.HttpStatusCode.OK, json)
                req.method == io.ktor.http.HttpMethod.Post && "/api/actions/" in path -> respond(answerBody, answerStatus, json)
                // What is open, in turn; a look at one status sees none.
                path.endsWith("/api/actions") ->
                    respond(if (req.url.parameters["status"] == "open") openLists.next() else "[]", io.ktor.http.HttpStatusCode.OK, json)
                "/api/body/" in path -> bodies[path.substringAfter("/api/body/")]?.let { respond(it.next(), io.ktor.http.HttpStatusCode.OK, json) }
                    ?: respond("""{"error":"no such body"}""", io.ktor.http.HttpStatusCode.NotFound, json)
                path.endsWith("/api/observe") -> if (observeDown) throw java.io.IOException("connection reset") else respond(observeAnswer, io.ktor.http.HttpStatusCode.OK, json)
                path.endsWith("/api/retract") -> respond("""{"ok":true}""", io.ktor.http.HttpStatusCode.OK, json)
                path.endsWith("/api/watches") -> {
                    watchReads += req.headers[io.ktor.http.HttpHeaders.Authorization] != null
                    watchesDoc?.let { respond(it, io.ktor.http.HttpStatusCode.OK, json) } ?: respond("""{"error":"not found"}""", io.ktor.http.HttpStatusCode.NotFound, json)
                }
                else -> respond("", io.ktor.http.HttpStatusCode.NotFound)
            }
        })
        return io.nisfeb.talon.orrery.OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
    }

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
                path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"$calZone","ball":"abc123"}""")
                path.endsWith("/share/shares.json") -> json("""{"shares":{},"offers":{},"accepted":{}}""")
                path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
                path.endsWith(".json") -> json("[]")
                else -> respondError(io.ktor.http.HttpStatusCode.NotFound)
            }
        })
        return io.nisfeb.talon.calendar.CalendarRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
    }

    /** What the decision model was asked, when it is on. */
    private val decided: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** The decision model's answer, as the vendor's route gives it. */
    private fun decision(calendarOnly: Double, days: String) =
        """{"answers":{"calendar_only":{"noul":$calendarOnly},"days":{"choice":"$days","probabilities":{"$days":0.9}}},"usage":{"input_tokens":400,"cost":0.0001}}"""

    /** With [decisions], the vendor's decision model is on and answers so, and the assistant's model is the vendor's row. */
    private fun assistant(withCalendar: Boolean = false, withMail: Boolean = false, withOrrery: Boolean = false, decisions: String? = null, withLattice: Boolean = false, seed: suspend AppDatabase.() -> Unit = {}, block: ComposeUiTest.(AppDatabase) -> Unit) {
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
        val base = "http://127.0.0.1:${server.address.port}/v1"
        val ai = FakeAiSettings(if (decisions == null) {
            AiSettings.Config(provider = AiSettings.Provider.Custom, apiKey = "k", model = "test-model", baseUrl = base, agentEnabled = true)
        } else {
            io.nisfeb.talon.ai.paidSettings(row = io.nisfeb.talon.ai.armillaryRow(base = base, suggested = mapOf("default" to "test-model", "decision" to "typesafe/jev-1.13")))
                .let { it.copy(savedProfile = it.savedProfile!!.copy(jev = true)) }
        })
        val decisionHttp = decisions?.let { said ->
            io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
                decided += req.body.toByteArray().decodeToString()
                respond(said, io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json"))
            })
        }
        val tmp = createTempDirectory(prefix = "talon-assistant-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ship = FakeShip("~zod")
        io.nisfeb.talon.ai.LatticeKeys.forget()
        runBlocking { db.seed() }
        val repo = TlonChatRepo(db).apply {
            if (withLattice) attachForTest(ship.channel, "~zod", http = latticeOwner, base = "https://ship.test") else attachForTest(ship.channel, "~zod")
        }
        ship.channel.events().launchIn(sessionScope)
        val calendar = if (withCalendar) calendarOn(sessionScope).also { calRepo = it } else null
        val mail = if (withMail) mailOn(sessionScope) else null
        val orrery = if (withOrrery) orreryOn(sessionScope, db).also { orrery = it } else null
        // The host's, as the shells keep it: it outlives the screen.
        session = AssistantSession(sessionScope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        if (showing.value) AssistantScreen(
                            db = db, aiSettings = ai, embedder = null, onOpenMessage = { _, _, _ -> }, repo = repo,
                            session = session, forceExpanded = true, calendar = calendar, mail = mail, orrery = orrery,
                            onOpenChat = { opened += "chat $it" }, onOpenCalendar = { opened += "calendar" }, http = decisionHttp ?: latticeBare.takeIf { withLattice },
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
        // The last run shows its answer before it has kept its turn, and the box waits for that.
        waitUntil(timeoutMillis = 10_000) { !shows("Working…") }
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

    // sneagan, in Barcelona with the calendar on New York, asked what to do
    // this evening and was told of a game at home.
    @Test
    fun `away from the calendar's zone the model is told the owner's clock, both zones, and to look nearby`() {
        val here = kotlinx.datetime.TimeZone.currentSystemDefault().id
        calZone = if (here == "Pacific/Chatham") "Pacific/Kiritimati" else "Pacific/Chatham"
        assistant(withCalendar = true) {
            waitUntil(timeoutMillis = 10_000) { calRepo?.zone?.value == calZone }
            ask("what should I do this evening?")
            waitUntil(timeoutMillis = 10_000) { asked.isNotEmpty() }
            val request = asked.first()
            assertTrue("zone $here, where the owner is. The calendar's times are in $calZone." in request, request.substringAfter("NOW:").take(300))
            assertTrue(AgentPrompt.nearby in request, "the typed assistant's role has the nearby rule")
        }
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
        // Inside today in the calendar's zone (UTC here): an hour on from
        // 23:00 crossed midnight and drew the event under two days.
        val day = 86_400_000L
        val from = maxOf(now - 60_000, now / day * day)
        val to = minOf(now + 3_600_000, (now / day + 1) * day - 1)
        calRows = listOf("""{"id":"d1","cal":"default","cat":"timed","kind":"once","all":false,"l":$from,"r":$to,"meta":{"name":"Seed swap"}}""")
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

    // ─── what orrery leaves waiting ───────────────────────────────

    private val proposal = """{"ok":true,"reply":"On it.","actions":[{"id":"a1","kind":"message","title":"Tell Rose",""" +
        """"payload":{"to":"person/rose","text":"Running late, sorry"},"about":[],"status":"proposed","by":"owner"}],"note":""}"""

    /** Asks orrery to tell Rose, allows the telling, and waits for the proposal it leaves. */
    private fun ComposeUiTest.proposed() {
        instructed = proposal
        toolCall = call("c1", "orrery_instruct", """{"text":"tell Rose I am running late"}""")
        ask("tell her I'm late")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Send this?") }
    }

    private fun answered(id: String) = orreryPosts.filter { it.first.endsWith("/api/actions/$id") }.map { it.second }

    // The voice card puts orrery's proposals to the owner at once, Send or
    // Don't send; the text assistant left them under Actions. The same
    // card in the transcript, answered on the same path.
    @Test
    fun `orrery's proposal is put to the owner in the transcript and Approve answers it`() = assistant(withOrrery = true) {
        answer = "Rose will hear you are late."
        proposed()
        assertTrue(shows("Rose") && shows("Running late, sorry") && shows("On it."), "who it goes to and what it says")
        assertEquals("""{"text":"tell Rose I am running late","apply":false}""", orreryPosts.single { it.first.endsWith("/api/instruct") }.second)
        // Left and opened again while it waits: it still asks, and its run goes on.
        showing.value = false
        waitUntil(timeoutMillis = 5_000) { !shows("Send this?") }
        showing.value = true
        waitUntil(timeoutMillis = 5_000) { shows("Send this?") }
        onNodeWithText("Approve").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Rose will hear you are late.") }
        assertEquals(listOf("""{"status":"approved"}"""), answered("a1"), "as the Actions page answers it")
        assertTrue("Tell Rose (approved)" in asked.last(), "the model reads what the owner decided")
        assertTrue(shows("Approved.") && onAllNodesWithText("Approve").fetchSemanticsNodes().isEmpty(), "the buttons give way to the word")
    }

    // It waits for the owner: one that comes while they are away says so
    // with the Assistant's dot, as a confirmation does.
    @Test
    fun `a proposal that comes while the assistant is away lights its dot`() = assistant(withOrrery = true) {
        instructed = proposal
        holdInstruct = 1_500
        toolCall = call("c1", "orrery_instruct", """{"text":"tell Rose I am running late"}""")
        ask("tell her I'm late")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
        showing.value = false
        waitUntil(timeoutMillis = 10_000) { session.news.value }
        assertTrue(answered("a1").isEmpty(), "still asking")
        showing.value = true
        waitUntil(timeoutMillis = 5_000) { shows("Send this?") && !session.news.value }
        onNodeWithText("Don't").performClick()
        waitUntil(timeoutMillis = 10_000) { answered("a1").isNotEmpty() }
    }

    @Test
    fun `Don't on orrery's proposal dismisses it`() = assistant(withOrrery = true) {
        answer = "I won't tell her."
        proposed()
        onNodeWithText("Don't").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("I won't tell her.") }
        assertEquals(listOf("""{"status":"dismissed"}"""), answered("a1"))
        assertTrue("Tell Rose (dismissed)" in asked.last())
        assertTrue(shows("Dropped. Nothing was done."))
    }

    @Test
    fun `a proposal unanswered at Stop waits under Actions`() = assistant(withOrrery = true) {
        proposed()
        onNodeWithText("Stop").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Waiting for you under Actions.") }
        assertTrue(onAllNodesWithText("Approve").fetchSemanticsNodes().isEmpty(), "nothing left to tap that would do nothing")
        Thread.sleep(300)
        assertTrue(answered("a1").isEmpty(), "nothing answered")
    }

    @Test
    fun `a new conversation while a proposal asks leaves it under Actions and the run finishes`() = assistant(withOrrery = true) { db ->
        answer = "It waits for you under Actions."
        proposed()
        onNodeWithText("New conversation").performClick()
        waitUntil(timeoutMillis = 10_000) { runBlocking { db.assistantConversations().mostRecent() } != null }
        assertTrue("Tell Rose (proposed)" in asked.last(), "the model is told it still waits")
        assertTrue(answered("a1").isEmpty())
        assertTrue(!shows("Send this?"), "the transcript is clear")
    }

    @Test
    fun `an answer orrery does not take is said to the model`() = assistant(withOrrery = true) {
        answerStatus = io.ktor.http.HttpStatusCode.InternalServerError
        answer = "Orrery did not take it."
        proposed()
        onNodeWithText("Approve").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Orrery did not take it.") }
        assertEquals(1, answered("a1").size)
        assertTrue("orrery did not take the answer" in asked.last() && "It waits under Actions." in asked.last(), asked.last().takeLast(600))
    }

    // ─── changing what orrery holds, and saying only what it shows ───

    // sneagan, 2026-10-18: the assistant could not change a value, and
    // said changes were made that the view still showed the old way.
    private val jax = "situation/2026-10-18-dl-2627"

    private fun jaxNeeds(value: String, by: String = "telegram") =
        """{"id":"$jax","kind":"situation","name":"DL 2627","attrs":{"needs":{"value":"$value","at":"2026-10-18T12:00:00Z","until":null,""" +
            """"conf":100,"source":{"kind":"$by","id":"t1"},"by":"$by","obs":"o1"}},"involved":[],"actions":[],""" +
            """"observations":[{"id":"o1","attr":"needs","value":"$value","at":"2026-10-18T12:00:00Z","status":"live","source":{"kind":"$by","id":"t1"}}]}"""

    /** What the model was told the last tool said. */
    private fun toolSaid(): String = kotlinx.serialization.json.Json.parseToJsonElement(asked.last()).let { it as kotlinx.serialization.json.JsonObject }
        .getValue("messages").let { it as kotlinx.serialization.json.JsonArray }
        .last { (it as kotlinx.serialization.json.JsonObject)["role"].toString() == "\"tool\"" }
        .let { ((it as kotlinx.serialization.json.JsonObject).getValue("content") as kotlinx.serialization.json.JsonPrimitive).content }

    private fun ComposeUiTest.changeNeeds() {
        toolCall = call("c1", "orrery_set", """{"subject":"$jax","attr":"needs","value":"terminal drop-off"}""")
        ask("it is a drop-off now, not parking")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
    }

    @Test
    fun `a value is changed as the owner's word and the answer says what the record shows`() = assistant(withOrrery = true) {
        bodies[jax] = mutableListOf(jaxNeeds("park at JAX"), jaxNeeds("terminal drop-off", "user"))
        answer = "It is a drop-off now."
        changeNeeds()
        // Left while it writes: the write goes on, and the answer is there on return.
        showing.value = false
        waitUntil(timeoutMillis = 10_000) { orreryPosts.any { it.first.endsWith("/api/observe") } }
        showing.value = true
        waitUntil(timeoutMillis = 15_000) { shows("It is a drop-off now.") }
        // orrery's serve-observe keeps the by an owner's row names, and dates one with no at.
        assertEquals(
            """{"observations":[{"subject":"$jax","attr":"needs","value":"terminal drop-off","by":"owner"}]}""",
            orreryPosts.single { it.first.endsWith("/api/observe") }.second,
        )
        assertEquals(listOf("/apps/orrery/api/observe" to false), orreryKeyed.filter { it.first.endsWith("/api/observe") }, "the owner's login, not the key")
        assertEquals("Confirmed: $jax needs shows \"terminal drop-off\".", toolSaid())
    }

    @Test
    fun `a change the record does not show reaches the model as not confirmed`() = assistant(withOrrery = true) {
        bodies[jax] = mutableListOf(jaxNeeds("park at JAX"))
        answer = "Orrery still shows parking, so that is not confirmed."
        changeNeeds()
        waitUntil(timeoutMillis = 20_000) { shows("not confirmed") }
        assertTrue(toolSaid().startsWith("Not confirmed (the record still shows \"park at JAX\")"), toolSaid())
        assertTrue("never say a change is made that the record does not show" in toolSaid())
    }

    @Test
    fun `a change the ship never answers is said to be unknown`() = assistant(withOrrery = true) {
        bodies[jax] = mutableListOf(jaxNeeds("park at JAX"))
        observeDown = true
        answer = "Your ship did not answer."
        changeNeeds()
        waitUntil(timeoutMillis = 15_000) { shows("Your ship did not answer.") }
        assertTrue("may or may not have taken it" in toolSaid(), toolSaid())
    }

    // sneagan, 2026-10-18: "the assistant says it can't get my flight
    // status. both text and voice assistants should be able to do that.
    // orrery can". Orrery watches the flight; the assistant reads it.
    @Test
    fun `a flight's status is read from orrery's watches and reaches the model`() = assistant(withOrrery = true) {
        watchesDoc = """{"flight_api":{"key_set":true},"watches":[{"id":"dl2627","kind":"flight","flight":"dl2627","date":"2026-10-18","from":"JAX",""" +
            """"last":{"status":"Delayed","dep_sched":"2026-10-18 23:00Z","dep_gate":"B4"}}]}"""
        toolCall = call("c1", "orrery_flights")
        answer = "DL2627 is delayed, gate B4."
        ask("what's my flight status")
        waitUntil(timeoutMillis = 15_000) { shows("DL2627 is delayed, gate B4.") }
        assertTrue("DL2627 on 2026-10-18 from JAX: status Delayed" in toolSaid() && "gate B4" in toolSaid(), toolSaid())
        assertEquals(listOf(false), watchReads, "read once, on the owner's login")
        assertTrue(!shows("Allow this action?"), "a read asks nothing")
    }

    @Test
    fun `an orrery with no watches is said to be too old, not to have no flights`() = assistant(withOrrery = true) {
        toolCall = call("c1", "orrery_flights")
        answer = "Your orrery is too old to watch flights."
        ask("is my flight on time")
        waitUntil(timeoutMillis = 15_000) { shows("Your orrery is too old to watch flights.") }
        assertEquals("This orrery has no flight watches: it is older than version 105.", toolSaid())
    }

    /** The model's request [n]: its system prompt, its last user message, and its tools' names. */
    private fun request(n: Int): Triple<String, String, List<String>> {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(asked[n]) as kotlinx.serialization.json.JsonObject
        val msgs = o.getValue("messages") as kotlinx.serialization.json.JsonArray
        fun content(m: kotlinx.serialization.json.JsonElement) = ((m as kotlinx.serialization.json.JsonObject)["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val system = msgs.first { (it as kotlinx.serialization.json.JsonObject)["role"].toString() == "\"system\"" }.let(::content)
        val user = msgs.last { (it as kotlinx.serialization.json.JsonObject)["role"].toString() == "\"user\"" }.let(::content)
        val tools = (o["tools"] as? kotlinx.serialization.json.JsonArray).orEmpty().map {
            (((it as kotlinx.serialization.json.JsonObject)["function"] as kotlinx.serialization.json.JsonObject)["name"] as kotlinx.serialization.json.JsonPrimitive).content
        }
        return Triple(system, user, tools)
    }

    private fun ComposeUiTest.memoryLoaded() {
        waitUntil(timeoutMillis = 10_000) { latticeKeyed.any { it.url.encodedPath.endsWith("/know-index") } }
        settle()
    }

    /** The memory is read off the main thread: give its answer a moment to reach the prompt. */
    private fun ComposeUiTest.settle() {
        waitForIdle()
        Thread.sleep(300)
        waitForIdle()
    }

    // sneagan, 2026-10-19: "give talon an agent key and bring the voice and
    // text assistants up to memory parity with Claude Code".
    @Test
    fun `a question carries what memory recalls, the index is in the prompt, and a save goes under Talon's own key`() = assistant(withLattice = true) { db ->
        memoryLoaded()
        toolCall = call("c1", "memory_save", """{"key":"user/trips","body":"Flies Delta out of JAX."}""")
        answer = "I'll remember that."
        ask("remember that I fly Delta out of JAX")
        waitUntil(timeoutMillis = 15_000) { shows("I'll remember that.") }
        val (system, user, tools) = request(0)
        assertTrue("MEMORY" in system && "LATTICE INDEX" in system && "Always name the ship." in system, system.takeLast(400))
        assertTrue(user.startsWith("Lattice memories that may bear on this question") && "- /user/trips (strength 80): Flies Delta out of JAX." in user, user)
        assertTrue(user.endsWith("\n\nremember that I fly Delta out of JAX"), user)
        assertTrue(tools.containsAll(listOf("memory_recall", "memory_save", "wiki_search", "web_read")), "$tools")
        // Lattice signs it with the key's name: Bearer, no cookie.
        val save = latticeKeyed.single { it.url.encodedPath.endsWith("/know-save") }
        assertEquals("Bearer k1.s3cret", save.headers[io.ktor.http.HttpHeaders.Authorization])
        assertEquals(null, save.headers[io.ktor.http.HttpHeaders.Cookie])
        assertEquals("user/trips", save.url.parameters["key"])
        assertEquals("Flies Delta out of JAX.", (save.body as io.ktor.http.content.TextContent).text)
        assertEquals(listOf("/apps/lattice/key-mint"), latticeOwned.map { it.url.encodedPath }, "minted once, on the owner's login")
        assertEquals("k1.s3cret", runBlocking { db.latticeKeys().get("~zod") }?.token)
        // What was recalled reached the model only: not shown, not kept.
        assertTrue(!shows("Lattice memories that may bear"))
        assertEquals(listOf("remember that I fly Delta out of JAX"), runBlocking { db.assistantHistory().recent(5).first() }.map { it.question })
    }

    // The owner's revoke stands: a new key only at their word, in Settings.
    @Test
    fun `a key the owner revoked is said, and no new one is made`() = assistant(
        withLattice = true,
        seed = { latticeKeys().upsert(io.nisfeb.talon.data.LatticeKeyEntity("~zod", "k0", "k0.old", revoked = true)) },
    ) {
        settle()
        answer = "Talon's memory key was revoked."
        ask("what do you remember about my trips")
        waitUntil(timeoutMillis = 15_000) { shows("Talon's memory key was revoked.") }
        val (system, user, tools) = request(0)
        assertTrue(io.nisfeb.talon.ai.LatticeMemory.REVOKED in system, system.takeLast(300))
        assertEquals("what do you remember about my trips", user)
        assertTrue(tools.none { it.startsWith("memory_") || it.startsWith("wiki_") }, "$tools")
        assertTrue(latticeOwned.isEmpty(), "not minted again")
        assertTrue(latticeKeyed.isEmpty())
    }

    @Test
    fun `a ship with no lattice has no memory, and the assistant answers`() {
        latticeMint = io.ktor.http.HttpStatusCode.NotFound to "<html>404</html>"
        assistant(withLattice = true) {
            waitUntil(timeoutMillis = 10_000) { latticeOwned.isNotEmpty() }
            settle()
            answer = "Tuesday."
            ask("when is the next library day")
            waitUntil(timeoutMillis = 15_000) { shows("Tuesday.") }
            val (system, user, tools) = request(0)
            assertTrue("LATTICE" !in system && "MEMORY\n" !in system, system.takeLast(300))
            assertEquals("when is the next library day", user)
            assertTrue(tools.none { it.startsWith("memory_") })
            assertTrue(latticeKeyed.isEmpty())
        }
    }

    private val late = """{"id":"a7","kind":"message","title":"Say you are running late to Park at JAX for DL 2627",""" +
        """"payload":{"to":"person/rose","text":"Running late"},"about":["$jax"],"due":"2026-10-18T20:45:00Z","status":"proposed","by":"generator"}"""

    private fun ComposeUiTest.dismissLate() {
        toolCall = call("c1", "orrery_answer", """{"id":"a7","status":"dismissed","note":"dropped off, not parking"}""")
        ask("drop that running late one")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
    }

    @Test
    fun `a waiting action is dismissed and read back and the Actions list loses it`() = assistant(withOrrery = true) {
        openLists.clear(); openLists += listOf("[$late]", "[]")
        answer = "Dismissed it."
        dismissLate()
        waitUntil(timeoutMillis = 15_000) { shows("Dismissed it.") }
        assertEquals(listOf("""{"status":"dismissed","note":"dropped off, not parking"}"""), answered("a7"), "orrery's serve-set-action: status and note")
        assertEquals("Confirmed: orrery no longer lists Say you are running late to Park at JAX for DL 2627 as waiting.", toolSaid())
        assertTrue(orrery.actions.value.none { it.id == "a7" }, "the Actions page shows it gone too")
    }

    // Orrery 110: a deadline that matters is marked high, through the card
    // and with the read back, and the Actions page shows it at once.
    @Test
    fun `an action is marked high through the card and read back and the Actions list shows it`() = assistant(withOrrery = true) {
        openLists.clear(); openLists += listOf("[$late]", "[${late.replace("\"payload\":{", "\"payload\":{\"priority\":\"high\",")}]")
        answerBody = """{"ok":true,"id":"a7","priority":"high"}"""
        answer = "Marked it high."
        toolCall = call("c1", "orrery_mark", """{"id":"a7","high":true}""")
        ask("make the running late one high priority")
        waitUntil(timeoutMillis = 10_000) { shows("Allow this action?") }
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 15_000) { shows("Marked it high.") }
        assertEquals(listOf("/apps/orrery/api/actions/a7/priority" to """{"priority":"high"}"""), orreryPosts.filter { "/priority" in it.first }, "orrery's serve-mark")
        assertEquals("Confirmed: orrery shows Say you are running late to Park at JAX for DL 2627 as high.", toolSaid())
        assertTrue(orrery.actions.value.single { it.id == "a7" }.payload["priority"].toString() == "\"high\"", "the Actions page shows it high")
    }

    @Test
    fun `an answer orrery refuses reaches the model as not taken`() = assistant(withOrrery = true) {
        openLists.clear(); openLists += "[$late]"
        answerStatus = io.ktor.http.HttpStatusCode.Conflict
        answerBody = """{"error":"cannot go from dismissed to dismissed"}"""
        answer = "Orrery would not take that."
        dismissLate()
        waitUntil(timeoutMillis = 15_000) { shows("Orrery would not take that.") }
        assertEquals("Orrery did not take it: cannot go from dismissed to dismissed. Nothing was changed.", toolSaid())
        assertTrue(orrery.actions.value.any { it.id == "a7" }, "still waiting under Actions")
    }

    // ─── the day, as Archie opens it ─────────────────────────────

    /** An event on now, one later today, an unread mail and today's brief, where it is noon. */
    private fun aDay() {
        calZone = noonZone()
        val now = System.currentTimeMillis()
        calRows = listOf(
            """{"id":"d1","cal":"default","cat":"timed","kind":"once","all":false,"l":${now - 60_000},"r":${now + 3_600_000},"meta":{"name":"Seed swap"}}""",
            """{"id":"d2","cal":"default","cat":"timed","kind":"once","all":false,"l":${now + 1_800_000},"r":${now + 3_600_000},"meta":{"name":"Tea with Rose"}}""",
        )
        inbox = """{"total":1,"offset":0,"limit":50,"view":"inbox","threads":[
            {"id":"0v1","subject":"Seed catalogue","from":"~nec","snippet":"The spring list","verdict":"verified",
             "forged":false,"count":1,"last":$now,"unread":true,"participants":[],
             "unreadable":0,"archived":false,"labels":[]}]}"""
        val today = java.time.ZonedDateTime.now(java.time.ZoneId.of(calZone)).toLocalDate()
        brief = """{"day":"$today","text":"Andrea is away, so you drop off."}"""
    }

    // sneagan, 2026-10-18: "go for parity". Archie opens with the counts,
    // the model's rest and the day's card; Brief me does the same here.
    @Test
    fun `Brief me opens the day as Archie does with its card and the brief follows`() {
        aDay()
        assistant(withCalendar = true, withMail = true, withOrrery = true) { db ->
            waitUntil(timeoutMillis = 5_000) { shows("Brief me") }
            answer = "Tea with Rose is in half an hour. Shall I read you the brief?"
            onNodeWithText("Brief me").performClick()
            waitUntil(timeoutMillis = 10_000) { shows("You have 1 new email, and no new messages. Tea with Rose is in half an hour.") }
            // The day's card: today's events and the unread mail.
            waitUntil(timeoutMillis = 5_000) { shows("Seed catalogue") }
            assertTrue(shows("Today") && shows("Seed swap"))
            val opener = asked.single()
            assertTrue("do not say those numbers again" in opener, "Archie's own opener prompt")
            assertTrue("First event today: Tea with Rose at" in opener && "Andrea is away, so you drop off." in opener, opener.takeLast(800))
            waitUntil(timeoutMillis = 5_000) { runBlocking { db.assistantConversations().mostRecent() } != null }
            val turn = runBlocking { db.assistantHistory().forConversation(db.assistantConversations().mostRecent()!!.id) }.single()
            assertEquals("Brief me" to "You have 1 new email, and no new messages. Tea with Rose is in half an hour. Shall I read you the brief?", turn.question to turn.answer)

            // "Yes": the brief, read from orrery, with the opener before it.
            toolCall = call("c1", "read_brief")
            answer = "Andrea is away, so you drop off."
            ask("yes please")
            waitUntil(timeoutMillis = 10_000) { asked.size == 3 && onAllNodesWithText("Andrea is away, so you drop off.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Shall I read you the brief?" in asked[1], "the opener is the turn before")
            assertTrue("Andrea is away, so you drop off." in asked[2], "the brief, as the tool read it")
        }
    }

    @Test
    fun `the model can open the day itself and its card shows under the answer`() {
        aDay()
        assistant(withCalendar = true, withMail = true) {
            toolCall = call("c1", "day_overview")
            answer = "A busy one."
            ask("how does my day look?")
            waitUntil(timeoutMillis = 10_000) { shows("A busy one.") && shows("Seed catalogue") }
            assertTrue(shows("Tea with Rose"))
            assertTrue("You have 1 new email, and no new messages." in asked.last() && "No brief today." in asked.last(), asked.last().takeLast(600))
        }
    }

    // ─── what is on, with no model ────────────────────────────────

    // The voice card answers "what's on today" from the calendar with no
    // model call, where the decision model says that is all it asks.
    @Test
    fun `a plain question about what is on is answered from the calendar with its card and no model call`() {
        aDay()
        assistant(withCalendar = true, decisions = decision(0.97, "today")) { db ->
            ask("what's on today")
            waitUntil(timeoutMillis = 10_000) { shows("Today you have 2 events.") }
            assertTrue(shows("You're in Seed swap until") && shows("Next is Tea with Rose at"), "the line Archie says")
            // The card, drawn once the run has answered: each event by name alone.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Tea with Rose").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodesWithText("Seed swap").fetchSemanticsNodes().isNotEmpty() && shows("Today"), "the day's card")
            assertTrue(asked.isEmpty(), "no model call")
            assertTrue("what's on today" in decided.single(), "the decision model was asked, once")
            waitUntil(timeoutMillis = 5_000) { runBlocking { db.assistantConversations().mostRecent() } != null }
        }
    }

    @Test
    fun `a question the decision model calls more than the calendar goes to the model`() {
        aDay()
        assistant(withCalendar = true, decisions = decision(0.2, "today")) {
            ask("what's left before Barcelona?")
            waitUntil(timeoutMillis = 10_000) { shows("Tuesday, at the library.") }
            assertEquals(1, asked.size)
            assertEquals(1, decided.size)
        }
    }

    @Test
    fun `with the decision model off nothing is decided and the model answers`() {
        aDay()
        assistant(withCalendar = true) {
            ask("what's on today")
            waitUntil(timeoutMillis = 10_000) { shows("Tuesday, at the library.") }
            assertTrue(decided.isEmpty())
        }
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

