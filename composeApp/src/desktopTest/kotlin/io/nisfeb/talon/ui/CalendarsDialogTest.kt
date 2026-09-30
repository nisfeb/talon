package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
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
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.ui.screens.CalendarScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Managing calendars: an offer from another ship taken, a calendar
 * made, renamed and deleted, shared and its share revoked, each checked
 * by what reaches the ship's calendar app.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarsDialogTest {
    private val writes: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        if (req.method == HttpMethod.Post) writes += path to req.body.toByteArray().decodeToString()
        when {
            path.startsWith("/grubbery/api/poke/") -> json("")
            path.endsWith("/share/share") -> json("""{"told":true}""")
            path.contains("/share/") && req.method == HttpMethod.Post -> json("""{"ok":true}""")
            path.endsWith("/calendars.json") ->
                json("""[{"id":"default","name":"Personal","kind":"local","count":3},{"id":"garden","name":"Garden","kind":"local","count":1}]""")
            path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
            path.endsWith("/share/shares.json") ->
                json("""{"shares":{"garden":{"~bus":"read"}},"offers":{"~nec/work":{"host":"~nec","cal":"work","name":"Work","mode":"edit"}},"accepted":{}}""")
            path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
            path.endsWith("/window.json") -> json("""{"rows":[]}""")
            else -> json("[]")
        }
    })

    private fun calendars(block: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        val repo = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L).apply { attach("https://ship.test") }
        runBlocking { repo.refresh(); repo.refreshAll() }
        try {
            runComposeUiTest {
                setContent { TalonTheme(darkTheme = false) { CalendarScreen(repo = repo, twentyFourHour = true, onBack = {}) } }
                onNodeWithContentDescription("Calendars").performClick()
                waitUntil(timeoutMillis = 5_000) { shows("Offered to you") }
                block()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** A calendar's row in the dialog: its name, not the chips that also carry it. */
    private fun ComposeUiTest.openRow(name: String) =
        onAllNodes(hasText(name) and SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)).onFirst().performClick()

    private fun ComposeUiTest.wrote(path: String): String {
        waitUntil(timeoutMillis = 5_000) { writes.any { it.first.endsWith(path) || it.second.contains(path) } }
        return writes.last { it.first.endsWith(path) || it.second.contains(path) }.second
    }

    @Test
    fun `an offer from another ship is shown and accepted`() = calendars {
        assertTrue(shows("from ~nec · read and edit"))
        onNodeWithText("Accept").performClick()
        assertTrue("\"key\":\"~nec/work\"" in wrote("/share/accept"))
    }

    @Test
    fun `a new calendar is made with its name and colour`() = calendars {
        onAllNodes(hasSetTextAction() and hasText("Name")).onLast().performScrollTo().performTextInput("Book Club")
        onAllNodes(hasSetTextAction() and hasText("Colour, #rrggbb")).onLast().performTextInput("#336699")
        onAllNodesWithText("Add").onLast().performScrollTo().performClick()
        val add = wrote("add-calendar")
        assertTrue("\"id\":\"book-club\"" in add && "\"name\":\"Book Club\"" in add && "#336699" in add, add)
    }

    @Test
    fun `a calendar opened is shared, and a share revoked after asking`() = calendars {
        openRow("Garden")
        waitUntil(timeoutMillis = 5_000) { shows("~bus · read only") }
        onNode(hasSetTextAction() and hasText("Share with a ship")).performScrollTo().performTextInput("~ridlur-figbud")
        onNodeWithText("Read and edit").performClick()
        onNodeWithText("Share").performClick()
        val share = wrote("/share/share")
        assertTrue("\"ship\":\"~ridlur-figbud\"" in share && "\"mode\":\"edit\"" in share, share)

        onAllNodesWithText("Revoke")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("it just stops syncing") }
        onAllNodesWithText("Revoke")[0].performClick()
        assertTrue("\"ship\":\"~bus\"" in wrote("/share/revoke"))
    }

    @Test
    fun `deleting a calendar says what goes with it, and Keep keeps it`() = calendars {
        openRow("Garden")
        onNodeWithText("Delete").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Delete it and its 1 event") }
        onNodeWithText("Keep").performClick()
        assertTrue(writes.none { "del-calendar" in it.second })
        onNodeWithText("Delete").performClick()
        onNodeWithText("Delete it and its 1 event").performClick()
        assertTrue("\"id\":\"garden\"" in wrote("del-calendar"))
    }

    @Test
    fun `the calendar every new event goes to cannot be deleted`() = calendars {
        openRow("Personal")
        waitUntil(timeoutMillis = 5_000) { shows("Save") }
        assertTrue(onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty())
    }
}
