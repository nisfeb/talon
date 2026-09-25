package io.nisfeb.talon.mail

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.ui.screens.MailLabelRow
import io.nisfeb.talon.ui.screens.MailOrganiseSheet
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sorting mail: filters and lists made, refused when they would not
 * work, and deleted; labels put on and taken off a thread.
 */
@OptIn(ExperimentalTestApi::class)
class MailOrganiseTest {
    private val posts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    private fun organise(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            if (req.method == HttpMethod.Post) posts += path to req.body.toByteArray().decodeToString()
            val body = when {
                path.endsWith("/api/rules") -> """[{"id":"r1","from":"~bus","subject":null,"add":["bills"],"archive":true}]"""
                path.endsWith("/api/lists") -> """[{"name":"team","members":["~zod","~nec"]}]"""
                else -> """{"ok":true,"threads":[]}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val repo = MailRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example") }
        // Loaded before the sheet opens: under the test harness this sheet
        // stops redrawing after its first update (a bare sheet fed the
        // same way does not), so later changes are checked by what is sent.
        runBlocking { repo.refreshRules(); repo.refreshLists() }
        setContent { TalonTheme(darkTheme = false) { MailOrganiseSheet(repo = repo, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("from ~bus", substring = true).fetchSemanticsNodes().isNotEmpty() }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.field(label: String) = onNode(hasSetTextAction() and hasText(label))

    private fun ComposeUiTest.posted(path: String): String {
        waitUntil(timeoutMillis = 5_000) { posts.any { it.first.endsWith(path) } }
        return posts.last { it.first.endsWith(path) }.second
    }

    @Test
    fun `filters and lists show what they do`() = organise {
        assertTrue(shows("from ~bus") && shows("label bills, archive"))
        assertTrue(shows("team"))
    }

    @Test
    fun `a filter is made from a sender, a subject, a label and archiving`() = organise {
        field("From").performScrollTo().performTextInput("~nec")
        field("Subject contains").performTextInput("invoice")
        field("Add label").performTextInput("bills")
        onNode(isToggleable()).performClick()
        onNodeWithText("Add filter").performScrollTo().performClick()
        val rule = posted("/api/rule")
        for (part in listOf("\"from\":\"~nec\"", "\"subject\":\"invoice\"", "\"add\":[\"bills\"]", "\"archive\":true")) {
            assertTrue(part in rule, "$part in $rule")
        }
    }

    @Test
    fun `a filter that would match everything is refused here`() = organise {
        onNodeWithText("Add filter").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("or it matches everything") }
        assertTrue(posts.none { it.first.endsWith("/api/rule") }, "nothing sent")
    }

    @Test
    fun `deleting a filter asks the ship to`() = organise {
        onAllNodesWithText("Delete")[0].performClick()
        assertEquals("""{"id":"r1"}""", posted("/api/rule-delete"))
    }

    @Test
    fun `labels come off with a tap, and go on from those in use or typed`() {
        val did = mutableListOf<String>()
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    MailLabelRow(labels = listOf("bills"), known = listOf("bills", "invoices", "travel")) { l, on -> did += "$l $on" }
                }
            }
            onNodeWithContentDescription("Take off bills").performClick()
            onNodeWithText("+ travel").performClick()
            onNode(hasSetTextAction()).performTextInput("inv")
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("+ travel").fetchSemanticsNodes().isEmpty() }
            onNodeWithText("+ invoices").performClick()
        }
        assertEquals(listOf("bills false", "travel true", "invoices true"), did)
    }
}
