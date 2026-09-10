package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.screens.MailThreadPane
import io.nisfeb.talon.ui.screens.sizeLabel
import io.nisfeb.talon.ui.screens.unreadableThreadLine
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reader. The claims here are the ones the tree exists for: what a
 * reply would carry is stated, it changes when the selection changes,
 * and a forged copy says so and cannot be selected to answer.
 */
class MailThreadPaneTest {

    /** A root with two replies, so the thread branches. */
    private val branching = """
        {"id":"0vt","participants":["~zod","~nec"],"last":40,"unreadable":0,
         "archived":false,"labels":[],"messages":[
          {"id":"0vroot","from":"~zod","to":["~nec"],"subject":"Plans",
           "body":"the root","sent":10,"prev":null,"verdict":"verified","read":true},
          {"id":"0vmine","from":"~nec","to":["~zod"],"subject":"Plans",
           "body":"my branch","sent":20,"prev":"0vroot","verdict":"verified","read":true},
          {"id":"0vtheirs","from":"~zod","to":["~nec"],"subject":"Plans",
           "body":"their branch","sent":30,"prev":"0vroot","verdict":"verified","read":true}]}
    """.trimIndent()

    private fun repoServing(body: String): MailRepo {
        val http = HttpClient(
            MockEngine {
                respond(
                    ByteReadChannel(body),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        return MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L)
            .also { it.attach("https://ship.example") }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the tree says what a reply from the selected message would carry`() = runComposeUiTest {
        val repo = repoServing(branching)
        setContent {
            TalonTheme(darkTheme = false) {
                MailThreadPane(repo = repo, threadId = "0vt", contacts = ContactMap.EMPTY)
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("the root").assertIsDisplayed(); true }.getOrDefault(false)
        }
        // A branching thread offers the tree; a straight one would not.
        onNodeWithText("Tree").performClick()

        // The default is the newest honest message, which is one of the
        // two branches: root plus that branch, and never its sibling.
        waitUntil(timeoutMillis = 5_000) {
            runCatching {
                onNodeWithText("2 signed messages travel with a reply from here.")
                    .assertIsDisplayed()
                true
            }.getOrDefault(false)
        }

        // Selecting the root narrows what travels to the root alone.
        onNodeWithText("the root").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runCatching {
                onNodeWithText("1 signed message travels with a reply from here.")
                    .assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a forged copy says why it cannot be answered`() = runComposeUiTest {
        val repo = repoServing(
            """{"id":"0vt","participants":["~zod"],"last":10,"unreadable":0,
                "archived":false,"labels":[],"messages":[
                 {"id":"0vf","from":"~zod","subject":"Invoice","body":"pay me",
                  "sent":10,"prev":null,"verdict":"forged","read":true}]}""",
        )
        setContent {
            TalonTheme(darkTheme = false) {
                MailThreadPane(repo = repo, threadId = "0vt", contacts = ContactMap.EMPTY)
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("FORGED").assertIsDisplayed(); true }.getOrDefault(false)
        }
        onNodeWithText(
            "This copy's signature does not match its contents. It is kept as " +
                "evidence and cannot be answered.",
        ).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a body that asked for another format is shown as plain text and says so`() =
        runComposeUiTest {
            val repo = repoServing(
                """{"id":"0vt","participants":["~zod"],"last":10,"unreadable":0,
                    "archived":false,"labels":[],"messages":[
                     {"id":"0vh","from":"~zod","subject":"Hi","body":"<b>bold</b>",
                      "body-mime":"text/html","sent":10,"prev":null,
                      "verdict":"verified","read":true}]}""",
            )
            setContent {
                TalonTheme(darkTheme = false) {
                    MailThreadPane(repo = repo, threadId = "0vt", contacts = ContactMap.EMPTY)
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    onNodeWithText("Shown as plain text; this message asked for text/html.")
                        .assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            // The markup is text, not markup: it is rendered verbatim.
            onNodeWithText("<b>bold</b>").assertIsDisplayed()
        }

    @Test
    fun `sizes and unreadable counts read as sentences`() {
        assertEquals("unknown size", sizeLabel(0))
        assertEquals("512 B", sizeLabel(512))
        assertEquals("2 KB", sizeLabel(2048))
        assertTrue(unreadableThreadLine(1).startsWith("1 copy here is"))
        assertTrue(unreadableThreadLine(3).startsWith("3 copies here are"))
    }
}
