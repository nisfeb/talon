package io.nisfeb.talon.mail

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.screens.MailComposer
import io.nisfeb.talon.ui.screens.MailIntent
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writing an answer with what it answers in view: the message replied to,
 * the ones before it on request, and lines of it quoted into the answer.
 */
@OptIn(ExperimentalTestApi::class)
class MailAnsweringTest {
    private val thread = """{"id":"0vt","messages":[
        {"id":"0vm1","from":"~bus","to":["~zod","~nec"],"subject":"Lunch","body":"Meet at noon.\nBring snacks.","body-mime":"","sent":1000,"prev":null,"verdict":"verified","read":true},
        {"id":"0vm2","from":"~nec","to":["~zod","~bus"],"subject":"Re: Lunch","body":"I cannot make noon.\nOne works.","body-mime":"","sent":2000,"prev":"0vm1","verdict":"verified","read":true}],
        "participants":["~zod","~bus","~nec"],"last":2000,"unreadable":0,"archived":false,"labels":[]}"""

    private fun repo(): MailRepo {
        val http = HttpClient(MockEngine { req ->
            val body = when {
                req.url.encodedPath.endsWith("/api/thread/0vt") -> thread
                req.url.encodedPath.endsWith("/api/drafts") -> "[]"
                else -> """{"ok":true,"threads":[]}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        return MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
    }

    private fun composing(intent: MailIntent, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                MailComposer(repo = repo(), intent = intent, onSent = {}, onCancel = {}, nameFor = { mapOf("~bus" to "Bus", "~nec" to "Nec")[it] ?: it })
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Replying to", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Forwarding", substring = true).fetchSemanticsNodes().isNotEmpty() }
        block()
    }

    private val reply = MailIntent(prev = "0vm2", threadId = "0vt", to = listOf("~nec", "~bus"), subject = "Re: Lunch")

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a reply shows what it answers, by name, and the rest on request`() = composing(reply) {
        assertTrue(shows("Replying to Nec"))
        assertTrue(shows("I cannot make noon."), "the answered message, in full")
        assertTrue(!shows("Meet at noon."), "the earlier one waits to be asked for")
        onNodeWithText("Show 1 included message").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Meet at noon.") }
    }

    @Test
    fun `quoting takes whole lines of the answered message into the answer`() = composing(reply) {
        // Part of the first line picked: the whole line is what is quoted.
        onNode(hasText("I cannot make noon.", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))
            .performTextInputSelection(TextRange(2, 8))
        waitUntil(timeoutMillis = 5_000) { shows("Quote") && !shows("Select lines above") }
        onNodeWithText("Quote").performClick() // with the pointer, as a mouse would
        waitForIdle()
        val answer = onNode(hasSetTextAction() and hasText("Message")).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertTrue(answer.startsWith("> I cannot make noon."), answer)
        assertTrue("One works." !in answer, "only the line picked: $answer")
    }

    @Test
    fun `a forward says it is forwarding`() = composing(reply.copy(forwarding = true, to = emptyList(), subject = "Fwd: Lunch")) {
        assertTrue(shows("Forwarding Nec"))
    }
}
