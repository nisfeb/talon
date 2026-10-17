package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.compose.App
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextReplacement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The whole desktop app against a [FakeShip]: getting into a section and,
 * above all, out of it again. Sections shipped stuck more than once
 * (CLAUDE.md, rule 7).
 */
@OptIn(ExperimentalTestApi::class)
class AppShellTest {

    private fun app(
        seed: suspend AppDatabase.() -> Unit = {},
        ai: FakeAiSettings = FakeAiSettings(),
        ui: UiSettings = InMemoryUiSettings(),
        // The test window is 1024dp wide: Modifier.size clamps anything
        // wider to that, so 1200 here is really 1024.
        width: androidx.compose.ui.unit.Dp = 1200.dp,
        /** The ship as it is before the app first asks it anything. */
        setup: FakeShip.() -> Unit = {},
        block: ComposeUiTest.(FakeShip) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-app-").toFile()
        // The chat list's snapshot is per ship and outlives a test, list
        // state and all. Every test here is ~zod, as other classes are, and
        // one left behind by a class run before this one broke the first
        // test's opening frame ("pending composition has not been
        // applied"), only in the full suite. HomeListTest does the same.
        io.nisfeb.talon.ui.screens.forgetHomeListSnapshot("~zod")
        // A ship with nothing yet: an empty feed and no invites, which is
        // not the same as one that does not answer.
        val ship = FakeShip("~zod").apply {
            scries["activity/v6/feed/init/30"] = """{"all":[]}"""
            scries["groups-ui/v7/init"] = """{"foreigns":{}}"""
            setup()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(width = width, height = 800.dp)) {
                        App(
                            http = ship.http,
                            sessionStore = ship.session,
                            aiSettings = ai,
                            createDb = { key ->
                                Room.databaseBuilder<AppDatabase>(File(tmp, "$key.db").absolutePath)
                                    .setDriver(BundledSQLiteDriver())
                                    .fallbackToDestructiveMigration(dropAllTables = true)
                                    .build().also { runBlocking { it.seed() } }
                            },
                            drafts = InMemoryDraftStore(),
                            updateState = UpdateState(scope, StaticUpdateRuntime(), NoopUpdateInstallerHook()),
                            createUiSettings = { ui },
                        )
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithContentDescription("New message").fetchSemanticsNodes().isNotEmpty()
                }
                block(ship)
            }
        } finally {
            scope.cancel()
            // The app closes its databases itself, two seconds after it
            // goes, so work still in flight can finish. Closing them here
            // too pulled SQLite out from under a running query, and deleting
            // the files at once did the same: a read in those two seconds
            // could not open them (SQLite 14), failing the NEXT test with
            // "uncaught exceptions before the test started" (CI, 2026-10-07).
            Thread { Thread.sleep(5_000); tmp.deleteRecursively() }.apply { isDaemon = true }.start()
        }
    }

    private fun ComposeUiTest.present(description: String) =
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.showing(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.home() = waitUntil(timeoutMillis = 5_000) { present("New message") && present("Chats") }

    @Test
    fun `every full-screen section opens from the rail and its Back returns to the list`() = app {
        for ((item, marker) in listOf(
            "My profile" to "Edit profile",
            "Administration" to "Administration",
            "Invites" to "No pending invites",
            "Settings" to "Appearance",
        )) {
            onNodeWithContentDescription(item).performClick()
            waitUntil(timeoutMillis = 5_000) { showing(marker) }
            assertTrue(!present("Chats"), "$item takes the whole window")
            onNodeWithContentDescription("Back").performClick()
            home()
        }
    }

    // "once you tap add ship there is no way to cancel and return to a
    // working ship".
    @Test
    fun `Add ship can be cancelled, back to the ship that was open`() = app {
        onNodeWithContentDescription("Switch ship").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Add ship") }
        onNodeWithText("Add ship").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Connect") }
        assertTrue(!present("New message"), "the login form, not the ship")
        onNodeWithText("Cancel").performClick()
        home()
        assertTrue(!showing("Connect"))
    }

    // Orrery is off unless the owner turns it on, and off means the ship's
    // Orrery hears nothing from this device: not a probe, not a read.
    @Test
    fun `with Orrery off the app asks the ship nothing of it`() = app { ship ->
        mainClock.advanceTimeBy(60_000)
        Thread.sleep(3_000)
        waitForIdle()
        assertTrue(ship.requests.none { "/apps/orrery" in it }, ship.requests.filter { "/apps/orrery" in it }.toString())
    }

    @Test
    fun `turned on, the app goes to the ship's Orrery`() = app(
        ai = FakeAiSettings().apply {
            applyRemote(io.nisfeb.talon.ai.AiSettings.Config(
                provider = io.nisfeb.talon.ai.AiSettings.Provider.Anthropic, apiKey = "", model = null,
                savedProfile = io.nisfeb.talon.ai.AiProfile(orrery = true),
            ))
        },
    ) { ship ->
        waitUntil(timeoutMillis = 10_000) { ship.requests.any { "/apps/orrery/api/state" in it } }
    }

    @Test
    fun `the rail's tabs switch the list pane and Chats brings the list back`() = app {
        onNodeWithContentDescription("Bookmarks").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("No bookmarks yet") }
        assertTrue(!present("New message"))
        onNodeWithContentDescription("Activity").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("No activity yet") }
        assertTrue(!showing("No bookmarks yet"), "one tab at a time")
        onNodeWithContentDescription("Chats").performClick()
        home()
    }

    @Test
    fun `Escape in a chat's composer closes the chat`() = app(seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["hello there"]}]""", "/chat"))
    }) {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("hello there") }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        onNode(hasSetTextAction()).performClick() // typing in the chat
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { showing("Select a chat to begin") }
    }

    // The reported "goes nowhere": a group reference used to run the
    // home-list reveal, which silently did nothing for a group you had
    // not joined. It now opens the group's own page — here its Join CTA,
    // since this ship is not in ~bus/crew (nothing seeded for it).
    @Test
    fun `tapping a group reference opens the group page`() = app(seed = {
        messages().upsert(
            MessageEntity(
                "~bus", "~bus/170141184506", "~bus", 1_000,
                """[{"block":{"cite":{"group":"~bus/crew"}}}]""", "/chat",
            ),
        )
    }) {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("~bus").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("~bus")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        // Click the reference chip itself. "Referenced post" is the
        // citation's own body text, rendered only in the chat pane, so it
        // targets the chip and not the list's preview of the same message.
        waitUntil(timeoutMillis = 5_000) { showing("Group ~bus/crew") && showing("Referenced post") }
        onNodeWithText("Referenced post").performClick()
        // The group's own page, in its non-member mode — not a no-op.
        waitUntil(timeoutMillis = 5_000) { showing("You haven't joined this group") }
    }

    // One Escape closed the image and the chat with it: the image took
    // the click's focus, went with the post when the viewer replaced it,
    // and the shell took focus back from the viewer.
    @Test
    fun `Escape closes an open image first, and the chat on the second`() = app(seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"block":{"image":{"src":"https://x.test/cat.png","alt":"a cat","height":300,"width":400}}}]""", "/chat"))
    }) {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("~bus").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("~bus")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("a cat").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("a cat")[0].performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onAllNodes(androidx.compose.ui.test.isRoot())[0].performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isEmpty() }
        assertTrue(!showing("Select a chat to begin"), "the chat is still open")
        onAllNodes(androidx.compose.ui.test.isRoot())[0].performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { showing("Select a chat to begin") }
    }

    @Test
    fun `closing a section by mouse leaves the keyboard working, and a field keeps its focus`() = app(seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["hello there"]}]""", "/chat"))
    }) {
        onNodeWithContentDescription("My profile").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Edit profile") }
        onNodeWithText("Edit profile").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasSetTextAction())[0].performClick() // typing in the section
        onNodeWithContentDescription("Back").performClick() // closed by mouse: what had focus is gone
        home()
        onAllNodes(isRoot()).onFirst().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Comma) } }
        waitUntil(timeoutMillis = 5_000) { showing("Appearance") }
        onNodeWithContentDescription("Back").performClick()
        home()
        // Taking focus back is only for when nothing has it: a field
        // clicked into keeps it.
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("hello there") }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        onNode(hasSetTextAction()).performClick()
        Thread.sleep(300)
        onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `Home, Mail and Calendar take the whole area, and each says what the ship lacks`() = app {
        onNodeWithContentDescription("Home").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Set a location") }
        assertTrue(showing("Install the calendar") && !present("New message"), "the home page, not the list")
        onNodeWithContentDescription("Mail").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Mail runs in Grubbery, which this ship does not have yet.") }
        onNodeWithContentDescription("Calendar").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("The Today widget on the home page can install it.") }
        onNodeWithContentDescription("Chats").performClick()
        home()
    }

    // An iPhone user, 2026-10-17: the Mail tab on a phone was the bare
    // mail list, under the clock, with no menu button and no way back,
    // and the tab is saved, so Talon opened on it again.
    @Test
    fun `with no rail, Mail has its header and a way back to the chats`() {
        val ui = InMemoryUiSettings()
        app(ui = ui, width = 600.dp) {
            assertTrue(!present("Mail"), "a phone-width window has no rail")
            // Where the drawer, the home page and a saved tab all land.
            ui.setActiveRailTab(RailTab.Mail)
            waitUntil(timeoutMillis = 5_000) { showing("Mail runs in Grubbery, which this ship does not have yet.") }
            assertTrue(onAllNodesWithText("Mail").fetchSemanticsNodes().isNotEmpty() && present("Back"), "the mail screen's own header")
            onNodeWithContentDescription("Back").performClick()
            // The chat list; no rail, so no Chats button to look for.
            waitUntil(timeoutMillis = 5_000) { present("New message") }
            assertTrue(!showing("Mail runs in Grubbery"))
        }
    }

    @Test
    fun `a thread opened from a message sits beside the chat`() = app(seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["shall we meet"]}]""", "/chat"))
    }) {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("shall we meet") }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        // Desktop's way into a message's menu: the Message actions button beside it.
        val opened = { onAllNodesWithText("Reply in thread").fetchSemanticsNodes().isNotEmpty() }
        for (attempt in 1..3) {
            onAllNodesWithContentDescription("Message actions").onFirst().performClick()
            if (runCatching { waitUntil(timeoutMillis = 1_500) { opened() } }.isSuccess) break
        }
        onNodeWithText("Reply in thread").performClick()
        // The parent, in the chat and again at the head of its thread.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("shall we meet").fetchSemanticsNodes().size >= 2 }
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2, "the chat's composer and the thread's, side by side")
    }

    // "Dropdown instead of panes": in a window too narrow for list, chat
    // and thread side by side, the thread opens over the chat, the list
    // stays, and closing the thread brings the chat back.
    @Test
    fun `in a narrower window a thread opens over the chat, beside the list`() = app(width = 900.dp, seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["shall we meet"]}]""", "/chat"))
    }) {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("shall we meet") }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        // 900dp used to fold the list away as soon as a chat opened.
        assertTrue(showing("DMs"), "the list is still beside the chat")
        val opened = { onAllNodesWithText("Reply in thread").fetchSemanticsNodes().isNotEmpty() }
        for (attempt in 1..3) {
            onAllNodesWithContentDescription("Message actions").onFirst().performClick()
            if (runCatching { waitUntil(timeoutMillis = 1_500) { opened() } }.isSuccess) break
        }
        onNodeWithText("Reply in thread").performClick()
        val threadOpen = { onAllNodesWithText("Thread").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { threadOpen() }
        assertTrue(showing("DMs"), "the list stays")
        kotlin.test.assertEquals(1, onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size, "the thread's composer alone, over the chat")
        onNodeWithContentDescription("Close").performClick()
        waitUntil(timeoutMillis = 5_000) { !threadOpen() }
        kotlin.test.assertEquals(1, onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size, "the chat's composer is back")
        assertTrue(present("Message actions"), "the chat is back")
    }

    private fun ComposeUiTest.press(key: Key, ctrl: Boolean = false) =
        onAllNodes(isRoot()).onFirst().performKeyInput { if (ctrl) withKeyDown(Key.CtrlLeft) { pressKey(key) } else pressKey(key) }

    // A shortcut's handler was in the list view, which is not drawn while a
    // section is: Ctrl+, in the profile did nothing, then opened Settings
    // as soon as Escape had left the profile.
    @Test
    fun `a shortcut works from inside a section, and Escape then leaves for good`() = app {
        onNodeWithContentDescription("My profile").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Edit profile") }
        press(Key.Comma, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { showing("Appearance") }
        assertTrue(!showing("Edit profile"), "the profile is put down")
        press(Key.Escape)
        home()
        Thread.sleep(500)
        waitForIdle()
        assertTrue(!showing("Appearance"), "Settings does not come back")
    }

    // The profile asked before dropping edits only on Android's back:
    // on desktop Escape, and a shortcut, dropped them without a word.
    @Test
    fun `edits in the profile ask before Escape or a shortcut leave them`() = app {
        onNodeWithContentDescription("My profile").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasSetTextAction())[0].performTextInput("Zod")
        press(Key.Escape)
        waitUntil(timeoutMillis = 5_000) { showing("Discard your changes?") }
        onNodeWithText("Cancel").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Discard your changes?") }
        assertTrue(showing("Edit profile") && showing("Zod"), "still editing, the edit kept")
        press(Key.Comma, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { showing("Discard your changes?") }
        assertTrue(!showing("Appearance"))
        onNodeWithText("Discard").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Appearance") }
        assertTrue(!showing("Edit profile"), "where the shortcut was going")
    }

    // A tapped calendar reminder (iOS) set the calendar's flag under the
    // section open at the time: nothing showed, and back seemed dead.
    @Test
    fun `a tapped calendar reminder opens the calendar over an open section`() = app {
        onNodeWithContentDescription("My profile").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Edit profile") }
        io.nisfeb.talon.notify.OpenNoticeRequests.request(io.nisfeb.talon.calendar.CALENDAR_PUSH_PREFIX + "nowhere")
        waitUntil(timeoutMillis = 5_000) { showing("The Today widget on the home page can install it.") }
        assertTrue(!showing("Edit profile"))
        press(Key.Escape)
        home()
    }

    // ---- Calendar meetings (the calendar app's version 34) ----

    private val hour = 3_600_000L
    /** Noon today, where the device is; the calendar's zone is UTC, so this is also each row's wall clock. */
    private val noon = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val planningMeta =
        """{"name":"Planning","conference":"https://meet.google.com/abc-defg-hij",""" +
            """"guests":[{"email":"ana@example.com","name":"Ana","status":"accepted"},{"email":"me@example.com","status":"needs-action","self":true}],""" +
            """"ships":[{"ship":"~bus","status":"tentative"}]}"""
    /** One invitation from ~nec, unanswered; its host's last one went on Work. */
    private val gardenParty =
        """[{"key":"~nec/inv1","host":"~nec","uid":"inv1","name":"Garden party","start_ms":${noon + 5 * hour},"status":"","cal":"","route":"work","ships":[{"ship":"~zod","status":"needs-action"}],"at_ms":1}]"""

    /**
     * A ship whose calendar has a daily standup, a Google meeting this ship
     * is asked to, and a copy of ~nec's event. [invites] null is a calendar
     * older than 34, whose invites.json is a 404.
     */
    private fun withCalendar(invites: String? = "[]", more: FakeShip.FakeCalendar.() -> Unit = {}): FakeShip.() -> Unit = {
        calendar = FakeShip.FakeCalendar().apply {
            calendars = """[{"id":"default","name":"Personal","kind":"local"},{"id":"work","name":"Work","kind":"google"}]"""
            rows = """[
                {"id":"s1","cal":"default","idx":7,"cat":"timed","kind":"rrule","meta":{"name":"Standup"},"l":$noon,"r":${noon + 15 * 60_000L}},
                {"id":"g1","cal":"work","meta":$planningMeta,"l":${noon + hour},"r":${noon + 2 * hour}},
                {"id":"c1","cal":"default","meta":{"name":"Seed swap","host":"~nec","ships":[{"ship":"~nec","status":"accepted"},{"ship":"~zod","status":"needs-action","self":true}]},"l":${noon + 3 * hour},"r":${noon + 4 * hour}}
            ]"""
            events["s1"] = """{"id":"s1","cal":"default","cat":"timed","kind":"rrule","start_ms":${noon - 7 * 24 * hour},"zone":"UTC","dur_min":15,"fin":"dur","args":{"rrule":"FREQ=DAILY"},"meta":{"name":"Standup"}}"""
            events["g1"] = """{"id":"g1","cal":"work","cat":"timed","kind":"once","start_ms":${noon + hour},"zone":"UTC","dur_min":60,"fin":"dur","meta":$planningMeta}"""
            this.invites = invites
            more()
        }
    }

    private fun ComposeUiTest.openCalendar() {
        onNodeWithContentDescription("Calendar").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("Standup") }
    }

    private fun ComposeUiTest.field(label: String) = onNode(hasSetTextAction() and androidx.compose.ui.test.hasText(label))

    /** Standup's occurrence today, opened, renamed, put on [calendar] if named, and saved as this one only. */
    private fun ComposeUiTest.renameThisStandupOnly(calendar: String? = null) {
        onNodeWithText("Standup").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Close") }
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("This change applies to") }
        onNodeWithText("This one only").performScrollTo().performClick()
        field("Name").performTextReplacement("Standup, moved")
        if (calendar != null) {
            onNode(androidx.compose.ui.test.hasText(calendar) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())).performScrollTo().performClick()
        }
        onAllNodesWithText("Save")[0].performClick()
    }

    @Test
    fun `this one only is one edit-at on a calendar that has it, and invitations are read after the month`() = app(
        setup = withCalendar {
            onPoke = { if ("edit-at" in it) rows = rows.replace("\"name\":\"Standup\"", "\"name\":\"Standup, moved\"") }
        },
    ) { ship ->
        val cal = ship.calendar!!
        Thread.sleep(1_000)
        assertTrue(ship.requests.none { it.endsWith("/invites.json") }, "nothing of invitations before the calendar is opened")
        val opened = ship.requests.size
        openCalendar()
        waitUntil(timeoutMillis = 5_000) { ship.requests.any { it.endsWith("/invites.json") } }
        val asked = ship.requests.toList()
        val month = (opened until asked.size).firstOrNull { asked[it].endsWith("/window.json") }
        assertTrue(month != null && asked.indexOfFirst { it.endsWith("/invites.json") } > month, "the month first, then invitations: $asked")
        // Another calendar picked too: one occurrence stays with its series,
        // as on the calendar's page, so home is the series' own and no cal goes.
        renameThisStandupOnly(calendar = "Work")
        waitUntil(timeoutMillis = 5_000) { cal.pokes.isNotEmpty() }
        waitUntil(timeoutMillis = 10_000) { showing("Standup, moved") && !showing("syncing") }
        assertEquals(
            listOf("""{"action":"edit-at","id":"s1","home":"default","at_ms":$noon,"cat":"timed","meta":{"name":"Standup, moved"},"kind":"once","start_ms":$noon,"zone":"UTC","fin":"dur","dur_min":15}"""),
            cal.pokes.toList(),
            "one step, nothing added and nothing skipped",
        )
    }

    @Test
    fun `on a calendar older than invitations, this one only adds the one-off and then skips the occurrence`() = app(setup = withCalendar(invites = null)) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        renameThisStandupOnly()
        waitUntil(timeoutMillis = 10_000) { cal.pokes.size >= 2 }
        assertTrue("\"add-event\"" in cal.pokes[0] && "Standup, moved" in cal.pokes[0], cal.pokes[0])
        assertEquals("""{"action":"skip-event","id":"s1","idx":7}""", cal.pokes[1])
        assertTrue(cal.pokes.none { "edit-at" in it })
        assertTrue(!showing("invitation"), "no invitations on a calendar without them")
        // Its 404 is remembered: the save did not ask again.
        assertEquals(1, ship.requests.count { it.endsWith("/invites.json") })
    }

    // Not knowing is not knowing yes: a hiccup on invites.json keeps the
    // two steps every calendar takes.
    @Test
    fun `when the calendar's invitations cannot be read, this one only stays add then skip`() = app(setup = withCalendar { invitesFail = true }) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        renameThisStandupOnly()
        waitUntil(timeoutMillis = 10_000) { cal.pokes.size >= 2 }
        assertTrue("\"add-event\"" in cal.pokes[0] && "skip-event" in cal.pokes[1], cal.pokes.toString())
        assertTrue(cal.pokes.none { "edit-at" in it })
    }

    // The calendar acks every poke and refuses after, keeping why in
    // outcomes.json, as its own page reads it. A refused edit-at must not
    // fall back to add and skip: the ship has the feature, and said no to
    // this one.
    @Test
    fun `an edit-at the calendar takes and then refuses reopens the editor with why, and nothing else is sent`() = app(
        setup = withCalendar { refuseLater = { if ("edit-at" in it) "no repeating event has an occurrence there, or the new one could not be read" else null } },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        renameThisStandupOnly()
        waitUntil(timeoutMillis = 10_000) { showing("Not saved: no repeating event has an occurrence there, or the new one could not be read") }
        assertTrue(onAllNodes(hasSetTextAction() and androidx.compose.ui.test.hasText("Standup, moved")).fetchSemanticsNodes().isNotEmpty(), "the edit is still there")
        assertEquals(1, cal.pokes.size)
        assertTrue(cal.outcomesRead.get() > 0)
    }

    // Taken is not kept: skipped on the add's ack alone, the occurrence
    // was lost whenever the calendar then refused the add.
    @Test
    fun `on an older calendar, an add it refuses leaves the occurrence and reopens the editor`() = app(
        setup = withCalendar(invites = null) { refuseLater = { if ("add-event" in it) "the event could not be read: its name, kind, start or rule" else null } },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        renameThisStandupOnly()
        waitUntil(timeoutMillis = 10_000) { showing("Not saved: the event could not be read: its name, kind, start or rule") }
        assertEquals(1, cal.pokes.size, "no skip: ${cal.pokes}")
    }

    // The add kept, the skip refused: both are on the calendar, said so,
    // and the editor stays shut, or saving again adds a third.
    @Test
    fun `on an older calendar, a skip it refuses says both are kept`() = app(
        setup = withCalendar(invites = null) { refuseLater = { if ("skip-event" in it) "no such occurrence" else null } },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        renameThisStandupOnly()
        waitUntil(timeoutMillis = 10_000) { showing("kept the old one too") }
        assertEquals(2, cal.pokes.size)
        assertTrue(!showing("This change applies to"), "the editor stays shut")
    }

    // Leaving while a busy ship has the save: the refusal is still found,
    // and said when the calendar is opened again.
    @Test
    fun `a save refused after the calendar was left is said when it opens again`() = app(
        setup = withCalendar {
            hold = kotlinx.coroutines.CompletableDeferred()
            refuseLater = { if ("edit-at" in it) "no repeating event has an occurrence there, or the new one could not be read" else null }
        },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        renameThisStandupOnly()
        waitUntil(timeoutMillis = 5_000) { ship.requests.any { it.endsWith("/calendar.calendar") } }
        onNodeWithContentDescription("Chats").performClick()
        home()
        cal.hold!!.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { cal.outcomesRead.get() > 0 }
        waitForIdle()
        onNodeWithContentDescription("Calendar").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("\"Standup, moved\" was not saved: no repeating event has an occurrence there") }
    }

    // A card for an event the calendar then refused pointed at nothing.
    @Test
    fun `a new event's card goes to its chat only once the calendar has kept it`() = app(
        seed = { messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["hello there"]}]""", "/chat")) },
        setup = withCalendar { refuseLater = { if ("Refused tea" in it) "the event could not be read: its name, kind, start or rule" else null } },
    ) { ship ->
        openCalendar()
        onAllNodesWithContentDescription("New event")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Also post to a chat") }
        field("Name").performTextInput("Refused tea")
        onNodeWithText("Also post to a chat…").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Post the event to which chat?") }
        onAllNodes(androidx.compose.ui.test.hasText("~bus") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()))[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Posts to") }
        onAllNodesWithText("Save")[0].performClick()
        waitUntil(timeoutMillis = 10_000) { showing("Not saved: the event could not be read") }
        assertTrue(ship.pokesTo("chat").isEmpty(), "nothing posted for a refused event")
        assertTrue(showing("Posts to"), "the editor back with where the card was to go")
        field("Refused tea").performTextReplacement("Tea")
        onAllNodesWithText("Save")[0].performClick()
        waitUntil(timeoutMillis = 10_000) { ship.pokesTo("chat").any { "Tea" in it.json.toString() } }
    }

    // A change the calendar keeps says nothing more: no refusal of it, and
    // one an hour old, said or not, is not this one's.
    @Test
    fun `a change the calendar keeps is not said refused, whatever its record holds`() = app(
        setup = withCalendar {
            refusals += """{"at_ms":${System.currentTimeMillis() - hour},"action":"edit-at","id":"s1","from":"","why":"an old one"}"""
            onPoke = { if ("edit-at" in it) rows = rows.replace("\"name\":\"Standup\"", "\"name\":\"Standup, moved\"") }
        },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        renameThisStandupOnly()
        waitUntil(timeoutMillis = 10_000) { cal.outcomesRead.get() > 0 }
        waitForIdle()
        assertTrue(!showing("Not saved") && !showing("an old one"))
    }

    @Test
    fun `a Google invitation shows its link and guests, and the owner's answer is an rsvp the details then show`() = app(
        setup = withCalendar {
            onPoke = { if ("\"rsvp\"" in it) rows = rows.replace("\"email\":\"me@example.com\",\"status\":\"needs-action\"", "\"email\":\"me@example.com\",\"status\":\"tentative\"") }
        },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        onNodeWithText("Planning").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Guests: Ana (going), ~bus (maybe)") }
        assertTrue(showing("Join") && !showing("me@example.com"), "the link, and the owner is not among the guests")
        val before = ship.requests.size
        onNodeWithText("Maybe").performClick()
        waitUntil(timeoutMillis = 5_000) { cal.pokes.isNotEmpty() }
        assertEquals(listOf("""{"action":"rsvp","id":"g1","home":"work","status":"tentative"}"""), cal.pokes.toList())
        assertTrue(cal.answers.isEmpty(), "an rsvp, not an invitation's answer")
        // Read back into the month, and the details say it.
        waitUntil(timeoutMillis = 10_000) { ship.requests.drop(before).count { it.endsWith("/window.json") } >= 2 }
        waitForIdle()
        onNodeWithText("Planning").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(androidx.compose.ui.test.hasText("Maybe") and androidx.compose.ui.test.isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `answering a copy of another ship's event answers its invitation, which tells the host`() = app(setup = withCalendar()) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        onNodeWithText("Seed swap").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("From ~nec. Guests: ~nec (going)") }
        onNodeWithText("Going").performClick()
        waitUntil(timeoutMillis = 5_000) { cal.answers.isNotEmpty() }
        assertEquals(listOf("""{"key":"~nec/c1","status":"accepted","cal":"default"}"""), cal.answers.toList())
        assertTrue(cal.pokes.isEmpty(), "no rsvp for a copy")
    }

    @Test
    fun `an invitation is accepted onto the calendar picked, and then it is on the calendar and no longer counted`() = app(
        setup = withCalendar(invites = gardenParty) {
            answer = {
                invites = invites!!.replace("\"status\":\"\",\"cal\":\"\"", "\"status\":\"accepted\",\"cal\":\"default\"")
                rows = rows.trimEnd().removeSuffix("]") + """,{"id":"inv1","cal":"default","meta":{"name":"Garden party","host":"~nec"},"l":${noon + 5 * hour},"r":${noon + 6 * hour}}]"""
                """{"ok":true,"cal":"default","told":true}"""
            }
        },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        waitUntil(timeoutMillis = 5_000) { showing("1 invitation") }
        onNodeWithText("1 invitation").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Garden party") && showing("from ~nec") && showing("no answer yet") }
        // Where that host's last one went, until another is picked.
        onNodeWithContentDescription("Calendar for Garden party").assertTextContains("Work")
        onNodeWithContentDescription("Calendar for Garden party").performClick()
        onNode(androidx.compose.ui.test.hasText("Personal") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isPopup())).performClick()
        onNodeWithContentDescription("Calendar for Garden party").assertTextContains("Personal")
        onNodeWithText("Going").performClick()
        waitUntil(timeoutMillis = 5_000) { cal.answers.isNotEmpty() }
        assertEquals(listOf("""{"key":"~nec/inv1","status":"accepted","cal":"default"}"""), cal.answers.toList())
        waitUntil(timeoutMillis = 10_000) { showing("· going") }
        onNodeWithText("Done").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("Garden party") && !showing("1 invitation") }
    }

    @Test
    fun `an answer the ship refuses, one only half done, and one that never reaches it each say so`() = app(
        setup = withCalendar(invites = gardenParty) {
            answer = { null }
            refuseLater = { if ("\"rsvp\"" in it) "the event names no guest who is this calendar's owner" else null }
        },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        onNodeWithText("Planning").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Guests:") }
        onNodeWithText("No").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Not answered: the event names no guest who is this calendar's owner") }
        assertEquals(1, cal.pokes.size)

        onNodeWithText("1 invitation").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Garden party") }
        onNodeWithText("Maybe").performClick()
        waitUntil(timeoutMillis = 5_000) { cal.answers.size == 1 && showing("The ship did not take the answer.") && !showing("Answering") }

        cal.answer = { """{"ok":false,"cal":"work","told":false}""" }
        onNodeWithText("Going").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("The event could not be put on that calendar, and ~nec could not be told yet. The calendar tries again in about an hour.") }

        cal.answer = { """{"ok":true,"cal":"work","told":false}""" }
        onNodeWithText("Going").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Answered, but ~nec could not be told yet: their ship did not answer. The calendar tries again in about an hour.") }

        cal.lose = true
        onNodeWithText("Going").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("No answer from the ship. Nothing was changed.") }
    }

    // The answer runs on the calendar's own scope: leaving the calendar
    // while a busy ship has not yet taken it does not lose it.
    @Test
    fun `an answer given just before leaving the calendar still reaches the ship`() = app(
        setup = withCalendar(invites = gardenParty) { hold = kotlinx.coroutines.CompletableDeferred() },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        onNodeWithText("1 invitation").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Garden party") }
        onNodeWithText("Maybe").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.requests.any { it.endsWith("/invites/answer") } }
        onNodeWithText("Done").performClick()
        onNodeWithContentDescription("Chats").performClick()
        home()
        val before = ship.requests.size
        cal.hold!!.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { cal.answers.size == 1 }
        assertEquals("""{"key":"~nec/inv1","status":"tentative","cal":"work"}""", cal.answers.single())
        // And read back behind it, with nobody on the screen to ask.
        waitUntil(timeoutMillis = 10_000) { ship.requests.drop(before).any { it.endsWith("/invites.json") } }
    }

    @Test
    fun `the editor writes guests and a video call as the calendar's page does`() = app(setup = withCalendar()) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        onNodeWithText("Planning").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Close") }
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("Video call") }
        assertTrue(showing("Keep the current link") && showing("Google Meet"), "a Google calendar, and an event with a link")
        // Google mails its guests on every change (calendar 34 writes with sendUpdates=all).
        val mails = "Google emails these guests about every change."
        assertTrue(showing(mails))
        field("ana@example.com, ~bus").performTextReplacement("~bus")
        assertTrue(!showing(mails), "ships only: their own calendars tell them")
        field("~bus").performTextReplacement("ana@example.com, ~bus, ~nec")
        assertTrue(showing(mails))
        onAllNodesWithText("Save")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { cal.pokes.isNotEmpty() }
        val edited = kotlinx.serialization.json.Json.parseToJsonElement(cal.pokes.single()).jsonObject["meta"]!!.jsonObject
        // A guest kept keeps their answer, the owner stays, and the new ship has none yet.
        assertEquals("""[{"ship":"~bus","status":"tentative"},{"ship":"~nec","status":"needs-action"}]""", edited["ships"].toString())
        assertEquals(
            """[{"email":"me@example.com","status":"needs-action","self":true},{"email":"ana@example.com","name":"Ana","status":"accepted"}]""",
            edited["guests"].toString(),
        )
        assertEquals("\"https://meet.google.com/abc-defg-hij\"", edited["conference"].toString())
        assertTrue("call" !in edited, "Keep leaves meta alone")

        // A new event on the local calendar: no Meet offered, and an email refused.
        onAllNodesWithContentDescription("New event")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Video call") }
        assertTrue(!showing("Google Meet") && !showing("Keep the current link"))
        field("Name").performTextInput("Tea")
        field("Guests, comma separated").performTextInput("bo@example.com, ~nec")
        assertTrue(!showing(mails), "not a Google calendar")
        onAllNodesWithText("Save")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Email guests need a calendar linked to Google") }
        assertEquals(1, cal.pokes.size)

        // On the Google calendar it goes, with a Meet asked for.
        onNode(androidx.compose.ui.test.hasText("Work") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())).performScrollTo().performClick()
        onNodeWithText("Google Meet").performScrollTo().performClick()
        onAllNodesWithText("Save")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { cal.pokes.size == 2 }
        val added = kotlinx.serialization.json.Json.parseToJsonElement(cal.pokes[1]).jsonObject
        assertEquals("add-event", added["action"]!!.jsonPrimitive.content)
        assertEquals(
            """{"name":"Tea","ships":[{"ship":"~nec","status":"needs-action"}],"guests":[{"email":"bo@example.com","status":"needs-action"}],"call":"meet"}""",
            added["meta"].toString(),
        )
    }

    // A copy's guests and call are its host's: the calendar ignores them
    // on the copy, so the editor does not offer them there.
    @Test
    fun `a copy of another ship's event is edited without guests or a video call`() = app(
        setup = withCalendar {
            events["c1"] = """{"id":"c1","cal":"default","cat":"timed","kind":"once","start_ms":${noon + 3 * hour},"zone":"UTC","dur_min":60,"fin":"dur","meta":{"name":"Seed swap","host":"~nec","ships":[{"ship":"~nec","status":"accepted"},{"ship":"~zod","status":"needs-action","self":true}]}}"""
        },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        onNodeWithText("Seed swap").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Close") }
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction() and androidx.compose.ui.test.hasText("Seed swap")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!showing("Guests, comma separated") && !showing("Video call"), "the host's guests are not offered")
        field("Seed swap").performTextReplacement("Seed swap, mine")
        onAllNodesWithText("Save")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { cal.pokes.isNotEmpty() }
        // Its meta as it came, host and guests and all: replaced whole on an edit.
        val meta = kotlinx.serialization.json.Json.parseToJsonElement(cal.pokes.single()).jsonObject["meta"]!!.jsonObject
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"name":"Seed swap, mine","host":"~nec","ships":[{"ship":"~nec","status":"accepted"},{"ship":"~zod","status":"needs-action","self":true}]}""",
            ),
            meta,
        )
    }

    // "When adding a new contact and pressing Message, it should exit you
    // out of that view to the newly created DM conversation" (sneagan,
    // 2026-10-08). The sheet set the chat and left Contacts on top of it.
    @Test
    fun `Message on a contact just added leaves Contacts for the conversation`() = app { ship ->
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Contacts").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("~patp, or a word name") }
        onNodeWithText("~patp, or a word name").performTextInput("~bus")
        waitForIdle()
        onNodeWithText("Add").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("contacts").isNotEmpty() && onAllNodesWithText("~bus").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("~bus")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Message") }
        onNodeWithText("Message").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("~patp, or a word name") }
        assertTrue(!showing("Search contacts"), "Contacts is put down")
        // The sheet is gone, so the one "Message" left is the conversation's composer.
        waitUntil(timeoutMillis = 5_000) { showing("Message") }
    }

    // Areas of the app get shortcuts the owner sets: pressed anywhere, the
    // area opens as its rail item would open it.
    @Test
    fun `a shortcut the owner set opens its area`() {
        val ui = InMemoryUiSettings().apply { setKeybinds(mapOf("open:Settings" to null, "open:Contacts" to null, "open:Profile" to KeyCombo("P", alt = true))) }
        app(ui = ui) {
            onAllNodes(androidx.compose.ui.test.isRoot())[0].performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.P) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Edit profile").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}

