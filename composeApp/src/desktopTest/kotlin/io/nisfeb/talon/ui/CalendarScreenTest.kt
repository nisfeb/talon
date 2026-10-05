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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    /** Every read the screen made, by path. */
    private val reads: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    /** The ship is down, and nginx in front of it answers every request with its 502 page. */
    @Volatile private var down = false
    /** The ship says no to every write. */
    @Volatile private var refuse = false
    /** The ship says no to a skip, and takes everything else. */
    @Volatile private var refuseSkip = false
    /** Writes wait for this: a busy ship, until it completes. */
    @Volatile private var shipTakes: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** The ship's one-event read never answers: a busy ship, at its worst. */
    @Volatile private var holdDetail = false
    /** The ship's calendar keeps reminders: its rows, its event read and its config say so. */
    @Volatile private var reminders = false
    /** The heads-up the ship holds, where it keeps reminders. */
    @Volatile private var leadMin = 30
    private fun alarms(json: String) = if (reminders) ",\"alarms\":$json" else ""
    @Volatile private var tasksJson = """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"}}]"""
    private val soon = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    /** A weekly lesson at 16:00 in New York, from a CalDAV calendar, read in a UTC calendar: today's is on today's UTC date. */
    private val newYork = java.time.ZoneId.of("America/New_York")
    private val lessonDay = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    private val lessonAt = lessonDay.atTime(16, 0).atZone(newYork).toInstant().toEpochMilli()
    /** The series began on Monday 31 August at 16:00, its start written as that wall clock read as UTC. */
    private val lessonJson = """{"id":"f1","cal":"family","cat":"timed","kind":"rrule","start_ms":1788192000000,"zone":"America/New_York","dur_min":60,"fin":"dur","count":0,"args":{"rrule":"FREQ=WEEKLY;UNTIL=20261020T025959Z"},"meta":{"name":"Fencing lesson"}}"""

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        if (down) return@MockEngine respond(io.nisfeb.talon.mail.NGINX_502, HttpStatusCode.BadGateway, headersOf("Content-Type", "text/html"))
        if (!path.startsWith("/grubbery/api/poke/")) reads += path
        if (holdDetail && path.endsWith("/event.json")) kotlinx.coroutines.awaitCancellation()
        when {
            path.startsWith("/grubbery/api/poke/") && refuse -> { shipTakes?.await(); respond("", HttpStatusCode.BadRequest) }
            path.startsWith("/grubbery/api/poke/") -> {
                shipTakes?.await()
                val body = req.body.toByteArray().decodeToString()
                if (refuseSkip && "skip-event" in body) return@MockEngine respond("", HttpStatusCode.BadRequest)
                writes += path to body
                json("")
            }
            path.endsWith("/window.json") -> json(
                """{"rows":[
                {"id":"e1","cal":"default","meta":{"name":"Dentist","location":"12 High Street","note":"Ring 020 7946 0958 first, or book at https://dent.example/book"},"l":$soon,"r":${soon + 30 * 60_000L}${alarms("""[{"kind":"before","s":900,"desc":""}]""")}},
                {"id":"s1","cal":"default","idx":3,"kind":"daily","meta":{"name":"Standup"},"l":${soon + HOUR},"r":${soon + HOUR + 15 * 60_000L}${alarms("[]")}},
                {"id":"b1","cal":"~nec/work","meta":{"name":"Board meeting"},"l":${soon + 2 * HOUR},"r":${soon + 3 * HOUR}${alarms("[]")}},
                {"id":"f1","cal":"family","idx":5,"kind":"rrule","meta":{"name":"Fencing lesson"},"l":$lessonAt,"r":${lessonAt + HOUR}${alarms("[]")}}
                ]}""",
            )
            path.endsWith("/event.json") && req.url.parameters["id"] == "f1" -> json(lessonJson)
            path.endsWith("/event.json") -> json(
                """{"id":"s1","cal":"default","cat":"timed","kind":"daily","start_ms":${soon + HOUR},"dur_min":15,"args":{"at":600},"zone":"none","meta":{"name":"Standup"}${alarms("""[{"kind":"offset","from":"end","after":true,"s":0,"desc":"wrap up"}]""")}}""",
            )
            path.endsWith("/events.json") -> json(tasksJson)
            path.endsWith("/calendars.json") ->
                json("""[{"id":"default","name":"Personal","kind":"local"},{"id":"~nec/work","name":"Work","kind":"local"},{"id":"family","name":"Family","kind":"caldav"}]""")
            path.endsWith("/config.json") -> json(if (reminders) """{"title":"Calendar","zone":"UTC","ball":"abc123","lead_min":$leadMin}""" else """{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
            path.endsWith("/share/shares.json") ->
                json("""{"shares":{},"offers":{},"accepted":{"~nec/work":{"key":"~nec/work","mode":"read"}}}""")
            path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
            path.endsWith("/zones.json") -> json("""["UTC","America/New_York"]""")
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

    // ─── task priority ────────────────────────────────────────────

    // "b4bp calendar is adding priority to tasks. talon should too".
    @Test
    fun `tasks show their priority, most pressing first, and the editor sets it`() {
        tasksJson = """[
            {"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"},"priority":0},
            {"id":"t2","cal":"default","cat":"todo","meta":{"name":"Pay rent"},"priority":1}]"""
        calendar {
            onNodeWithContentDescription("Tasks").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Pay rent") }
            assertTrue(shows("High"))
            val top = { name: String -> onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top }
            assertTrue(top("Pay rent") < top("Buy milk"), "the high one first, as the calendar's page has it")
            onNodeWithText("Buy milk").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Edit").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Priority") }
            chip("Medium")
            save()
            val body = wrote("edit-event")
            assertTrue("\"priority\":5" in body, body)
        }
    }

    @Test
    fun `a task edit that leaves the priority alone sends none, and the ship keeps it`() {
        tasksJson = """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"},"priority":3}]"""
        calendar {
            onNodeWithContentDescription("Tasks").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Buy milk") }
            onNodeWithText("Buy milk").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("High priority") }
            onNodeWithText("Edit").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Priority") }
            field("Name").performTextReplacement("Buy oat milk")
            save()
            assertTrue("priority" !in wrote("Buy oat milk"), "a 3 it did not set goes back untouched")
        }
    }

    // A calendar older than priority is offered none and sent none.
    @Test
    fun `a calendar that does not keep a priority is offered none`() = calendar {
        onNodeWithContentDescription("Tasks").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Buy milk") }
        onNodeWithText("Buy milk").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Edit task") }
        assertTrue(!shows("Priority"))
        field("Name").performTextReplacement("Buy oat milk")
        save()
        assertTrue("priority" !in wrote("Buy oat milk"))
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
    fun `a task's editor opens at once, and its save reads back only what it changed`() {
        holdDetail = true
        calendar {
            onNodeWithContentDescription("Tasks").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Buy milk") }
            onNodeWithText("Buy milk").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Edit").performClick()
            // It waited on the ship's copy of the task, which a busy ship took minutes to send.
            waitUntil(timeoutMillis = 2_000) { shows("Edit task") }
            field("Name").performTextReplacement("Buy oat milk")
            // The ship will have it once written: the read-back finds it moved.
            tasksJson = """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy oat milk"}}]"""
            reads.clear()
            onAllNodesWithText("Save")[0].performClick()
            waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
            assertTrue("Buy oat milk" in writes.single().second, writes.single().second)
            waitUntil(timeoutMillis = 5_000) { reads.any { it.endsWith("/events.json") } }
            waitForIdle()
            assertTrue(
                reads.none { it.endsWith("/calendars.json") || it.endsWith("/shares.json") || it.endsWith("/google.json") },
                "a task's save reads the lists it changed, not the calendars: $reads",
            )
            assertTrue(reads.none { it.endsWith("/window.json") }, "an undated task is in no window: $reads")
        }
    }

    // "saving a todo edit takes way too long": the task list kept the old
    // task until the ship had answered, seconds on a busy one.
    private fun ComposeUiTest.renameMilk() {
        onNodeWithContentDescription("Tasks").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Buy milk") }
        onNodeWithText("Buy milk").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 2_000) { shows("Edit task") }
        field("Name").performTextReplacement("Buy oat milk")
        onAllNodesWithText("Save")[0].performClick()
    }

    @Test
    fun `a task edit is in the task list at Save, before a slow ship answers`() {
        holdDetail = true
        val takes = kotlinx.coroutines.CompletableDeferred<Unit>()
        shipTakes = takes
        calendar {
            renameMilk()
            waitUntil(timeoutMillis = 2_000) { shows("Buy oat milk") && !shows("Edit task") }
            assertTrue(!shows("Buy milk"), "the old name is gone")
            assertTrue(writes.isEmpty(), "and the ship has not taken it yet")
            tasksJson = """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy oat milk"}}]"""
            takes.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
            waitForIdle()
            assertTrue(shows("Buy oat milk"))
        }
    }

    @Test
    fun `a task edit the ship refuses goes back to what it was`() {
        holdDetail = true
        refuse = true
        calendar { repo ->
            renameMilk()
            waitUntil(timeoutMillis = 5_000) { shows("did not take") }
            // The list is back as it was; the change waits in the editor it
            // reopened, to try again.
            assertEquals("Buy milk", repo.tasks.value?.single { it.id == "t1" }?.name)
            assertTrue(onAllNodes(hasSetTextAction() and hasText("Buy oat milk")).fetchSemanticsNodes().isNotEmpty())
        }
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
    fun `this one only, edited, adds it back as it now is and then skips the occurrence`() = calendar {
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
        // Added first: skipping first lost the occurrence when the add was refused.
        assertTrue(writes.indexOfFirst { "Standup, moved" in it.second } < writes.indexOfFirst { "skip-event" in it.second })
    }

    /** Opens the lesson's editor on today's occurrence and sets its hour. */
    private fun ComposeUiTest.moveLesson(scope: String?, hour: String) {
        open("Fencing lesson")
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("This change applies to") }
        // It opens on the occurrence, at 16:00 where the lesson is, not on the
        // day the series began.
        assertTrue(shows(io.nisfeb.talon.util.formatDate(kotlinx.datetime.LocalDate(lessonDay.year, lessonDay.monthValue, lessonDay.dayOfMonth))), "opens on today's lesson")
        if (scope != null) onNodeWithText(scope).performScrollTo().performClick()
        onNode(hasSetTextAction() and hasText("16")).performTextReplacement(hour)
        onAllNodesWithText("Save")[0].performClick()
    }
    private fun wallMs(d: java.time.LocalDate, h: Int) = d.atTime(h, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    // "I've moved Magnus fencing to 5pm on the calendar twice and it still doesn't save."
    @Test
    fun `a series moved to five, every occurrence, moves on the ship`() = calendar {
        moveLesson(scope = null, hour = "17")
        val edit = wrote("edit-event")
        assertTrue("\"start_ms\":${wallMs(java.time.LocalDate.of(2026, 8, 31), 17)}" in edit, "from its own first day, an hour later: $edit")
        assertTrue("FREQ=WEEKLY;UNTIL=20261020T025959Z" in edit && "\"cal\":\"family\"" in edit && "America/New_York" in edit, edit)
        assertTrue(writes.none { "skip-event" in it.second || "add-event" in it.second }, "the series itself, nothing added")
    }

    @Test
    fun `one lesson moved to five, this one only, is a one-off at five and the old one skipped`() = calendar {
        moveLesson(scope = "This one only", hour = "17")
        val skip = wrote("skip-event")
        assertTrue("\"id\":\"f1\"" in skip && "\"idx\":5" in skip, skip)
        val added = writes.first { "add-event" in it.second }.second
        assertTrue("\"start_ms\":${wallMs(lessonDay, 17)}" in added && Regex("kind.{0,6}once").containsMatchIn(added) && "America/New_York" in added, added)
        assertTrue(writes.none { "edit-event" in it.second }, "the series left as it was")
    }

    @Test
    fun `this and following restarts the series at five on that day, not at midnight`() = calendar {
        moveLesson(scope = "This and following", hour = "17")
        val cap = wrote("cap-event")
        assertTrue("\"dom\":5" in cap, cap)
        val added = wrote("add-event")
        assertTrue("\"start_ms\":${wallMs(lessonDay, 17)}" in added && "FREQ=WEEKLY" in added, added)
    }

    @Test
    fun `a move whose skip the ship refuses says the old one is still there, and does not offer to add it twice`() = calendar {
        refuseSkip = true
        moveLesson(scope = "This one only", hour = "17")
        waitUntil(timeoutMillis = 5_000) { shows("kept the old one too") }
        assertEquals(1, writes.count { "add-event" in it.second })
        assertFalse(shows("This change applies to"), "the editor stays closed")
    }

    @Test
    fun `an event on a calendar shared read-only can be read but not changed`() = calendar {
        open("Board meeting")
        assertTrue(shows("Work · shared with you, read-only"))
        for (gone in listOf("Edit", "More", "Done")) assertTrue(onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty(), gone)
    }

    // ─── reminders ─────────────────────────────────────────────────

    // "add reminder configuration and editing to talon".
    @Test
    fun `an event's reminders show on its row and in its details, and a new one can have one`() {
        reminders = true
        calendar {
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Has reminders").fetchSemanticsNodes().size == 1 }
            open("Dentist")
            assertTrue(shows("15 min before"))
            onNodeWithText("Close").performClick()
            newEvent()
            field("Name").performTextInput("Lunch with Bus")
            chip("+ 30 min before")
            assertTrue(onAllNodesWithContentDescription("Remove 30 min before").fetchSemanticsNodes().size == 1)
            save()
            val body = wrote("Lunch with Bus")
            assertTrue(""""alarms":[{"kind":"before","s":1800,"desc":""}]""" in body, body)
        }
    }

    // An edit that does not touch them sends none, and the ship keeps
    // them; one that does sends the whole list, a kind this app does not
    // edit going back as it came.
    @Test
    fun `an edit that leaves the reminders alone sends none, and the ship keeps them`() {
        reminders = true
        calendar {
            open("Standup")
            onNodeWithText("Edit").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("At the end") }
            field("Name").performTextReplacement("Standup, renamed")
            save()
            assertTrue("alarms" !in wrote("Standup, renamed"), "untouched, not sent")
        }
    }

    @Test
    fun `an edit to the reminders sends the whole list, keeping a kind this app does not edit`() {
        reminders = true
        calendar {
            open("Standup")
            onNodeWithText("Edit").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("At the end") }
            chip("+ 5 min before")
            save()
            val body = wrote("edit-event")
            assertTrue(""""alarms":[{"kind":"offset","from":"end","after":true,"s":0,"desc":"wrap up"},{"kind":"before","s":300,"desc":""}]""" in body, body)
        }
    }

    @Test
    fun `this one only takes the series' reminders with it`() {
        reminders = true
        calendar {
            open("Standup")
            onNodeWithText("Edit").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("This change applies to") }
            onNodeWithText("This one only").performScrollTo().performClick()
            field("Name").performTextReplacement("Standup, moved")
            save()
            val added = wrote("Standup, moved")
            assertTrue(""""alarms":[{"kind":"offset","from":"end","after":true,"s":0,"desc":"wrap up"}]""" in added, added)
        }
    }

    // A calendar older than reminders shows none and is sent none: one set
    // there would go nowhere.
    @Test
    fun `a calendar that does not keep reminders is offered none`() = calendar {
        waitForIdle()
        assertTrue(onAllNodesWithContentDescription("Has reminders").fetchSemanticsNodes().isEmpty())
        newEvent()
        assertTrue(!shows("Reminders") && !shows("before"))
        field("Name").performTextInput("Lunch with Bus")
        save()
        assertTrue("alarms" !in wrote("Lunch with Bus"))
        onNodeWithContentDescription("Calendars").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("New calendar") }
        assertTrue(!shows("Heads-up before every timed event"))
    }

    // Set elsewhere to a value this page does not offer, it still shows as the one chosen.
    @Test
    fun `a heads-up set elsewhere shows as chosen`() {
        reminders = true
        leadMin = 120
        calendar {
            onNodeWithContentDescription("Calendars").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Heads-up before every timed event") }
            assertTrue(onAllNodes(hasText("120 min") and androidx.compose.ui.test.isSelected()).fetchSemanticsNodes().size == 1)
        }
    }

    @Test
    fun `the heads-up before every timed event is set, or turned off`() {
        reminders = true
        calendar { repo ->
            onNodeWithContentDescription("Calendars").performClick()
            waitUntil(timeoutMillis = 5_000) { shows("Heads-up before every timed event") }
            chip("Off")
            val body = wrote("lead_min")
            assertTrue("""{"action":"config","lead_min":0}""" == body, body)
            waitUntil(timeoutMillis = 5_000) { repo.leadMin.value == 0 }
        }
    }

    // ─── the new event form ────────────────────────────────────────

    private fun ComposeUiTest.newEvent() {
        onAllNodesWithContentDescription("New event")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Repeats") }
    }

    private fun ComposeUiTest.chip(label: String) = onAllNodesWithText(label).let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()

    private fun ComposeUiTest.save() = onAllNodesWithText("Save")[0].performClick()

    private fun sentMatching(pattern: String) = writes.any { Regex(pattern).containsMatchIn(it.second) }

    // "now calendar says the ship isn't taking my changes to events": the
    // ship was down for a restart. Said as that, and the edit is kept.
    @Test
    fun `a save while the ship is down says so, and the editor comes back with the change to try again`() = calendar {
        open("Dentist")
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Repeats") }
        field("Name").performTextReplacement("Dentist, moved")
        down = true
        save()
        waitUntil(timeoutMillis = 5_000) { shows("Your ship isn't answering") }
        assertTrue(onAllNodes(hasSetTextAction() and hasText("Dentist, moved")).fetchSemanticsNodes().isNotEmpty(), "the edit is still there")
        assertTrue(!shows("did not take") && !shows("<html"), "not a refusal, and no markup")
        assertTrue(writes.isEmpty())
        down = false
        save()
        wrote("Dentist, moved")
    }

    @Test
    fun `a save the ship refuses still says the ship did not take it`() = calendar {
        open("Dentist")
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Repeats") }
        refuse = true
        save()
        waitUntil(timeoutMillis = 5_000) { shows("The ship did not take the change") }
        assertTrue(!shows("isn't answering"))
    }

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

    // ─── sending an event on ───────────────────────────────────────

    private val mailed = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var mailRefuses = false

    private val mailHttp = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val json = { body: String -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        when {
            path.endsWith("/api/blob") -> json("""{"hash":"0vics"}""")
            path.endsWith("/api/send") && mailRefuses -> respond("""{"error":"outbox full"}""", HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
            path.endsWith("/api/send") -> { mailed += req.body.toByteArray().decodeToString(); json("{}") }
            else -> json("""{"ok":true,"threads":[]}""")
        }
    })

    /** The calendar with the chats and the mail to send an event on through; [roster] is the group's, or none. */
    private fun sharing(roster: Boolean, block: ComposeUiTest.(io.nisfeb.talon.urbit.FakeShip) -> Unit) {
        val tmp = kotlin.io.path.createTempDirectory(prefix = "talon-cal-share-").toFile()
        val db = androidx.room.Room.databaseBuilder<io.nisfeb.talon.data.AppDatabase>(java.io.File(tmp, "t.db").absolutePath)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = io.nisfeb.talon.urbit.FakeShip("~zod").apply {
            if (roster) scries["groups/v2/groups/~bus/garden"] = """{"meta":{"title":"The Garden","description":"","image":"","cover":""},"admins":[],
                "seats":{"~bus":{"roles":[],"joined":0},"~zod":{"roles":[],"joined":0},"~nec":{"roles":[],"joined":0}},
                "admissions":{"privacy":"private","banned":{"ships":[],"ranks":[]},"invited":{},"pending":{},"requests":{}}}"""
        }
        val chat = io.nisfeb.talon.urbit.TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val scope = CoroutineScope(SupervisorJob())
        val events = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        ship.channel.events().launchIn(events)
        runBlocking {
            db.groups().upsertGroups(listOf(io.nisfeb.talon.data.GroupEntity("~bus/garden", "The Garden", null)))
            db.groups().upsertChannelGroups(listOf(io.nisfeb.talon.data.ChannelGroupEntity("chat/~bus/seeds", "~bus/garden", title = "Seed swap")))
            db.messages().upsert(io.nisfeb.talon.data.MessageEntity("chat/~bus/seeds", "~bus/1", "~bus", 1_000, """[{"inline":["hello"]}]""", "/chat"))
        }
        val repo = CalendarRepo(http, scope, pollIntervalMs = 60 * 60_000L).apply { attach("https://ship.test") }
        val mail = io.nisfeb.talon.mail.MailRepo(mailHttp, scope, pollIntervalMs = 60 * 60_000L).apply { attach("https://ship.test") }
        runBlocking { repo.refresh(); repo.refreshAll() }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        CalendarScreen(repo = repo, twentyFourHour = true, onBack = {}, db = db, chat = chat, mail = mail, ourShip = "~zod")
                    }
                }
                waitUntil(timeoutMillis = 5_000) { shows("Dentist") }
                block(ship)
            }
        } finally {
            runBlocking { chat.stopAndJoinForTest() }
            scope.cancel()
            events.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shareWithGroup() {
        open("Dentist")
        onNodeWithText("More").performClick()
        onNodeWithText("Share with a group").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Seed swap") }
        onAllNodesWithText("Seed swap", substring = true)[0].performClick()
    }

    @Test
    fun `an event shared with a group is posted there, and its members are mailed the invite`() = sharing(roster = true) { ship ->
        shareWithGroup()
        waitUntil(timeoutMillis = 5_000) { shows("and mailed the invite to 2 ships.") }
        assertTrue(ship.pokesTo("channels").any { "Dentist" in it.json.toString() })
        val sent = mailed.single()
        assertTrue("~bus" in sent && "~nec" in sent && "~zod" !in sent && "event.ics" in sent, sent)
    }

    @Test
    fun `a group whose members cannot be read is posted to, and says no invites went`() = sharing(roster = false) { ship ->
        shareWithGroup()
        waitUntil(timeoutMillis = 5_000) { shows("The member list could not be read, so no invites were mailed.") }
        assertTrue(ship.pokesTo("channels").any { "Dentist" in it.json.toString() })
        assertTrue(mailed.isEmpty())
    }

    // "The group could not be reached" after the post had gone out had
    // people share it again, and post it twice.
    @Test
    fun `a share whose post went but whose invites did not says which half went`() = sharing(roster = true) { ship ->
        mailRefuses = true
        shareWithGroup()
        waitUntil(timeoutMillis = 5_000) { shows("but the invite could not be mailed") }
        assertTrue(ship.pokesTo("channels").any { "Dentist" in it.json.toString() })
        assertTrue(!shows("The group could not be reached."))
    }

    // A slip of the thumb outside the dialog lost a half-written event.
    @Test
    fun `a dismiss closes the editor only while nothing was written in it`() = calendar {
        onAllNodesWithContentDescription("New event")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Repeats") }
        field("Name").performTextInput("Lunch with Bus")
        tapOutside()
        assertTrue(shows("Repeats"), "kept open with something written in it")
        onAllNodesWithText("Cancel")[0].performClick()
        waitForIdle()
        assertTrue(!shows("Repeats"), "Cancel still closes it")

        onAllNodesWithContentDescription("New event")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Repeats") }
        tapOutside()
        assertTrue(!shows("Repeats"), "nothing written: it closes")
    }

    private fun ComposeUiTest.tapOutside() {
        onAllNodes(androidx.compose.ui.test.isRoot())[0].performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, 2f)) }
        waitForIdle()
    }
}

