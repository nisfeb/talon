package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
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

    // The repo's own poller asks at the same time the test does, and a
    // plain list throws when one thread walks it while another adds.
    private val seen = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    /** A ship that takes everything. [draftsListed] is what its drafts list says. */
    private fun repo(draftsListed: () -> String = { "[]" }): MailRepo {
        val http = HttpClient(
            MockEngine { req ->
                seen += req
                val body = if (req.url.encodedPath.endsWith("/api/drafts")) draftsListed() else """{"ok":true,"threads":[]}"""
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

    /** The last draft saved. */
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

        // One send path since auspex 14: the send route, then the draft
        // it was saved as dropped.
        val body = sentBody()
        assertEquals("0vparent", body["prev"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("~zod"),
            body["to"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("answering", body["body"]!!.jsonPrimitive.content)
        waitUntil(timeoutMillis = 5_000) { seen.any { it.url.encodedPath.endsWith("/api/draft-delete") } }
        val dropped = seen.last { it.url.encodedPath.endsWith("/api/draft-delete") }
        assertEquals(
            draftBody()["id"]!!.jsonPrimitive.content,
            Json.parseToJsonElement((dropped.body as TextContent).text).jsonObject["id"]!!.jsonPrimitive.content,
            "the draft that was saved is the one dropped",
        )
        assertTrue(seen.none { it.url.encodedPath.endsWith("/api/draft-send") }, "a route auspex 14 no longer has")
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

    // A reply's recipients sit in the To field rather than in chips
    // beside it, so nothing has committed them when the composer goes
    // away. The draft that is kept has to read them off the field.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a reply left half written keeps who it was going to`() = runComposeUiTest {
        var closed = false
        val showing = androidx.compose.runtime.mutableStateOf(true)
        setContent {
            TalonTheme(darkTheme = false) {
                if (showing.value) {
                    MailComposer(
                        repo = repo(),
                        intent = MailIntent(prev = "0vparent", to = listOf("~zod"), subject = "Plans"),
                        onSent = {},
                        onCancel = { closed = true; showing.value = false },
                    )
                }
            }
        }
        onNodeWithText("Message").performTextInput("later")
        onNodeWithContentDescription("Close").performClick()
        waitUntil(timeoutMillis = 5_000) { closed }
        waitUntil(timeoutMillis = 5_000) { seen.any { it.url.encodedPath.endsWith("/api/draft") } }
        val saved = seen.last { it.url.encodedPath.endsWith("/api/draft") }
        val body = Json.parseToJsonElement((saved.body as TextContent).text).jsonObject
        assertEquals(
            listOf("~zod"),
            body["to"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the reply was addressed before a word of it was written",
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

    // A recipient is a name to tap for their card and a cross to take
    // them off. A tap used to take them off, which nothing said.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tap on a recipient opens their card, and only the cross removes them`() = runComposeUiTest {
        var opened: String? = null
        setContent {
            TalonTheme(darkTheme = false) {
                androidx.compose.runtime.CompositionLocalProvider(
                    io.nisfeb.talon.ui.LocalOpenProfile provides { ship: String -> opened = ship },
                ) {
                    MailComposer(
                        repo = repo(),
                        intent = MailIntent(prev = null, to = emptyList(), subject = "Hi"),
                        onSent = {},
                        onCancel = {},
                    )
                }
            }
        }
        onNodeWithText("To").performTextInput("~zod")
        onNodeWithText("Add recipient").performClick()
        waitForIdle()
        onNodeWithText("~zod").performClick()
        waitForIdle()
        assertEquals("~zod", opened, "the card was asked for")
        onNodeWithText("~zod").assertIsDisplayed()
        onNodeWithContentDescription("Remove ~zod").performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("~zod").fetchSemanticsNodes().isEmpty(), "and the cross took them off")
    }

    // A send runs on the repo's scope: leaving the composer while it is
    // out used to cancel it, and the message sat in Drafts unsent.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `leaving while a message sends does not stop it`() = runComposeUiTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = HttpClient(
            MockEngine { req ->
                calls += req.url.encodedPath
                // The ship holds the send until the composer is gone.
                if (req.url.encodedPath.endsWith("/api/send")) gate.await()
                val body = if (req.url.encodedPath.endsWith("/api/drafts")) "[]" else """{"ok":true,"threads":[]}"""
                respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        )
        val repo = MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L)
            .also { it.attach("https://ship.example") }
        val showing = androidx.compose.runtime.mutableStateOf(true)
        setContent {
            TalonTheme(darkTheme = false) {
                if (showing.value) {
                    MailComposer(
                        repo = repo,
                        intent = MailIntent(prev = "0vparent", to = listOf("~zod"), subject = "Plans"),
                        onSent = {},
                        onCancel = {},
                    )
                }
            }
        }
        onNodeWithText("Message").performTextInput("on my way")
        onNodeWithText("Send").performClick()
        waitUntil(timeoutMillis = 5_000) { calls.any { it.endsWith("/api/send") } }
        showing.value = false
        waitForIdle()
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { calls.any { it.endsWith("/api/draft-delete") } }
        // And the way out filed nothing behind the send: a draft saved
        // after the send dropped it came back as a message still to send.
        waitForIdle()
        val all = calls.toList()
        val afterDelete = all.drop(all.indexOfLast { it.endsWith("/api/draft-delete") })
        assertTrue(afterDelete.none { it.endsWith("/api/draft") }, "$all")
        assertEquals(1, all.count { it.endsWith("/api/draft") }, "saved once, before the send: $all")
    }
}
