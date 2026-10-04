package io.nisfeb.talon.ui

import androidx.compose.ui.test.assertIsOn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.mock.respond
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.UnreadEntity
import io.nisfeb.talon.ui.screens.HomeScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The home page: its widgets fed from what is stored, each opening what
 * it shows, and the page's arrangement saved when it is changed.
 */
@OptIn(ExperimentalTestApi::class)
class HomeScreenTest {
    private val opened = mutableListOf<String>()
    private var layouts = mutableListOf<HomeLayout>()
    private var invitesOpened = 0
    private var chatsOpened = 0
    private var calendarOpened = 0
    private val calendarWrites = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun home(
        seed: suspend AppDatabase.() -> Unit = {},
        statuses: List<ContactEntity> = emptyList(),
        invites: List<String> = emptyList(),
        calendar: io.nisfeb.talon.calendar.CalendarRepo? = null,
        onInstallCalendar: (suspend () -> Result<Unit>)? = null,
        block: ComposeUiTest.(AppDatabase) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-homepage-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        runBlocking { db.seed() }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        HomeScreen(
                            db = db, mail = null, contacts = ContactMap.EMPTY, ourShip = "~zod",
                            calendar = calendar, onOpenCalendar = { calendarOpened++ }, onInstallCalendar = onInstallCalendar,
                            statuses = statuses, invites = invites,
                            onLayoutChanged = { layouts += it },
                            onOpenInvites = { invitesOpened++ },
                            onOpenConversation = { opened += it }, onOpenChats = { chatsOpened++ },
                            onOpenMailThread = {}, onOpenMail = {},
                        )
                    }
                }
                waitForIdle()
                block(db)
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun msg(whom: String, author: String, text: String, sent: Long) =
        MessageEntity(whom, "$author/$sent", author, sent, """[{"inline":["$text"]}]""", "/chat")

    private fun ComposeUiTest.shows(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private val seeded: suspend AppDatabase.() -> Unit = {
        messages().upsert(msg("~bus", "~bus", "are you coming?", 1_000))
        messages().upsert(msg("~nec", "~nec", "old news", 500))
        unreads().upsert(UnreadEntity("~bus", count = 2, notifyCount = 1, recencyMs = 1_000))
    }

    @Test
    fun `the page greets the ship, and says what this ship does not have`() = home {
        assertTrue(shows("~zod") && shows("Good "), "a greeting by name")
        assertTrue(shows("This ship has no mail app yet."))
        assertTrue(shows("This host has no calendar."))
    }

    @Test
    fun `the chat widget lists conversations newest first and opens them`() = home(seed = seeded) {
        waitUntil(timeoutMillis = 5_000) { shows("old news") }
        val rows = onAllNodes(hasText("are you coming?", substring = true) or hasText("old news", substring = true))
            .fetchSemanticsNodes().map { it.boundsInRoot.top }
        assertTrue(rows.size >= 2 && rows.first() < rows.last(), "newest on top: $rows")
        onAllNodes(hasText("old news", substring = true))[0].performClick()
        waitForIdle()
        assertEquals(listOf("~nec"), opened)
        onNodeWithText("All chats").performClick()
        waitForIdle()
        assertEquals(1, chatsOpened)
    }

    @Test
    fun `what is new holds the unread and the invitations, not what was read`() = home(seed = seeded, invites = listOf("~bus/garden")) {
        waitUntil(timeoutMillis = 5_000) { shows("Group invitation") }
        // "old news" is read: it is in the chat list, and only there.
        assertEquals(1, onAllNodes(hasText("old news", substring = true)).fetchSemanticsNodes().size)
        assertEquals(2, onAllNodes(hasText("are you coming?", substring = true)).fetchSemanticsNodes().size, "in chats and in new")
        onAllNodes(hasText("Group invitation", substring = true))[0].performClick()
        waitForIdle()
        assertEquals(1, invitesOpened)
    }

    @Test
    fun `a long press arranges the page, and a widget taken off is saved gone`() = home {
        onAllNodesWithText("Mail")[0].performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { shows("Done") }
        onNodeWithContentDescription("Take Mail off the home page").performClick()
        waitUntil(timeoutMillis = 5_000) { layouts.isNotEmpty() }
        val saved = layouts.last()
        assertTrue(saved.shown.none { it.kind == HomeWidgetKind.MAIL }, "mail is off the page")
        assertTrue(saved.shown.any { it.kind == HomeWidgetKind.MESSAGES }, "and the rest stays")
        onNodeWithText("Done").performClick()
        waitForIdle()
        assertTrue(!shows("Done"))
    }

    // ─── arranging by hand ─────────────────────────────────────────

    private fun ComposeUiTest.arranging() {
        onAllNodesWithText("Mail")[0].performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { shows("Done") }
    }

    /** A press on [description]'s grip, pulled by [by] in two steps, and let go. */
    private fun ComposeUiTest.pull(description: String, by: Offset) {
        runCatching { onNodeWithContentDescription(description).performScrollTo() }
        onNodeWithContentDescription(description).performTouchInput { down(center); moveBy(by / 2f); moveBy(by / 2f); up() }
        waitForIdle()
    }

    private fun saved(kind: HomeWidgetKind) = layouts.last().shown.single { it.kind == kind }

    @Test
    fun `a grip makes a widget taller or narrower, and the page keeps it`() = home {
        arranging()
        pull("Height of Mail", Offset(0f, 200f))
        waitUntil(timeoutMillis = 5_000) { layouts.isNotEmpty() && saved(HomeWidgetKind.MAIL).rows > 4 }
        pull("Width of Mail", Offset(-200f, 0f))
        waitUntil(timeoutMillis = 5_000) { saved(HomeWidgetKind.MAIL).span < 7 }
    }

    @Test
    fun `the assistant stays square whichever way its corner is pulled`() = home {
        arranging()
        assertTrue(onAllNodesWithContentDescription("Width of Assistant").fetchSemanticsNodes().isEmpty(), "a square has no width of its own")
        pull("Size of Assistant", Offset(0f, 200f))
        waitUntil(timeoutMillis = 5_000) { layouts.isNotEmpty() && saved(HomeWidgetKind.ASSISTANT).rows > 3 }
        assertTrue(saved(HomeWidgetKind.ASSISTANT).span > 3, "taller is wider too: ${saved(HomeWidgetKind.ASSISTANT)}")
    }

    @Test
    fun `a widget dragged across the page is saved where it was dropped`() = home {
        arranging()
        onAllNodesWithText("Mail")[0].performTouchInput { down(center); moveBy(Offset(0f, 150f)); moveBy(Offset(0f, 150f)); up() }
        waitUntil(timeoutMillis = 5_000) { layouts.isNotEmpty() }
        val mail = saved(HomeWidgetKind.MAIL)
        assertTrue(mail.row > 5, "moved down from row 5: $mail")
    }

    // ─── the Today widget ──────────────────────────────────────────

    private val now = System.currentTimeMillis()
    private val todayUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    /** A calendar app on the ship, or none where [present] is false. */
    private fun calendar(present: Boolean = true, offers: String = "{}", writeTakesMs: Long = 0, taskPriority: Int? = null, taskName: String = "Buy milk"): io.nisfeb.talon.calendar.CalendarRepo {
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
            val path = req.url.encodedPath
            val json = { body: String -> respond(body, io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json")) }
            when {
                !present -> respond("", io.ktor.http.HttpStatusCode.NotFound)
                path.startsWith("/grubbery/api/poke/") -> {
                    kotlinx.coroutines.delay(writeTakesMs)
                    calendarWrites += String(req.body.toByteArray()); json("")
                }
                path.endsWith("/window.json") ->
                    json("""{"rows":[{"id":"e1","cal":"default","meta":{"name":"Dentist"},"l":${now - 600_000},"r":${now + 600_000}}]}""")
                // Done once written: the ship's own word after the tick.
                path.endsWith("/events.json") ->
                    json("""[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"$taskName"},"due_ms":$todayUtc${if (calendarWrites.any { "t1" in it }) ""","done":true""" else ""}${taskPriority?.let { ",\"priority\":$it" } ?: ""}}]""")
                path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"}]""")
                path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
                path.endsWith("/share/shares.json") -> json("""{"shares":{},"offers":$offers,"accepted":{}}""")
                else -> json("[]")
            }
        })
        return io.nisfeb.talon.calendar.CalendarRepo(http, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), pollIntervalMs = 60 * 60_000L)
            .apply { attach("https://ship.test"); runBlocking { refresh(); refreshAll() } }
    }

    @Test
    fun `today's events and tasks show, a task is ticked, and a tap opens the calendar`() = home(calendar = calendar()) {
        waitUntil(timeoutMillis = 5_000) { shows("Dentist") && shows("Buy milk") }
        onAllNodes(androidx.compose.ui.test.isToggleable())[0].performClick()
        waitUntil(timeoutMillis = 5_000) { calendarWrites.any { "t1" in it } }
        onNodeWithText("Dentist").performClick()
        assertEquals(1, calendarOpened)
    }

    // The calendar's tasks carry a priority (version 25); the home page's
    // today says it as the Tasks view does.
    // A long title ran into its day: "Get Linus ready for Pi…tomorrow".
    @Test
    fun `a long task title stops short of its day`() = home(
        calendar = calendar(taskName = "Get Linus ready for Pirates picture day and the bake sale after it"),
    ) {
        waitUntil(timeoutMillis = 5_000) { shows("today") }
        // Unmerged: the row is clickable, and merged its texts into one node.
        val title = onNodeWithText("Get Linus", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val day = onNodeWithText("today", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val gap = day.left - title.right
        assertTrue(gap >= with(density) { 8.dp.toPx() } - 0.5f, "title ends ${gap}px before its day")
    }

    @Test
    fun `a task's priority shows on the home page too`() = home(calendar = calendar(taskPriority = 1)) {
        waitUntil(timeoutMillis = 5_000) { shows("Buy milk") && shows("High") }
    }

    // Ticked, then off to the chats and back while the ship was still at
    // it: the box stood unticked for seconds, then the task went.
    @Test
    fun `a tick outlasts going to another section and back, and the task goes once the ship has it`() {
        val cal = calendar(writeTakesMs = 2_000)
        val tmp = createTempDirectory(prefix = "talon-homepage-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        try {
            runComposeUiTest {
                var here by androidx.compose.runtime.mutableStateOf(true)
                setContent {
                    TalonTheme(darkTheme = false) {
                        if (here) {
                            HomeScreen(
                                db = db, mail = null, contacts = ContactMap.EMPTY, ourShip = "~zod",
                                calendar = cal, onOpenCalendar = {}, onInstallCalendar = null,
                                statuses = emptyList(), invites = emptyList(), onLayoutChanged = {},
                                onOpenInvites = {}, onOpenConversation = {}, onOpenChats = {},
                                onOpenMailThread = {}, onOpenMail = {},
                            )
                        } else {
                            Text("The chats")
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { shows("Buy milk") }
                onAllNodes(androidx.compose.ui.test.isToggleable())[0].performClick()
                here = false
                waitForIdle()
                here = true
                waitUntil(timeoutMillis = 5_000) { shows("Buy milk") }
                onAllNodes(androidx.compose.ui.test.isToggleable())[0].assertIsOn()
                // Real time: the write is held on the ship, not on this clock.
                val until = System.currentTimeMillis() + 15_000
                while (shows("Buy milk") && System.currentTimeMillis() < until) { Thread.sleep(50); waitForIdle() }
                assertTrue(!shows("Buy milk"), "the task goes once the ship has it done")
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a calendar shared with you is mentioned, and opens the calendar`() = home(
        calendar = calendar(offers = """{"~nec/work":{"host":"~nec","cal":"work","name":"Work","mode":"read"}}"""),
    ) {
        waitUntil(timeoutMillis = 5_000) { shows("A calendar was shared with you.") }
        onNodeWithText("A calendar was shared with you.").performClick()
        assertEquals(1, calendarOpened)
    }

    @Test
    fun `a ship without the calendar is offered it, and a failed install says why`() = home(
        calendar = calendar(present = false),
        onInstallCalendar = { Result.failure(IllegalStateException("The ship would not install it.")) },
    ) {
        waitUntil(timeoutMillis = 5_000) { shows("Install the calendar") }
        onNodeWithText("Install the calendar").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("The ship would not install it.") }
    }

    // A widget's menu came only from a held press; a mouse right-clicks.
    @Test
    fun `a right-click arranges the page, as a long press does`() = home {
        onAllNodesWithText("Mail")[0].performMouseInput { rightClick() }
        waitForIdle()
        assertTrue(onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty(), "arranging")
    }
}

