package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.orrery.OrreryAction
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.ui.screens.OrreryActionsScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Orrery proposes, answered: the list's quick Approve and Dismiss,
 * and one action opened to approve, dismiss with a reason, refine in
 * words, or mark done, each checked by what reaches the ship.
 */
@OptIn(ExperimentalTestApi::class)
class OrreryActionsTest {
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun action(id: String, status: String = "proposed", kind: String = "message", title: String = "Tell Bus about lunch") = OrreryAction(
        id = id, kind = kind, title = title,
        payload = Json.parseToJsonElement("""{"via":"chat","to":"~bus","text":"Lunch at noon?"}""").jsonObject,
        about = listOf("lunch"), due = null, status = status, by = "the analyst",
    )

    // ─── the list ─────────────────────────────────────────────────

    private fun list(actions: List<OrreryAction>, failed: List<OrreryAction> = emptyList(), block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                OrreryActionsScreen(
                    actions = actions, onBack = {}, failed = failed,
                    onShown = { did += "shown" },
                    generator = "Read 4 chats an hour ago",
                    onDecide = { a, status, why -> did += "${a.id} $status $why".trim() },
                    onOpen = { did += "open ${it.id}" },
                )
            }
        }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `what Orrery said to the last Tell shows on the page until it is left`() {
        val here = androidx.compose.runtime.mutableStateOf(true)
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    if (here.value) OrreryActionsScreen(
                        actions = emptyList(), onBack = {}, onOpen = {},
                        told = "Bus is on holiday until Friday.", onLeave = { did += "left" },
                    )
                }
            }
            waitUntil(timeoutMillis = 5_000) { shows("Bus is on holiday until Friday.") }
            here.value = false
            waitForIdle()
            assertEquals(listOf("left"), did.toList())
        }
    }

    @Test
    fun `nothing waiting says so, and says what the generator last did`() = list(emptyList()) {
        waitUntil(timeoutMillis = 5_000) { shows("Nothing to answer") }
        assertTrue(shows("Read 4 chats an hour ago"))
        waitUntil(timeoutMillis = 5_000) { "shown" in did }
    }

    @Test
    fun `a proposal is approved or dismissed from the list, or opened`() = list(listOf(action("a1"), action("a2", status = "done", title = "Old thing"))) {
        waitUntil(timeoutMillis = 5_000) { shows("Tell Bus about lunch") }
        assertTrue(shows("Old thing"), "settled ones are listed too")
        onNodeWithText("Approve").performClick()
        onNodeWithText("Dismiss").performClick()
        onNodeWithText("No reason").performClick()
        onNodeWithText("Tell Bus about lunch").performClick()
        assertEquals(listOf("a1 approved", "a1 dismissed", "open a1"), did.filter { it != "shown" })
    }

    // An approved action the ship could not carry out left the list like
    // one done, and nothing said it had not happened.
    @Test
    fun `what did not go through is listed with the ship's reason, and opens`() =
        list(emptyList(), failed = listOf(action("f1", status = "failed").copy(note = "no DM with ~bus"))) {
            waitUntil(timeoutMillis = 5_000) { shows("Did not go through") }
            assertTrue(shows("no DM with ~bus") && !shows("Nothing to answer"))
            onNodeWithText("Tell Bus about lunch").performClick()
            assertEquals(listOf("open f1"), did.filter { it != "shown" })
        }

    // ─── one action ───────────────────────────────────────────────

    private val posts: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()
    /** How long the ship's model takes to answer what it is told. */
    @Volatile private var holdInstruct = 0L
    private lateinit var repo: OrreryRepo

    private fun opened(
        a: OrreryAction,
        refine: (String) -> String = { """{"ok":true}""" },
        instruct: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"ok":true,"reply":"","actions":[],"note":""}""" },
        block: ComposeUiTest.() -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-orract-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val body = req.body.toByteArray().decodeToString()
            if (req.method == HttpMethod.Post) posts += path to body
            if (path.endsWith("/api/instruct")) {
                kotlinx.coroutines.delay(holdInstruct)
                val (status, said) = instruct()
                return@MockEngine respond(said, status, headersOf("Content-Type", "application/json"))
            }
            val answer = when {
                path.endsWith("/refine") -> refine(body)
                req.method == HttpMethod.Post && "/api/actions/" in path -> """{"ok":true}"""
                path.endsWith("/api/actions") -> "[]"
                else -> null
            }
            if (answer != null) respond(answer, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else respond("", HttpStatusCode.NotFound)
        })
        val scope = CoroutineScope(SupervisorJob())
        val orrery = OrreryRepo(http, scope, db, "test", bareClient = http).apply { attach("https://ship.test", "~zod") }
        repo = orrery
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) { io.nisfeb.talon.ui.OrreryActionDialog(a, orrery, onClose = { did += "closed" }) }
                }
                waitUntil(timeoutMillis = 5_000) { shows(a.title) }
                block()
            }
        } finally {
            runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a correction shows what is struck and why, and the ship makes it once approved`() = opened(
        action("c1", kind = "correct", title = "Andrea was not in Barcelona").copy(
            payload = Json.parseToJsonElement("""{"subject":"person/andrea","attr":"location","value":{"ref":"place/barcelona"},"why":"she stayed home"}""").jsonObject,
        ),
    ) {
        assertTrue(shows("Not true: person/andrea's location is place/barcelona") && shows("Why: she stayed home"))
        assertTrue(shows("Approved, the ship makes the change itself."))
        onNodeWithText("Approve").performClick()
        assertEquals("""{"status":"approved"}""", answered("c1"))
    }

    // Orrery 60: the owner's own words about a proposal, for the ship's
    // model to act on. What it files is proposed; a refusal is said.
    // "doing tell orrery and hitting send doesn't immediately dismiss the
    // popup": the model takes up to two minutes, and the dialog waited.
    @Test
    fun `what the owner tells Orrery goes with the proposal, the dialog closes at once, and the reply follows`() = opened(
        action("a1"),
        instruct = { HttpStatusCode.OK to """{"ok":true,"reply":"Bus is on holiday until Friday.","actions":[{"id":"t9","kind":"task","title":"Ask Bus on Friday","payload":{},"about":[],"status":"proposed","by":"owner"}],"note":""}""" },
    ) {
        holdInstruct = 2_000
        onNodeWithText("Tell Orrery").performClick()
        onNode(hasSetTextAction()).performTextInput("he is away this week")
        onNodeWithText("Send").performClick()
        waitForIdle()
        assertEquals(listOf("closed"), did.toList(), "closed before the ship answered")
        assertEquals("Orrery is reading what you told it.", repo.told.value)
        waitUntil(timeoutMillis = 10_000) { repo.told.value != "Orrery is reading what you told it." }
        assertEquals("Bus is on holiday until Friday. Filed: Ask Bus on Friday", repo.told.value)
        assertEquals(listOf("t9"), repo.actions.value.map { it.id }, "what it filed is in the list")
        val sent = Json.parseToJsonElement(posts.single { it.first.endsWith("/api/instruct") }.second).jsonObject
        assertEquals("""{"text":"he is away this week","action":"a1","apply":false}""", sent.toString())
    }

    @Test
    fun `an Orrery with no model key says so when told something`() = opened(
        action("a1"),
        instruct = { HttpStatusCode.ServiceUnavailable to """{"error":"the generator has no key"}""" },
    ) {
        onNodeWithText("Tell Orrery").performClick()
        onNode(hasSetTextAction()).performTextInput("she moved to Lisbon")
        onNodeWithText("Send").performClick()
        waitForIdle()
        assertEquals(listOf("closed"), did.toList())
        // Said where Actions shows problems, with the words to say again.
        waitUntil(timeoutMillis = 5_000) { repo.answerProblem.value != null }
        val said = repo.answerProblem.value!!
        assertTrue("\u201cshe moved to Lisbon\u201d" in said && "Orrery has no model key on your ship" in said, said)
        assertEquals(null, repo.told.value)
    }

    @Test
    fun `one that failed says why, and offers nothing to do`() = opened(action("f1", status = "failed").copy(note = "no DM with ~bus")) {
        assertTrue(shows("It did not go through. no DM with ~bus"))
        assertTrue(!shows("Mark done") && !shows("Approve") && !shows("Not this"))
    }

    private fun ComposeUiTest.answered(id: String): String {
        waitUntil(timeoutMillis = 5_000) { posts.any { it.first.endsWith("/api/actions/$id") } }
        return posts.last { it.first.endsWith("/api/actions/$id") }.second
    }

    @Test
    fun `a message proposal shows who it goes to and what it says, and approving sends it`() = opened(action("a1")) {
        assertTrue(shows("To ~bus, by DM:") && shows("Lunch at noon?"))
        assertTrue(shows("Approved, the ship sends it as a DM."))
        onNodeWithText("Approve and send").performClick()
        assertEquals("""{"status":"approved"}""", answered("a1"))
        assertTrue("closed" in did)
    }

    @Test
    fun `not this, with a reason, dismisses it and says why`() = opened(action("a1")) {
        onNodeWithText("Not this").performClick()
        onNode(hasSetTextAction()).performTextInput("wrong person")
        onAllNodesWithText("Dismiss").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        val sent = Json.parseToJsonElement(answered("a1")) as JsonObject
        assertEquals("dismissed", sent["status"].toString().trim('"'))
        assertEquals("wrong person", sent["note"].toString().trim('"'))
    }

    @Test
    fun `words sent to refine it come back as the proposal revised`() = opened(action("a1"), refine = {
        """{"ok":true,"action":{"id":"a1","kind":"message","title":"Tell Bus and Nec about lunch","payload":{"via":"chat","to":"~bus","text":"Lunch at one?"},"status":"proposed"},"note":""}"""
    }) {
        onNodeWithText("Say what it should be").performClick()
        onNode(hasSetTextAction()).performTextInput("make it one o'clock")
        onNodeWithText("Refine").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Lunch at one?") }
        assertTrue(posts.single { it.first.endsWith("/refine") }.second.contains("make it one o'clock"))
    }

    @Test
    fun `a refinement the ship will not take says why and leaves the proposal`() = opened(action("a1"), refine = {
        """{"ok":false,"note":"That action has moved on."}"""
    }) {
        onNodeWithText("Say what it should be").performClick()
        onNode(hasSetTextAction()).performTextInput("make it one o'clock")
        onNodeWithText("Refine").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("That action has moved on.") }
        assertTrue(shows("Lunch at noon?"))
    }

    @Test
    fun `an approved task is marked done`() = opened(action("t1", status = "approved", kind = "task", title = "Buy bread")) {
        assertTrue(shows("on your task list"))
        onNodeWithText("Mark done").performClick()
        assertEquals("""{"status":"done"}""", answered("t1"))
    }

    // Three of them did not fit across a phone's dialog, and the last was
    // squeezed to a column one letter wide.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `on a phone, the dialog's choices wrap rather than squeeze`() {
        val tmp = createTempDirectory(prefix = "talon-orract-phone-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val http = HttpClient(MockEngine { respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
        val scope = CoroutineScope(SupervisorJob())
        val orrery = OrreryRepo(http, scope, db, "test", bareClient = http)
        try {
            androidx.compose.ui.test.runDesktopComposeUiTest(width = 412, height = 915) {
                setContent { TalonTheme(darkTheme = false) { io.nisfeb.talon.ui.OrreryActionDialog(action("a1"), orrery, onClose = {}) } }
                waitUntil(timeoutMillis = 5_000) { shows("Not this") }
                val line = onNodeWithText("Say what it should be").fetchSemanticsNode().boundsInRoot.height
                val not = onNodeWithText("Not this").fetchSemanticsNode().boundsInRoot
                assertTrue(not.height <= line * 1.5f, "one line, not a column: ${not.height} against ${line}")
                assertTrue(not.width > not.height, "wider than tall: $not")
            }
        } finally {
            runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
            db.close()
            tmp.deleteRecursively()
        }
    }
}
