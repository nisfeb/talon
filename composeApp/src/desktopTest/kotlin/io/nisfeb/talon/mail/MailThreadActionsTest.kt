package io.nisfeb.talon.mail

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailThreadPane
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/** A thread's own menu: each action asks auspex, and Delete asks first. */
@OptIn(ExperimentalTestApi::class)
class MailThreadActionsTest {
    /** Each write, as path and body. */
    private val posts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    private val thread = """{"id":"0vt","messages":[
        {"id":"0vm1","from":"~zod","to":["~nec"],"subject":"Plans","body":"hello","body-mime":"","sent":10,"prev":null,"verdict":"verified","read":true}],
        "participants":["~zod","~nec"],"last":10,"unreadable":0,"archived":false,"labels":[]}"""

    private fun pane(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val http = HttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Post) posts += req.url.encodedPath to req.body.toByteArray().decodeToString()
            val body = if (req.url.encodedPath.endsWith("/api/thread/0vt")) thread else """{"ok":true,"threads":[]}"""
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val repo = MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
        setContent {
            TalonTheme(darkTheme = false) {
                MailThreadPane(repo = repo, threadId = "0vt", contacts = ContactMap.EMPTY, ourShip = "~nec", onCompose = {})
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("hello", substring = true).fetchSemanticsNodes().isNotEmpty() }
        block()
    }

    private fun ComposeUiTest.menu(item: String) {
        onNodeWithContentDescription("Thread actions").performClick()
        onNodeWithText(item).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.posted(path: String): String {
        waitUntil(timeoutMillis = 5_000) { posts.any { it.first.endsWith(path) } }
        return posts.last { it.first.endsWith(path) }.second
    }

    @Test
    fun `archiving asks auspex to archive the thread`() = pane {
        menu("Archive")
        val body = posted("/api/archive")
        assertTrue("0vt" in body && "true" in body, body)
    }

    @Test
    fun `marking unread names the thread and its messages`() = pane {
        menu("Mark unread")
        val body = posted("/api/unread")
        assertTrue("\"thread-id\":\"0vt\"" in body && "0vm1" in body, body)
    }

    @Test
    fun `delete asks first, Keep sends nothing, and Delete deletes`() = pane {
        menu("Delete")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Delete this thread?").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Keep").performClick()
        waitForIdle()
        assertTrue(posts.none { it.first.endsWith("/api/delete-thread") }, "kept")
        menu("Delete")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Delete this thread?").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        assertTrue("0vt" in posted("/api/delete-thread"))
    }
}
