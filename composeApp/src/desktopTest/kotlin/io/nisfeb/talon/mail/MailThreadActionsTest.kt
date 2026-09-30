package io.nisfeb.talon.mail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
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
import io.nisfeb.talon.urbit.TalonLink
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A thread's own menu: each action asks auspex, and Delete asks first. */
@OptIn(ExperimentalTestApi::class)
class MailThreadActionsTest {
    /** Each write, as path and body. */
    private val posts: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()

    private val thread = """{"id":"0vt","messages":[
        {"id":"0vm1","from":"~zod","to":["~nec"],"subject":"Plans","body":"hello","body-mime":"","sent":10,"prev":null,"verdict":"verified","read":true}],
        "participants":["~zod","~nec"],"last":10,"unreadable":0,"archived":false,"labels":[]}"""

    private val copied = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun pane(body: String? = thread, ready: String = "hello", block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val http = HttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Post) posts += req.url.encodedPath to req.body.toByteArray().decodeToString()
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/thread/0vt") && body == null -> respond("""{"error":"no such thread"}""", HttpStatusCode.NotFound)
                path.endsWith("/api/thread/0vt") -> respond(body!!, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                path.endsWith("/api/whoami") -> respond("""{"ship":"~nec"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                else -> respond("""{"ok":true,"threads":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        })
        val repo = MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
        setContent {
            CompositionLocalProvider(
                LocalClipboardManager provides object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) { copied += annotatedString.text }
                },
            ) {
                TalonTheme(darkTheme = false) {
                    MailThreadPane(repo = repo, threadId = "0vt", contacts = ContactMap.EMPTY, ourShip = "~nec", onCompose = {})
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { shows(ready) }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

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

    @Test
    fun `the link is copied, and filing to lattice says where it went`() = pane {
        menu("Copy link")
        assertEquals(listOf(TalonLink.forMail("0vt")), copied.toList())
        menu("File to Lattice")
        waitUntil(timeoutMillis = 5_000) { shows("Filed to Lattice at") }
        onNodeWithText("Filed to Lattice at", substring = true).performClick()
        waitForIdle()
        assertTrue(!shows("Filed to Lattice at"), "a tap puts the note away")
    }

    @Test
    fun `a message's words copy whole from under it`() = pane {
        onNodeWithText("Copy").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Copied") }
        assertEquals(listOf("hello"), copied.toList())
    }

    @Test
    fun `a thread's labels show under its subject and open the editor`() = pane(body = thread.replace("\"labels\":[]", "\"labels\":[\"garden\"]")) {
        onNodeWithText("garden").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Labels") }
        onNodeWithText("Done").performClick()
    }

    @Test
    fun `a thread gone from the ship, or in no form this build reads, says so`() {
        pane(body = null, ready = "This thread is no longer on the ship.") {}
        pane(
            body = """{"id":"0vt","messages":[],"participants":["~zod","~nec"],"last":10,"unreadable":2,"archived":false,"labels":[]}""",
            ready = "Every copy of this thread is in a form this build cannot read.",
        ) { assertTrue(shows("2 copies here are in a form this build cannot read.")) }
    }

    @Test
    fun `stored copies and an answer to an unreadable message are named`() = pane(
        body = """{"id":"0vt","messages":[
            {"id":"0vm1","from":"~zod","to":["~nec"],"subject":"Plans","body":"hello","body-mime":"","sent":10,"prev":null,"verdict":"verified","read":true},
            {"id":"0vm1","from":"~zod","to":["~nec"],"subject":"Plans","body":"hello","body-mime":"","sent":10,"prev":null,"verdict":"forged","read":true},
            {"id":"0vm2","from":"~zod","to":["~nec"],"subject":"Plans","body":"about that","body-mime":"","sent":20,"prev":"0vgone","verdict":"verified","read":true}],
            "participants":["~zod","~nec"],"last":20,"unreadable":0,"archived":false,"labels":[]}""",
    ) {
        waitUntil(timeoutMillis = 5_000) { shows("2 stored copies of this message") }
        assertTrue(shows("Answers a message this build cannot read."))
    }
}
