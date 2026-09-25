package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlin.test.Test
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

    private fun assistant(block: ComposeUiTest.(AppDatabase) -> Unit) {
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
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        AssistantScreen(
                            db = db, aiSettings = ai, embedder = null, onOpenMessage = { _, _, _ -> }, repo = repo,
                            session = AssistantSession(sessionScope), forceExpanded = true,
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
}
