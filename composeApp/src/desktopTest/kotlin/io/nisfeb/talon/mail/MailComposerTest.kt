package io.nisfeb.talon.mail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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

        val body = sentBody()
        assertEquals("0vparent", body["prev"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("~zod"),
            body["to"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("answering", body["body"]!!.jsonPrimitive.content)
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
}
