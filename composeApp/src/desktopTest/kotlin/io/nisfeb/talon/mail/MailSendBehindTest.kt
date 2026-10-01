package io.nisfeb.talon.mail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailIntent
import io.nisfeb.talon.ui.screens.MailWorkspace
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.util.PickedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "I need to be able to click on and look at other mail while a message
 * is sending. not just go to other tabs." The composer held the reading
 * pane until the ship took the message.
 */
@OptIn(ExperimentalTestApi::class)
class MailSendBehindTest {
    /** The ship takes the send when this completes: a slow ship until then. */
    private val shipTakes = CompletableDeferred<Unit>()
    @Volatile private var sendStatus = HttpStatusCode.OK
    @Volatile private var draftStatus = HttpStatusCode.OK
    private val sent = CopyOnWriteArrayList<String>()

    private fun row(id: String, subject: String) =
        """{"id":"$id","subject":"$subject","from":"~bus","snippet":"","verdict":"valid","forged":false,"count":1,"last":0,
            "unread":false,"participants":["~bus"],"unreadable":0,"archived":false,"labels":[]}"""

    private val scope = CoroutineScope(SupervisorJob())
    private val repo = MailRepo(
        HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val json = { s: HttpStatusCode, b: String -> respond(b, s, headersOf("Content-Type", "application/json")) }
            when {
                path.endsWith("/api/inbox") -> json(HttpStatusCode.OK, """{"total":2,"offset":0,"limit":50,"view":"inbox","threads":[${row("0v1", "Lunch")},${row("0v2", "Rent")}]}""")
                path.endsWith("/api/thread/0v2") -> json(HttpStatusCode.OK, """{"id":"0v2","messages":[
                    {"id":"0vm","from":"~bus","to":["~zod"],"subject":"Rent","body":"Rent is due Friday.","body-mime":"","sent":1000,"prev":null,"verdict":"verified","read":true}],
                    "participants":["~zod","~bus"],"last":1000,"unreadable":0,"archived":false,"labels":[]}""")
                path.endsWith("/api/drafts") -> json(HttpStatusCode.OK, "[]")
                path.endsWith("/api/draft") -> json(draftStatus, if (draftStatus == HttpStatusCode.OK) "{}" else "<html>502 Bad Gateway</html>")
                path.endsWith("/api/blob") -> json(HttpStatusCode.OK, """{"hash":"0vh"}""")
                path.endsWith("/api/send") && req.method == HttpMethod.Post -> {
                    sent += req.body.toByteArray().decodeToString()
                    shipTakes.await()
                    json(sendStatus, if (sendStatus == HttpStatusCode.OK) "{}" else """{"error":"no"}""")
                }
                path.endsWith("/api/whoami") -> json(HttpStatusCode.OK, """{"ship":"~zod"}""")
                else -> json(HttpStatusCode.OK, "{}")
            }
        }),
        scope,
        pollIntervalMs = 60 * 60 * 1000L,
    )

    private fun ComposeUiTest.shows(t: String) = onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The mail workspace at desktop width, writing [intent]. */
    private fun writing(intent: MailIntent, block: ComposeUiTest.() -> Unit) = try {
        runComposeUiTest {
            var open by mutableStateOf<String?>(null)
            var composing by mutableStateOf<MailIntent?>(intent)
            setContent {
                TalonTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(1000.dp, 760.dp)) {
                        MailWorkspace(repo, ContactMap.EMPTY, "~zod", open, { open = it }, composing, { composing = it })
                    }
                }
            }
            repo.attach("https://ship.test")
            waitUntil(timeoutMillis = 5_000) { shows("Rent") && shows("New message") }
            onNode(hasSetTextAction() and hasText("Message")).performTextInput("Hi there")
            block()
        }
    } finally {
        shipTakes.complete(Unit)
        scope.cancel()
    }

    private val hello = MailIntent(to = listOf("~bus"), subject = "Hello")

    @Test
    fun `while a message sends, other mail can be opened and read`() = writing(hello) {
        onNodeWithText("Send").performClick()
        // Gone at once, and the list says it is on its way.
        waitUntil(timeoutMillis = 5_000) { !shows("New message") && shows("Sending \"Hello\"") }
        onNodeWithText("Rent").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Rent is due Friday.") }
        assertTrue(shows("Sending \"Hello\""), "still going while the other is read")
        shipTakes.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { !shows("Sending \"Hello\"") }
        assertTrue("Hi there" in sent.single())
    }

    @Test
    fun `a send the ship refuses comes back from the list whole, its file too`() {
        hello.edits.files.add(PickedImage(ByteArray(8) { 1 }, "image/png", "cat.png"))
        sendStatus = HttpStatusCode.BadRequest
        shipTakes.complete(Unit)
        writing(hello) {
            onNodeWithText("Send").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("\"Hello\" was not sent") }
            onNodeWithText("Open").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("New message") }
            assertTrue(onAllNodes(hasSetTextAction() and hasText("Hi there")).fetchSemanticsNodes().isNotEmpty(), "the text")
            assertTrue(shows("cat.png"), "the file")
            assertTrue(!shows("was not sent"), "opened, so no longer waiting on the list")
        }
    }

    // A ship that is down takes no draft either: the text lives only here.
    @Test
    fun `a message the ship never got, not even as a draft, comes back`() {
        draftStatus = HttpStatusCode.BadGateway
        writing(hello) {
            onNodeWithText("Send").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("\"Hello\" was not sent") }
            assertTrue(!shows("It is in Drafts"), "it is not")
            onNodeWithText("Open").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction() and hasText("Hi there")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun `two that fail are both kept, and putting one away leaves the other`() = runBlocking<Unit> {
        draftStatus = HttpStatusCode.BadGateway
        repo.attach("https://ship.test")
        repo.sendMessage(io.nisfeb.talon.mail.Draft(id = "0vd1", to = listOf("~bus"), subject = "One", body = "1"), emptyList()).await()
        repo.sendMessage(io.nisfeb.talon.mail.Draft(id = "0vd2", to = listOf("~bus"), subject = "Two", body = "2"), emptyList()).await()
        assertEquals(listOf("1", "2"), repo.unsent.value.map { it.draft.body })
        repo.dismiss(repo.unsent.value.last())
        assertEquals(listOf("1"), repo.unsent.value.map { it.draft.body })
        assertTrue(repo.sendProblem.value!!.startsWith("\"One\" was not sent"))
        assertTrue(repo.outbox.value.isEmpty(), "nothing left on its way")
        scope.cancel()
    }
}
