package io.nisfeb.talon.mail

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailIntent
import io.nisfeb.talon.ui.screens.MailThreadPane
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A mail thread as read: a forgery flagged, remote images left unloaded
 * until asked, replies folded away and back, and who a reply or forward
 * goes to.
 */
@OptIn(ExperimentalTestApi::class)
class MailThreadViewTest {
    private val composed = CopyOnWriteArrayList<MailIntent>()

    private fun message(id: String, from: String, body: String, prev: String?, verdict: String = "verified", sent: Long) =
        """{"id":"$id","from":"$from","to":["~nec"],"subject":"Plans","body":${kotlinx.serialization.json.JsonPrimitive(body)},"body-mime":"",""" +
            """"sent":$sent,"prev":${prev?.let { "\"$it\"" } ?: "null"},"verdict":"$verdict","read":true}"""

    private fun thread(vararg messages: String) =
        """{"id":"0vt","messages":[${messages.joinToString(",")}],"participants":["~zod","~nec"],"last":30,"unreadable":0,"archived":false,"labels":[]}"""

    private val chain = thread(
        message("0vm1", "~zod", "shall we plant garlic", null, sent = 10),
        message("0vm2", "~nec", "> shall we plant garlic\nyes, see https://img.example/bulbs.png", "0vm1", sent = 20),
        message("0vm3", "~zod", "bought them", "0vm2", sent = 30),
    )

    private fun pane(body: String = chain, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val http = HttpClient(MockEngine { req ->
            val answer = if (req.url.encodedPath.endsWith("/api/thread/0vt")) body else """{"ok":true,"threads":[]}"""
            respond(answer, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val repo = MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
        setContent {
            TalonTheme(darkTheme = false) {
                MailThreadPane(repo = repo, threadId = "0vt", contacts = ContactMap.EMPTY, ourShip = "~nec", onCompose = { composed += it })
            }
        }
        waitUntil(timeoutMillis = 5_000) { shows("bought them") }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a remote image waits to be asked for`() = pane(
        body = thread(message("0vm1", "~zod", "shall we plant garlic", null, sent = 10), message("0vm2", "~nec", "yes, see https://img.example/bulbs.png\nbought them", "0vm1", sent = 20)),
    ) {
        assertTrue(shows("Load 1 remote image"), "nothing is fetched from the sender's server unasked")
    }

    @Test
    fun `replies fold away and back`() = pane {
        onAllNodesWithContentDescription("Fold replies")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("replies folded") || shows("reply folded") }
        assertTrue(!shows("bought them"))
        onAllNodesWithContentDescription("Unfold replies")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("bought them") }
    }

    @Test
    fun `reply goes to the others in the thread, and forward to nobody yet`() = pane {
        onAllNodesWithContentDescription("Reply").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        onAllNodesWithContentDescription("Forward").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        val (reply, forward) = composed.toList()
        assertEquals(listOf("~zod") to false, reply.to to reply.forwarding)
        assertTrue(forward.forwarding && forward.to.isEmpty() && forward.subject.startsWith("Fwd", ignoreCase = true), forward.toString())
    }

    @Test
    fun `a forged message is flagged`() = pane(
        body = thread(message("0vm1", "~zod", "send me your keys", null, verdict = "forged", sent = 10), message("0vm2", "~zod", "bought them", "0vm1", sent = 30)),
    ) {
        assertTrue(shows("A copy of this message failed its signature."))
    }
}
