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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The calendar screen over a fake of the ship's calendar app: what it
 * shows of the ship's events and tasks, and what it writes back.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarScreenTest {
    /** Every write the screen made: path and body. */
    private val writes: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()
    /** Noon today, where the device is: today's agenda whatever the hour the test runs. */
    private val HOUR = 3_600_000L
    @Volatile private var tasksJson = """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"}}]"""
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
            path.endsWith("/events.json") -> json(tasksJson)
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

    // ─── the new event form ────────────────────────────────────────

    private fun ComposeUiTest.newEvent() {
        onAllNodesWithContentDescription("New event")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Repeats") }
    }

    private fun ComposeUiTest.chip(label: String) = onAllNodesWithText(label).let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()

    private fun ComposeUiTest.save() = onAllNodesWithText("Save")[0].performClick()

    private fun sentMatching(pattern: String) = writes.any { Regex(pattern).containsMatchIn(it.second) }

    @Test
    fun `an event needs a name before it goes`() = calendar {
        newEvent()
        save()
        waitUntil(timeoutMillis = 5_000) { shows("A name is needed.") }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a weekly event needs its weekdays, and goes with the ones picked`() = calendar {
        newEvent()
        field("Name").performTextInput("Choir")
        chip("Weekly")
        save()
        waitUntil(timeoutMillis = 5_000) { shows("Pick the weekdays.") }
        assertTrue(writes.isEmpty())
        chip("Tu")
        save()
        waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
        assertTrue(sentMatching("weekly") && sentMatching("tue"), writes.toString())
    }

    @Test
    fun `a zone the calendar does not know is refused`() = calendar {
        newEvent()
        field("Name").performTextInput("Call with Nec")
        field("Zone").performScrollTo().performTextInput("Mars/Olympus")
        save()
        waitUntil(timeoutMillis = 5_000) { shows("That zone is not one the calendar knows.") }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `an all-day event spans the days asked, and carries its tags`() = calendar {
        newEvent()
        field("Name").performTextInput("Festival")
        chip("All day")
        field("Days").performScrollTo().performTextReplacement("3")
        field("Tags, comma separated").performScrollTo().performTextInput("music, summer")
        save()
        waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
        assertTrue(sentMatching("allday") && sentMatching("span_days\\W+3") && sentMatching("music") && sentMatching("summer"), writes.toString())
    }

    // ─── tasks ─────────────────────────────────────────────────────

    private val dayMs = 86_400_000L
    private val todayUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun ComposeUiTest.tasks() {
        onNodeWithContentDescription("Tasks").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Open") }
    }

    @Test
    fun `a task added in the list goes to the ship, due when asked`() = calendar {
        tasks()
        onNodeWithContentDescription("New task").performClick()
        onNode(hasSetTextAction() and hasText("New task")).performTextInput("Water the plants")
        onNodeWithContentDescription("Add task").performClick()
        waitUntil(timeoutMillis = 5_000) { writes.any { "Water the plants" in it.second } }
        assertTrue(sentMatching("todo"), writes.toString())
    }

    @Test
    fun `searching narrows the tasks, and a search that finds nothing says so`() {
        tasksJson = """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"}},{"id":"t2","cal":"default","cat":"todo","meta":{"name":"Call mum"}}]"""
        calendar {
            tasks()
            waitUntil(timeoutMillis = 5_000) { shows("Call mum") }
            onNodeWithContentDescription("Search tasks").performClick()
            onNode(hasSetTextAction() and hasText("Search tasks")).performTextInput("milk")
            waitUntil(timeoutMillis = 5_000) { !shows("Call mum") }
            assertTrue(shows("Buy milk"))
            onNode(hasSetTextAction() and hasText("milk")).performTextReplacement("zzz")
            waitUntil(timeoutMillis = 5_000) { shows("Nothing matches \"zzz\".") }
        }
    }

    @Test
    fun `the filters count what they hold, late ones are overdue and done ones are kept apart`() {
        tasksJson = """[
            {"id":"t1","cal":"default","cat":"todo","meta":{"name":"File taxes"},"due_ms":${todayUtc - 3 * dayMs}},
            {"id":"t2","cal":"default","cat":"todo","meta":{"name":"Old errand"},"done":true}]"""
        calendar {
            tasks()
            waitUntil(timeoutMillis = 5_000) { shows("File taxes") }
            assertTrue(shows("Overdue 1") && shows("Done 1"), "each chip counts its own")
            assertTrue(!shows("Old errand"), "done is not open")
            onNodeWithText("Done 1").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Old errand") }
            assertTrue(!shows("File taxes"))
        }
    }
}
