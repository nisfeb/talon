package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailList
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mail list's drafts, and the notice left by a send that did not go
 * after its composer had closed.
 */
@OptIn(ExperimentalTestApi::class)
class MailListDraftsTest {
    @Volatile private var sendRefused = false

    private fun repo(): MailRepo {
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/api/send") && sendRefused -> respondError(HttpStatusCode.InternalServerError, "no")
                path.endsWith("/api/drafts") ->
                    respond("""[{"id":"0vd1","to":["~bus"],"subject":"Lunch plans","body":"Noon?","prev":null,"at":5}]""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                else -> respond("""{"ok":true,"threads":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        })
        return MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
    }

    @Test
    fun `the Drafts folder lists what was kept, and opens it`() = runComposeUiTest {
        val mail = repo()
        var opened: Draft? = null
        mail.selectFolder(MailFolder.Drafts)
        setContent {
            TalonTheme(darkTheme = false) {
                MailList(repo = mail, contacts = ContactMap.EMPTY, onOpenThread = {}, onOpenDraft = { opened = it })
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Lunch plans", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Lunch plans", substring = true)[0].performClick()
        waitForIdle()
        assertEquals("0vd1", opened?.id)
    }

    @Test
    fun `a send that failed after its composer closed says so until dismissed`() = runComposeUiTest {
        val mail = repo()
        sendRefused = true
        val why = runBlocking { mail.sendMessage(Draft(id = "0vd2", to = listOf("~bus"), subject = "Lunch", body = "Noon?"), emptyList()).await() }
        assertTrue(why != null, "the send failed")
        setContent {
            TalonTheme(darkTheme = false) {
                MailList(repo = mail, contacts = ContactMap.EMPTY, onOpenThread = {})
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("\"Lunch\" was not sent", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("It is in Drafts.", substring = true).fetchSemanticsNodes().isNotEmpty(), "it says where the message is")
        onAllNodesWithText("Tap to dismiss", substring = true)[0].performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("was not sent", substring = true).fetchSemanticsNodes().isEmpty())
        assertNull(mail.sendProblem.value)
    }
}
