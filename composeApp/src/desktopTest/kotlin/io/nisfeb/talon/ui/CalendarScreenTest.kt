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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
    private val HOUR = 3_600_000L
    private val soon = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        when {
            path.startsWith("/grubbery/api/poke/") -> {
                writes += path to req.body.toByteArray().decodeToString()
                json("")
            }
            path.endsWith("/window.json") -> json(
                """{"rows":[
                {"id":"e1","cal":"default","meta":{"name":"Dentist","location":"12 High Street","note":"Ring 020 7946 0958 first, or book at https://dent.example/book"},"l":$soon,"r":${soon + 30 * 60_000L}},
                {"id":"s1","cal":"default","idx":3,"kind":"daily","meta":{"name":"Standup"},"l":${soon + HOUR},"r":${soon + HOUR + 15 * 60_000L}},
                {"id":"b1","cal":"~nec/work","meta":{"name":"Board meeting"},"l":${soon + 2 * HOUR},"r":${soon + 3 * HOUR}}
                ]}""",
            )
            path.endsWith("/event.json") -> json(
                """{"id":"s1","cal":"default","cat":"timed","kind":"daily","start_ms":${soon + HOUR},"dur_min":15,"args":{"at":600},"zone":"none","meta":{"name":"Standup"}}""",
            )
            path.endsWith("/events.json") ->
                json("""[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"}}]""")
            path.endsWith("/calendars.json") ->
                json("""[{"id":"default","name":"Personal","kind":"local"},{"id":"~nec/work","name":"Work","kind":"local"}]""")
            path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
            path.endsWith("/share/shares.json") ->
                json("""{"shares":{},"offers":{},"accepted":{"~nec/work":{"key":"~nec/work","mode":"read"}}}""")
            path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
            else -> json("[]")
        }
    })

    private fun calendar(block: ComposeUiTest.(CalendarRepo) -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        val repo = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L).apply { attach("https://ship.test") }
        runBlocking { repo.refresh(); repo.refreshAll() }
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

    // ─── one event, opened ─────────────────────────────────────────

    private fun ComposeUiTest.open(name: String) {
        onAllNodesWithText(name, substring = true)[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Close") }
    }

    private fun ComposeUiTest.wrote(action: String): String {
        waitUntil(timeoutMillis = 5_000) { writes.any { action in it.second } }
        return writes.last { action in it.second }.second
    }

    @Test
    fun `an event opened offers its place, and the number and link in its note`() = calendar {
        open("Dentist")
        // The dialog brings its own clipboard and link handler, so what
        // is checked is what is offered, not what a tap does.
        for (what in listOf("Copy the place", "Copy the number", "Copy the link")) {
            assertTrue(onAllNodesWithContentDescription(what).fetchSemanticsNodes().size == 1, what)
        }
        assertTrue(onAllNodesWithText("020 7946 0958").fetchSemanticsNodes().size == 1)
        assertTrue(onAllNodesWithText("https://dent.example/book").fetchSemanticsNodes().size == 1)
        assertTrue(shows("Personal"))
    }

    @Test
    fun `deleting an event asks once more, and Keep keeps it`() = calendar {
        open("Dentist")
        onNodeWithText("More").performClick()
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Delete \"Dentist\"?") }
        onNodeWithText("Keep").performClick()
        assertTrue(!shows("Delete \"Dentist\"?") && writes.isEmpty())
        onNodeWithText("More").performClick()
        onNodeWithText("Delete").performClick()
        onNodeWithText("Delete").performClick()
        assertTrue("\"id\":\"e1\"" in wrote("del-event"))
    }

    @Test
    fun `one of a series is skipped, and deleting says it takes the series`() = calendar {
        open("Standup")
        onNodeWithText("More").performClick()
        onNodeWithText("Delete series").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Delete every occurrence of \"Standup\"?") }
        onNodeWithText("Skip this one").performClick()
        val skip = wrote("skip-event")
        assertTrue("\"id\":\"s1\"" in skip && "\"idx\":3" in skip, skip)
    }

    @Test
    fun `this one only, edited, skips the occurrence and adds it back as it now is`() = calendar {
        open("Standup")
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("This change applies to") }
        onNodeWithText("This one only").performScrollTo().performClick()
        field("Name").performTextReplacement("Standup, moved")
        onAllNodesWithText("Save")[0].performClick()
        val skip = wrote("skip-event")
        assertTrue("\"idx\":3" in skip, skip)
        val added = wrote("Standup, moved")
        // A new single event, the series left as it was.
        assertTrue("add-event" in added && Regex("kind.{0,6}once").containsMatchIn(added), added)
        assertTrue(writes.indexOfFirst { "skip-event" in it.second } < writes.indexOfFirst { "Standup, moved" in it.second })
    }

    @Test
    fun `an event on a calendar shared read-only can be read but not changed`() = calendar {
        open("Board meeting")
        assertTrue(shows("Work · shared with you, read-only"))
        for (gone in listOf("Edit", "More", "Done")) assertTrue(onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty(), gone)
    }
}
