package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.ui.screens.CalendarScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The calendar screen over a fake of the ship's calendar app: what it
 * shows of the ship's events and tasks, and what it writes back.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarScreenTest {
    /** Every write the screen made: path and body. */
    private val writes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    /** Noon today, where the device is: today's agenda whatever the hour the test runs. */
    private val soon = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        when {
            path.startsWith("/grubbery/api/poke/") -> {
                writes += path to req.body.toByteArray().decodeToString()
                json("")
            }
            path.endsWith("/window.json") ->
                json("""{"rows":[{"id":"e1","cal":"default","meta":{"name":"Dentist"},"l":$soon,"r":${soon + 30 * 60_000L}}]}""")
            path.endsWith("/events.json") ->
                json("""[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"}}]""")
            path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"}]""")
            path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
            path.endsWith("/share/shares.json") -> json("""{"shares":{},"offers":{},"accepted":{}}""")
            path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
            else -> json("[]")
        }
    })

    private fun calendar(block: ComposeUiTest.(CalendarRepo) -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        val repo = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L).apply { attach("https://ship.test") }
        runBlocking { repo.refresh() }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        CalendarScreen(repo = repo, twentyFourHour = true, onBack = {})
                    }
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Dentist", substring = true).fetchSemanticsNodes().isNotEmpty() }
                block(repo)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.shows(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.field(label: String) = onNode(hasSetTextAction() and hasText(label))

    @Test
    fun `today's events are listed on today with their times`() = calendar {
        // Shown in the calendar's own zone, UTC here: local noon is 16:00 in summer.
        val row = onAllNodes(hasText("Dentist") and hasText("–", substring = true)).fetchSemanticsNodes()
        assertTrue(row.isNotEmpty(), "listed with its start and end")
    }

    @Test
    fun `a new event goes to the ship with what was typed, and Cancel sends nothing`() = calendar {
        onAllNodesWithContentDescription("New event")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Repeats") }
        onAllNodesWithText("Cancel")[0].performClick()
        waitForIdle()
        assertTrue(writes.isEmpty(), "cancelled")

        onAllNodesWithContentDescription("New event")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Repeats") }
        field("Name").performTextInput("Lunch with Bus")
        onAllNodesWithText("Save")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
        val body = writes.single().second
        assertTrue("Lunch with Bus" in body, body)
    }

    @Test
    fun `an open task is listed, and ticking it writes it done`() = calendar {
        onNodeWithContentDescription("Tasks").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Buy milk") }
        assertTrue(shows("Open 1"))
        onAllNodes(isToggleable())[0].performClick()
        waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
        assertTrue("t1" in writes.single().second, writes.single().second)
    }

    @Test
    fun `the month moves on and back, and Today comes home`() = calendar {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.ENGLISH)
        val now = java.time.YearMonth.now()
        assertTrue(shows(now.format(fmt)))
        onNodeWithContentDescription("Next month").performClick()
        waitUntil(timeoutMillis = 5_000) { shows(now.plusMonths(1).format(fmt)) }
        onNodeWithContentDescription("Previous month").performClick()
        onNodeWithContentDescription("Previous month").performClick()
        waitUntil(timeoutMillis = 5_000) { shows(now.minusMonths(1).format(fmt)) }
        onAllNodesWithText("Today")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows(now.format(fmt)) }
    }
}
