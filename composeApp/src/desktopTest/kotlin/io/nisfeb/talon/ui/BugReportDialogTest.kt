package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.mail.MailAvailability
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.util.LogBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings, About, Report a bug: what the user wrote and this run's logs
 * reach the developer's ship as Auspex mail from the user's own, through
 * a stand-in Auspex (2026-10-11).
 */
@OptIn(ExperimentalTestApi::class)
class BugReportDialogTest {
    /** The ship takes the send when this completes. */
    private val shipTakes = CompletableDeferred<Unit>()
    @Volatile private var mailHere = true
    @Volatile private var sendStatus = HttpStatusCode.OK
    private val blobs = CopyOnWriteArrayList<String>()
    private val sends = CopyOnWriteArrayList<String>()
    private val copied = CopyOnWriteArrayList<String>()

    private val scope = CoroutineScope(SupervisorJob())
    private val repo = MailRepo(
        HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val json = { s: HttpStatusCode, b: String -> respond(b, s, headersOf("Content-Type", "application/json")) }
            when {
                !mailHere -> json(HttpStatusCode.NotFound, if (path.endsWith("desks/stock")) "" else """{"error":"not found"}""")
                path.endsWith("/api/inbox") -> json(HttpStatusCode.OK, """{"total":0,"offset":0,"limit":50,"view":"inbox","threads":[]}""")
                path.endsWith("/api/drafts") -> json(HttpStatusCode.OK, "[]")
                path.endsWith("/api/blob") -> {
                    blobs += req.body.toByteArray().decodeToString()
                    json(HttpStatusCode.OK, """{"hash":"0vlog"}""")
                }
                path.endsWith("/api/send") && req.method == HttpMethod.Post -> {
                    sends += req.body.toByteArray().decodeToString()
                    shipTakes.await()
                    json(sendStatus, if (sendStatus == HttpStatusCode.OK) "{}" else """{"error":"the vendor said no"}""")
                }
                path.endsWith("/api/whoami") -> json(HttpStatusCode.OK, """{"ship":"~zod"}""")
                else -> json(HttpStatusCode.OK, "{}")
            }
        }),
        scope,
        pollIntervalMs = 60 * 60 * 1000L,
    )

    @AfterTest
    fun close() {
        shipTakes.complete(Unit)
        scope.cancel()
    }

    private var shown by mutableStateOf(true)

    private fun dialog(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                CompositionLocalProvider(LocalClipboardManager provides object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) { copied += annotatedString.text }
                }) {
                    if (shown) ReportBugDialog(repo, listOf("Version" to "9.9.9"), onDismiss = { shown = false })
                }
            }
        }
        repo.attach("https://ship.test")
        block()
    }

    private fun ComposeUiTest.shows(t: String) = onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.write(t: String) = onNode(hasSetTextAction()).performTextInput(t)
    private fun ComposeUiTest.ready() = waitUntil(timeoutMillis = 10_000) { repo.availability.value == MailAvailability.PRESENT }
    private fun sent() = Json.parseToJsonElement(sends.single()).jsonObject

    @Test
    fun `a report reaches the developer as mail from the user's ship, the logs as a file`() {
        // Logged before the form opens: it sends the lines it showed.
        LogBuffer.add("INFO ", "Test", "marker-4d1f")
        dialog { sendsWithLogs() }
    }

    private fun ComposeUiTest.sendsWithLogs() {
        ready()
        onNodeWithText("Send").assertIsNotEnabled()
        write("Calls drop after a minute")
        onNodeWithText("Send").assertIsEnabled().performClick()
        shipTakes.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { shows("Report sent") }
        assertTrue("marker-4d1f" in blobs.single(), "this run's logs are the file")
        val mail = sent()
        assertEquals(listOf(BUG_REPORT_TO), mail["to"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Talon bug: Calls drop after a minute", mail["subject"]!!.jsonPrimitive.content)
        val body = mail["body"]!!.jsonPrimitive.content
        assertTrue(body.startsWith("Calls drop after a minute\n\nVersion: 9.9.9\nLogs: "), body)
        val file = mail["attachments"]!!.jsonArray.single().jsonObject
        assertEquals(BUG_REPORT_LOG_FILE, file["name"]!!.jsonPrimitive.content)
        assertEquals("text/plain", file["mime"]!!.jsonPrimitive.content)
        assertEquals("0vlog", file["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without the logs ticked, no file goes`() = dialog {
        ready()
        write("Typing lags")
        onNode(androidx.compose.ui.test.isToggleable()).performClick()
        onNodeWithText("Send").performClick()
        shipTakes.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { shows("Report sent") }
        assertTrue(blobs.isEmpty(), "no logs uploaded")
        assertTrue(sent()["attachments"]!!.jsonArray.isEmpty())
        assertTrue(sent()["body"]!!.jsonPrimitive.content.endsWith("Logs: not sent"))
    }

    @Test
    fun `a send the ship refuses says so, and can be sent again`() = dialog {
        sendStatus = HttpStatusCode.BadRequest
        shipTakes.complete(Unit)
        ready()
        write("Calls drop")
        onNodeWithText("Send").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Not sent:") }
        assertTrue(!shows("Report sent"))
        onNodeWithText("Send").assertIsEnabled()
    }

    @Test
    fun `leaving while it sends does not stop it`() = dialog {
        ready()
        write("Calls drop")
        onNodeWithText("Send").performClick()
        waitUntil(timeoutMillis = 10_000) { sends.isNotEmpty() }
        shown = false
        waitForIdle()
        // Real time: the send waits on the ship from the repo's scope, not the dialog's.
        shipTakes.complete(Unit)
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (repo.outbox.value.isNotEmpty()) kotlinx.coroutines.delay(20)
            }
        }
        assertTrue(repo.unsent.value.isEmpty(), "it went: ${repo.unsent.value.map { it.line }}")
    }

    @Test
    fun `a ship without Auspex offers the report to copy, logs and all`() {
        mailHere = false
        LogBuffer.add("INFO ", "Test", "marker-77c2")
        dialog {
            waitUntil(timeoutMillis = 10_000) { shows("Sending needs Auspex mail") }
            assertTrue(onAllNodesWithText("Send").fetchSemanticsNodes().isEmpty(), "nothing to send through")
            write("Calls drop")
            onNodeWithText("Copy report").performClick()
            val text = copied.single()
            assertTrue(text.startsWith("Talon bug: Calls drop\n\nCalls drop\n\nVersion: 9.9.9"), text)
            assertTrue("marker-77c2" in text, "the logs come with it")
            assertTrue(sends.isEmpty())
        }
    }
}
