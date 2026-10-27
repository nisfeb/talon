package io.nisfeb.talon.mail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailList
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Users missed new mail while they read Sent. The poll reads the inbox
 * beside the open folder; its unread count goes on the Inbox row, and
 * a dot on the mailbox button where the folders are behind it.
 */
@OptIn(ExperimentalTestApi::class)
class MailInboxIndicatorTest {

    /** Whether the inbox holds the new mail yet. */
    @Volatile private var arrived = false

    private fun thread(id: String, subject: String, unread: Boolean) =
        """{"id":"$id","subject":"$subject","from":"~zod","snippet":"","verdict":"verified","forged":false,
            "count":1,"last":0,"unread":$unread,"participants":["~zod"],"unreadable":0,"archived":false,"labels":[]}"""

    private fun repo(): MailRepo {
        val http = HttpClient(MockEngine { req ->
            val body = when (req.url.parameters["view"]) {
                "inbox" -> if (arrived) {
                    """{"total":2,"offset":0,"limit":50,"view":"inbox","threads":[${thread("0v2", "New one", true)},${thread("0v1", "Old one", false)}]}"""
                } else {
                    """{"total":1,"offset":0,"limit":50,"view":"inbox","threads":[${thread("0v1", "Old one", false)}]}"""
                }
                "sent" -> """{"total":1,"offset":0,"limit":50,"view":"sent","threads":[${thread("0v9", "My reply", false)}]}"""
                else -> """{"total":0,"offset":0,"limit":50,"view":"x","threads":[]}"""
            }
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        // A fast poll: the arrival is read the way the ten-minute one reads it.
        return MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 300)
    }

    private fun ComposeUiTest.shows(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** Mail arrives in the inbox while Sent is open. */
    private fun ComposeUiTest.newMailWhileOnSent(repo: MailRepo) {
        repo.attach("https://ship.example")
        waitUntil(timeoutMillis = 5_000) { shows("Old one") }
        repo.selectFolder(MailFolder.View(MailView.SENT))
        waitUntil(timeoutMillis = 5_000) { shows("My reply") }
        arrived = true
    }

    @Test
    fun `with the folder column, the Inbox row counts new mail while Sent is open`() = runComposeUiTest {
        val repo = repo()
        setContent { TalonTheme(darkTheme = false) { MailList(repo = repo, contacts = ContactMap.EMPTY, onOpenThread = {}) } }
        newMailWhileOnSent(repo)
        // The Inbox row's count, beside its name.
        waitUntil(timeoutMillis = 10_000) { shows("1") }
        assertTrue(shows("Inbox") && shows("My reply"), "still on Sent, the count on the row")
    }

    @Test
    fun `without the column, the mailbox button carries a dot, and the sheet the count`() = runComposeUiTest {
        val repo = repo()
        setContent {
            TalonTheme(darkTheme = false) {
                Box(Modifier.width(420.dp)) { MailList(repo = repo, contacts = ContactMap.EMPTY, onOpenThread = {}) }
            }
        }
        newMailWhileOnSent(repo)
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasContentDescription("Mailboxes, unread mail in Inbox")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Mailboxes, unread mail in Inbox").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Inbox") && shows("1") }
        // In the inbox itself the list says it, so the button carries no dot.
        repo.selectFolder(MailFolder.View(MailView.INBOX))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Mailboxes")).fetchSemanticsNodes().isNotEmpty() }
    }
}
