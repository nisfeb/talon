package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.nisfeb.talon.ui.screens.MailComposer
import io.nisfeb.talon.ui.screens.MailIntent
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Writing one message. The claim worth pinning hardest is the
 * disclosure: a forward tells the writer how much travels, and to
 * people who have not seen it, BEFORE they choose who that is.
 */
class MailComposerTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun repo(): MailRepo {
        val http = HttpClient(
            MockEngine { req ->
                seen += req
                respond(
                    ByteReadChannel("""{"ok":true,"threads":[]}"""),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        return MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L)
            .also { it.attach("https://ship.example") }
    }

    /** The last draft saved, which is what a send without files goes out as. */
    private fun draftBody() = Json.parseToJsonElement(
        (seen.last { it.url.encodedPath.endsWith("/api/draft") }.body as TextContent).text,
    ).jsonObject

    private fun sentBody() = Json.parseToJsonElement(
        (seen.last { it.url.encodedPath.endsWith("/api/send") }.body as TextContent).text,
    ).jsonObject

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a forward warns what travels, and to whom, before recipients are chosen`() =
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    MailComposer(
                        repo = repo(),
                        intent = MailIntent(
                            prev = "0vparent",
                            to = emptyList(),
                            subject = "Plans",
                            travels = 3,
                            forwarding = true,
                        ),
                        onSent = {},
                        onCancel = {},
                    )
                }
            }
            onNodeWithText("3 signed messages travel to people who have not seen them.")
                .assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a reply names its parent and keeps the conversation's recipients`() = runComposeUiTest {
        var sent = false
        setContent {
            TalonTheme(darkTheme = false) {
                MailComposer(
                    repo = repo(),
                    intent = MailIntent(
                        prev = "0vparent",
                        to = listOf("~zod"),
                        subject = "Plans",
                        travels = 2,
                    ),
                    onSent = { sent = true },
                    onCancel = {},
                )
            }
        }
        onNodeWithText("~zod").assertIsDisplayed()
        onNodeWithText("Message").performTextInput("answering")
        onNodeWithText("Send").performClick()
        waitUntil(timeoutMillis = 5_000) { sent }

        // A message with no files goes out as the draft it was saved
        // as: the ship deletes one only when the send has landed, and
        // that absence is the only proof a poke can give that it was
        // applied rather than merely accepted.
        val body = draftBody()
        assertEquals("0vparent", body["prev"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("~zod"),
            body["to"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("answering", body["body"]!!.jsonPrimitive.content)
        val sendReq = seen.last { it.url.encodedPath.endsWith("/api/draft-send") }
        assertEquals(
            body["id"]!!.jsonPrimitive.content,
            Json.parseToJsonElement((sendReq.body as TextContent).text).jsonObject["id"]!!.jsonPrimitive.content,
            "the draft that was saved is the one the ship is asked to send",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a name that is not a ship is refused by name rather than dropped`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                MailComposer(
                    repo = repo(),
                    intent = MailIntent(),
                    onSent = {},
                    onCancel = {},
                )
            }
        }
        onNodeWithText("To").performTextInput("~zod nonsense")
        onNodeWithText("Add recipient").performClick()
        onNodeWithText("Not a ship: nonsense").assertIsDisplayed()
        // The good one still landed.
        onNodeWithText("~zod").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `closing with something written keeps it as a draft`() = runComposeUiTest {
        var closed = false
        // Closing takes the composer off the screen, as both shells do
        // by clearing what is being composed. The save is the dispose's,
        // so that the button itself need not wait for the ship.
        val showing = androidx.compose.runtime.mutableStateOf(true)
        setContent {
            TalonTheme(darkTheme = false) {
                if (showing.value) {
                    MailComposer(
                        repo = repo(),
                        intent = MailIntent(),
                        onSent = {},
                        onCancel = { closed = true; showing.value = false },
                    )
                }
            }
        }
        onNodeWithText("Message").performTextInput("half a thought")
        onNodeWithContentDescription("Close").performClick()
        waitUntil(timeoutMillis = 5_000) { closed }
        waitUntil(timeoutMillis = 5_000) { seen.any { it.url.encodedPath.endsWith("/api/draft") } }
        val saved = seen.last { it.url.encodedPath.endsWith("/api/draft") }
        val body = Json.parseToJsonElement((saved.body as TextContent).text).jsonObject
        assertEquals("half a thought", body["body"]!!.jsonPrimitive.content)
        assertTrue(
            body["id"]!!.jsonPrimitive.content.startsWith("0v"),
            "the client mints the id, because the route answers before the writer applies",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `closing an empty composer writes nothing`() = runComposeUiTest {
        var closed = false
        setContent {
            TalonTheme(darkTheme = false) {
                MailComposer(
                    repo = repo(),
                    intent = MailIntent(),
                    onSent = {},
                    onCancel = { closed = true },
                )
            }
        }
        onNodeWithContentDescription("Close").performClick()
        waitUntil(timeoutMillis = 5_000) { closed }
        assertTrue(seen.none { it.url.encodedPath.endsWith("/api/draft") })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a message with nobody to send it to does not go`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                MailComposer(
                    repo = repo(),
                    intent = MailIntent(),
                    onSent = {},
                    onCancel = {},
                )
            }
        }
        onNodeWithText("Message").performTextInput("hello")
        onNodeWithText("Send").performClick()
        onNodeWithText("Say who this is going to.").assertIsDisplayed()
        assertTrue(seen.none { it.url.encodedPath.endsWith("/api/send") })
    }

    /** The last save the composer asked the ship for, or null. */
    private fun savedDraft() = seen.lastOrNull { it.url.encodedPath.endsWith("/api/draft") }
        ?.let { Json.parseToJsonElement((it.body as TextContent).text).jsonObject }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `leaving the composer by any route keeps what was written`() = runComposeUiTest {
        val r = repo()
        // Compose state, so that clearing it really does take the
        // composer out of composition.
        val showing = androidx.compose.runtime.mutableStateOf(true)
        setContent {
            TalonTheme(darkTheme = false) {
                if (showing.value) {
                    MailComposer(
                        repo = r,
                        intent = MailIntent(to = listOf("~zod")),
                        onSent = {},
                        onCancel = {},
                    )
                }
            }
        }
        onNodeWithText("Message").performTextInput("half a thought")
        waitForIdle()
        // Switching section takes the composer out of composition: no
        // back button, no cancel, the way mail to chat does it.
        showing.value = false
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { savedDraft() != null }
        assertEquals("half a thought", savedDraft()?.get("body")?.jsonPrimitive?.content)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `what was typed survives the screen being rebuilt`() = runComposeUiTest {
        val r = repo()
        // One intent, two mountings: a window crossing a layout width,
        // a rail tab, a section switch all do this, and every remember
        // in the composer dies in between.
        val intent = MailIntent(to = listOf("~zod"))
        val wide = androidx.compose.runtime.mutableStateOf(false)
        setContent {
            TalonTheme(darkTheme = false) {
                if (wide.value) {
                    androidx.compose.foundation.layout.Box {
                        MailComposer(repo = r, intent = intent, onSent = {}, onCancel = {})
                    }
                } else {
                    MailComposer(repo = r, intent = intent, onSent = {}, onCancel = {})
                }
            }
        }
        onNodeWithText("Message").performTextInput("half a thought")
        onNodeWithText("Subject").performTextInput("Plans")
        waitForIdle()
        wide.value = true
        waitForIdle()
        onNodeWithText("half a thought").assertIsDisplayed()
        onNodeWithText("Plans").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a reply whose thread is not known says what it carries anyway`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                MailComposer(
                    repo = repo(),
                    // A draft, as it comes back from the ship: it says
                    // what it answers and nothing about how much of the
                    // conversation goes with it.
                    intent = MailIntent(prev = "0vparent", draftId = "0vdraft", to = listOf("~bus")),
                    onSent = {},
                    onCancel = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("This reply carries the conversation it answers to whoever you name.")
            .assertIsDisplayed()
    }
}
