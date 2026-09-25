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

    private fun list(actions: List<OrreryAction>, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                OrreryActionsScreen(
                    actions = actions, onBack = {},
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

    // ─── one action ───────────────────────────────────────────────

    private val posts: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()

    private fun opened(a: OrreryAction, refine: (String) -> String = { """{"ok":true}""" }, block: ComposeUiTest.() -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-orract-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val body = req.body.toByteArray().decodeToString()
            if (req.method == HttpMethod.Post) posts += path to body
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
}
