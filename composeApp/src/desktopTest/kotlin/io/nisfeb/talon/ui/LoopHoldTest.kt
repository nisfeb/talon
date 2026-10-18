package io.nisfeb.talon.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ai.AgentMessage
import io.nisfeb.talon.ai.AgentTurn
import io.nisfeb.talon.ai.AssistantActions
import io.nisfeb.talon.ai.LoopRunner
import io.nisfeb.talon.ai.LoopScheduler
import io.nisfeb.talon.ai.ToolCall
import io.nisfeb.talon.ai.actionTools
import io.nisfeb.talon.ai.paidSettings
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.ui.screens.LoopsScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A loop that asks before sharing, through to the calendar: its share is
 * held in the run, offered on the Loops screen, and reaches the calendar
 * only once allowed, with the body the calendar's share tests pin
 * (CalendarsDialogTest, CalendarWriteTest). The real share tools over a
 * calendar on a mock ship; the model is scripted.
 */
@OptIn(ExperimentalTestApi::class)
class LoopHoldTest {
    /** Each write to the calendar's share routes: the route and its body. */
    private val wrote: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()
    /** How long the ship takes over a share write: a busy one. */
    @Volatile private var holdWriteMs = 0L
    private val notified: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val showing = mutableStateOf(true)

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        when {
            req.method == HttpMethod.Post && "/share/" in path -> {
                if (holdWriteMs > 0) kotlinx.coroutines.delay(holdWriteMs)
                wrote += path.substringAfter("/share/") to req.body.toByteArray().decodeToString()
                json("""{"notified":true}""")
            }
            path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"},{"id":"garden","name":"Garden","kind":"local"}]""")
            path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
            path.endsWith("/share/shares.json") ->
                json("""{"shares":{},"offers":{"~nec/work":{"host":"~nec","cal":"work","name":"Work","mode":"edit"}},"accepted":{}}""")
            path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
            path.endsWith("/window.json") -> json("""{"rows":[]}""")
            path.endsWith("/outcomes.json") -> json("""{"refusals":[],"faults":{}}""")
            else -> json("[]")
        }
    })

    private val shareGarden = "share_calendar" to buildJsonObject { put("calendar", "Garden"); put("ship", "~nec"); put("edit", true) }

    /** A loop that makes [call] once and answers; run once before the screen opens. */
    private fun held(ask: Boolean = true, call: Pair<String, JsonObject> = shareGarden, block: ComposeUiTest.(AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-loop-hold-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        // The host's: Allow and the calendar's writes run here, past the screen.
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calendar = CalendarRepo(http, host, pollIntervalMs = 60 * 60_000L).apply { attach("https://ship.test") }
        val runner = LoopRunner(
            loops = db.loops(), runs = db.loopRuns(), holds = db.loopHolds(),
            tools = actionTools(AssistantActions(db = db, contacts = { ContactMap() }, calendar = calendar, zone = { TimeZone.UTC })),
            completer = { _, messages, _ ->
                if (messages.none { it is AgentMessage.ToolResults }) AgentTurn.Calls(null, listOf(ToolCall("c1", call.first, call.second)))
                else AgentTurn.Final("done")
            },
            aiConfig = { paidSettings("k", true) },
            notify = { _, _, body -> notified += body },
        )
        runBlocking {
            calendar.refresh()
            val id = db.loops().upsert(LoopEntity(name = "Shares", prompt = "share the garden with nec", intervalMinutes = 60,
                writesAuthorized = true, askBeforeSharing = ask, createdAt = 1, updatedAt = 1))
            runner.runLoop(db.loops().get(id)!!)
        }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        if (showing.value) LoopsScreen(
                            db = db, scheduler = LoopScheduler.Noop, onRunNow = {}, onBack = {},
                            onAllowHeld = { holdId -> host.launch { runner.allow(holdId) } },
                        )
                    }
                }
                waitForIdle()
                block(db)
            }
        } finally {
            host.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private val gardenBody = """{"id":"garden","ship":"~nec","mode":"edit"}"""

    @Test
    fun `a held share reaches the calendar only once allowed and with the exact body`() = held { db ->
        assertTrue(wrote.isEmpty(), "nothing shared by the run: $wrote")
        assertTrue(notified.single().startsWith("Shares wants to share Garden with ~nec, edit on."), notified.toString())
        waitUntil(timeoutMillis = 5_000) { shows("Shares wants to share Garden with ~nec, edit on.") }
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 10_000) { wrote.isNotEmpty() }
        assertEquals(listOf("share" to gardenBody), wrote)
        waitUntil(timeoutMillis = 5_000) { !shows("wants to share Garden") }
        // What came of it: in the loop's history and a notification.
        val said = "You allowed it: Shares wants to share Garden with ~nec, edit on.\n\nOffered \"Garden\" to ~nec to read and edit."
        waitUntil(timeoutMillis = 5_000) { notified.last() == said }
        onNodeWithText("Shares").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Offered \"Garden\" to ~nec to read and edit.") }
        assertTrue(shows("Waiting for you in Loops: Shares wants to share Garden"), "the run said what waited")
        assertEquals(1, wrote.size, "done once")
        assertTrue(runBlocking { db.loopHolds().stream(0).first() }.isEmpty())
    }

    @Test
    fun `with the switch off the run shares at once as before`() = held(ask = false) { db ->
        assertEquals(listOf("share" to gardenBody), wrote)
        assertTrue(runBlocking { db.loopHolds().stream(0).first() }.isEmpty())
        assertEquals("done", notified.single())
        assertTrue(!shows("Allow"))
    }

    @Test
    fun `a held offer answer is sent only once allowed`() = held(call = "answer_calendar_offer" to buildJsonObject { put("offer", "~nec/work"); put("accept", true) }) {
        assertTrue(wrote.isEmpty(), "$wrote")
        waitUntil(timeoutMillis = 5_000) { shows("Shares wants to accept the calendar offer ~nec/work.") }
        onNodeWithText("Allow").performClick()
        waitUntil(timeoutMillis = 10_000) { wrote.isNotEmpty() }
        assertEquals(listOf("accept" to """{"key":"~nec/work"}"""), wrote)
    }

    @Test
    fun `Don't sends nothing`() = held { db ->
        waitUntil(timeoutMillis = 5_000) { shows("wants to share Garden") }
        onNodeWithText("Don't").performClick()
        waitUntil(timeoutMillis = 5_000) { !shows("wants to share Garden") }
        Thread.sleep(500) // real time, for a write that should not come
        assertTrue(wrote.isEmpty(), "$wrote")
        assertTrue(runBlocking { db.loopHolds().stream(0).first() }.isEmpty())
    }

    // Allow on a busy ship, and Loops left at once: the share still goes.
    @Test
    fun `an allowed share goes on after the screen is left`() = held { db ->
        holdWriteMs = 600
        waitUntil(timeoutMillis = 5_000) { shows("wants to share Garden") }
        onNodeWithText("Allow").performClick()
        showing.value = false
        waitForIdle()
        assertTrue(wrote.isEmpty(), "still on its way")
        waitUntil(timeoutMillis = 10_000) { wrote.isNotEmpty() }
        assertEquals(listOf("share" to gardenBody), wrote)
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { db.loopRuns().streamForLoop(1, 1).first() }.firstOrNull()?.output?.startsWith("You allowed it") == true
        }
    }
}
