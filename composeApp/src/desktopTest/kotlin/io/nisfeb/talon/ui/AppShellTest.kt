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
        /** Media for 1:1 calls; null keeps calls dark, as on a platform without them. */
        calls: io.nisfeb.talon.call.CallEngineProvider? = null,
        /** The saved ships; the fake ship's own, ~zod alone, by default. */
        sessions: ((FakeShip) -> io.nisfeb.talon.urbit.SessionStore)? = null,
        relay: io.nisfeb.talon.notify.RelaySettings = io.nisfeb.talon.notify.InMemoryRelaySettings(),
        drafts: DraftStore = InMemoryDraftStore(),
        notifier: io.nisfeb.talon.notify.Notifier = io.nisfeb.talon.notify.NoopNotifier,
        /** How soon the chat list's sync status says something is wrong. */
        sync: io.nisfeb.talon.ui.SyncThresholds = io.nisfeb.talon.ui.SyncThresholds(),
        audioDevices: io.nisfeb.talon.call.AudioDevices = io.nisfeb.talon.call.AudioDevices.Noop,
        videoDevices: io.nisfeb.talon.call.VideoDevices = io.nisfeb.talon.call.VideoDevices.Noop,
        /** Grubbery's stock apps added on the way in; off, so other tests' ships hear nothing of it. */
        defaultApps: Boolean = false,
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
        val store = sessions?.invoke(ship) ?: ship.session
        try {
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(width = width, height = 800.dp)) {
                        androidx.compose.runtime.CompositionLocalProvider(io.nisfeb.talon.ui.LocalSyncThresholds provides sync) {
                        App(
                            http = ship.http,
                            sessionStore = store,
                            installDefaultApps = defaultApps,
                            aiSettings = ai,
                            createDb = { key ->
                                Room.databaseBuilder<AppDatabase>(File(tmp, "$key.db").absolutePath)
                                    .setDriver(BundledSQLiteDriver())
                                    .fallbackToDestructiveMigration(dropAllTables = true)
                                    .build().also { runBlocking { it.seed() } }
                            },
                            drafts = drafts,
                            relaySettings = relay,
                            updateState = UpdateState(scope, StaticUpdateRuntime(), NoopUpdateInstallerHook()),
                            createUiSettings = { ui },
                            callEngineProvider = calls,
                            notifier = notifier,
                            audioDevices = audioDevices,
                            videoDevices = videoDevices,
                        )
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithContentDescription("New message").fetchSemanticsNodes().isNotEmpty()
                }
                dumpingScreenOnFailure(ship) { block(ship) }
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

    private fun ComposeUiTest.home() = waitUntil(timeoutMillis = 20_000) { present("New message") && present("Chats") }

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
    // B1-4: a first read that failed said "No direct messages yet". No
    // answer is not empty: it says so, and Try again reads them.
    @Test
    fun `a first load that fails says so and Try again reads the chats`() = app(setup = { failScry = { "init-posts" in it } }) { ship ->
        waitUntil(timeoutMillis = 20_000) { showing("Couldn't load your chats") }
        assertTrue(!showing("No direct messages yet"), "a failed read called empty")
        ship.answerScry = { if ("init-posts" in it) """{"chat":{},"channels":{}}""" else null }
        ship.failScry = { false }
        val asked = ship.scried.count { "init-posts" in it }
        onNodeWithText("Try again").performClick()
        waitUntil(timeoutMillis = 20_000) { ship.scried.count { "init-posts" in it } > asked }
        // Read, and empty: now it is said so.
        waitUntil(timeoutMillis = 20_000) { !showing("Couldn't load your chats") }
        assertTrue(showing("No direct messages yet") || showing("No groups yet"), "the empty line")
    }

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

    // Orrery 110's late nags, the whole way through the app: a late high
    // task leads Orrery's Actions in red, Snooze 1 h reaches the ship as
    // orrery's serve-mark reads it, and Late empties. The app holds no key
    // here, so every orrery request is the owner's, through this ship.
    private fun FakeShip.orrery(version: Int) {
        val due = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis() - 2 * 3_600_000).toString()
        answerApi = { method, path, _ ->
            when {
                path == "/apps/orrery/api/state" -> "{}"
                path == "/apps/orrery/api/version" -> """{"version":$version}"""
                path == "/apps/orrery/api/actions" ->
                    """[{"id":"a1","kind":"task","title":"Check in for DL 2627","payload":{"priority":"high"},"due":"$due","status":"approved"}]"""
                method == "POST" && path == "/apps/orrery/api/actions/a1" -> """{"ok":true}"""
                method == "POST" && path == "/apps/orrery/api/actions/a1/snooze" ->
                    """{"ok":true,"id":"a1","snooze_until":"${kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis() + 3_600_000)}"}"""
                else -> null
            }
        }
    }

    private val orreryOn get() = FakeAiSettings().apply {
        applyRemote(io.nisfeb.talon.ai.AiSettings.Config(
            provider = io.nisfeb.talon.ai.AiSettings.Provider.Anthropic, apiKey = "", model = null,
            savedProfile = io.nisfeb.talon.ai.AiProfile(orrery = true),
        ))
    }

    private fun ComposeUiTest.openOrrery() {
        waitUntil(timeoutMillis = 10_000) { present("Orrery") }
        onNodeWithContentDescription("Orrery").performClick()
    }

    @Test
    fun `a late high action leads Orrery and Snooze 1 h reaches the ship and empties Late`() = app(ai = orreryOn, setup = { orrery(110) }) { ship ->
        openOrrery()
        waitUntil(timeoutMillis = 10_000) { showing("Late · 1") }
        assertTrue(showing("Check in for DL 2627") && showing("2 h") && showing("late"))
        onNodeWithText("Snooze 1 h").performClick()
        waitUntil(timeoutMillis = 10_000) { !showing("Late ·") }
        waitUntil(timeoutMillis = 10_000) { ship.api.any { "/snooze" in it } }
        assertEquals(listOf("POST /apps/orrery/api/actions/a1/snooze {\"minutes\":60}"), ship.api.filter { "/snooze" in it })
        assertTrue(showing("Check in for DL 2627"), "still listed, to be done")
    }

    // Done takes back the nag: the app's own slot, and on iOS the ship's
    // push under its tag, which the system shows as it came.
    @Test
    fun `Done on a late one takes back its nag under both its keys`() {
        val cleared = java.util.concurrent.CopyOnWriteArrayList<String>()
        val notifier = object : io.nisfeb.talon.notify.Notifier {
            override fun notify(title: String, body: String, key: String?) {}
            override fun clear(key: String) { cleared += key }
        }
        app(ai = orreryOn, setup = { orrery(110) }, notifier = notifier) { ship ->
            openOrrery()
            waitUntil(timeoutMillis = 10_000) { showing("Late · 1") }
            onNodeWithText("Done").performClick()
            waitUntil(timeoutMillis = 10_000) { !showing("Late ·") }
            waitUntil(timeoutMillis = 10_000) { ship.api.any { it.startsWith("POST /apps/orrery/api/actions/a1 ") } }
            assertEquals(listOf("POST /apps/orrery/api/actions/a1 {\"status\":\"done\"}"), ship.api.filter { it.startsWith("POST /apps/orrery/api/actions/a1 ") })
            assertTrue("action:a1" in cleared && "orrery-overdue-a1" in cleared, cleared.toString())
        }
    }

    @Test
    fun `an orrery older than 110 shows no Late section`() = app(ai = orreryOn, setup = { orrery(109) }) { ship ->
        openOrrery()
        waitUntil(timeoutMillis = 10_000) { showing("Check in for DL 2627") }
        waitUntil(timeoutMillis = 10_000) { ship.api.any { "/api/version" in it } }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertTrue(!showing("Late") && !showing("Snooze 1 h") && !showing("Not high"))
    }

    @Test
    fun `the rail's tabs switch the list pane and Chats brings the list back`() = app {
        onNodeWithContentDescription("Bookmarks").performClick()
        // Each pane says it is empty once the ship has answered, and the app
        // is still starting: 5 s ran out on a loaded runner (2026-10-29).
        waitUntil(timeoutMillis = 20_000) { showing("No bookmarks yet") }
        assertTrue(!present("New message"))
        onNodeWithContentDescription("Activity").performClick()
        waitUntil(timeoutMillis = 20_000) { showing("No activity yet") }
        assertTrue(!showing("No bookmarks yet"), "one tab at a time")
        onNodeWithContentDescription("Chats").performClick()
        home()
    }

    // B1-5 through the shell: a group's activity row opens its page. It
    // read "group/~bus/crew" and did nothing.
    @Test
    fun `a group's activity row opens the group`() = app(
        seed = { groups().upsertGroups(listOf(io.nisfeb.talon.data.GroupEntity("~bus/crew", "The Crew", null))) },
        setup = {
            scries["activity/v6/feed/init/30"] = """{"all":[{"source-key":"group/~bus/crew","events":[
                {"time":"300","event":{"group-join":{"group":"~bus/crew","ship":"~nec"}}}
            ]}]}"""
        },
    ) {
        onNodeWithContentDescription("Activity").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Joined the group · The Crew") }
        onNodeWithText("Joined the group", substring = true).performClick()
        // The group's page: kept here, so as a member's, with no channels.
        waitUntil(timeoutMillis = 5_000) { showing("No channels in this group yet.") }
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

    // Review B1-2: below 840dp the More menu is the only navigation, and
    // it had no Home. Orrery is offered there only while it is on.
    @Test
    fun `with no rail the More menu opens Home`() = app(width = 600.dp) {
        onNodeWithContentDescription("More").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Sign out") }
        assertTrue(!showing("Orrery"), "Orrery is off")
        onNodeWithText("Home").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Set a location") }
        assertTrue(!present("New message"), "the home page, not the list")
    }

    // With a rail, Home is on it, so the overflow menu leaves it out.
    @Test
    fun `with the rail the More menu has no second Home`() = app {
        onNodeWithContentDescription("More").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Sign out") }
        assertTrue(onAllNodesWithText("Home").fetchSemanticsNodes().isEmpty())
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

    /** Standup's occurrence today, opened, renamed, and saved as this one only; [kept] is the calendar it is said to stay on. */
    private fun ComposeUiTest.renameThisStandupOnly(kept: String? = null) {
        onNodeWithText("Standup").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Close") }
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("This change applies to") }
        onNodeWithText("This one only").performScrollTo().performClick()
        field("Name").performTextReplacement("Standup, moved")
        if (kept != null) {
            // A calendar picked for it was dropped without a word: none is offered.
            waitUntil(timeoutMillis = 5_000) { showing("This one stays on $kept, with its series.") }
            assertTrue(onAllNodes(androidx.compose.ui.test.hasText("Work") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())).fetchSemanticsNodes().isEmpty())
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
        // One occurrence stays with its series, as on the calendar's page,
        // so home is the series' own and no cal goes; none is offered.
        renameThisStandupOnly(kept = "Personal")
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

    /** A task typed into the calendar's task list and added. */
    private fun ComposeUiTest.addTask(name: String) {
        onNodeWithContentDescription("Tasks").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Open") }
        onNodeWithContentDescription("New task").performClick()
        field("New task").performTextInput(name)
        onNodeWithContentDescription("Add task").performClick()
    }

    // The task's refusal came back to the screen that added it, gone by
    // then, and that screen's callback cleared it from what the calendar
    // says on opening: never said at all.
    @Test
    fun `a task refused after the calendar was left is said when it opens again`() = app(
        setup = withCalendar {
            hold = kotlinx.coroutines.CompletableDeferred()
            refuseLater = { if ("Buy bread" in it) "the task could not be read" else null }
        },
    ) { ship ->
        val cal = ship.calendar!!
        openCalendar()
        addTask("Buy bread")
        waitUntil(timeoutMillis = 5_000) { ship.requests.any { it.endsWith("/calendar.calendar") } }
        onNodeWithContentDescription("Chats").performClick()
        home()
        cal.hold!!.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { cal.outcomesRead.get() > 0 }
        waitForIdle()
        onNodeWithContentDescription("Calendar").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("\"Buy bread\" was not added: the task could not be read") }
    }

    @Test
    fun `a task refused while the calendar is open is said there once and not again`() = app(
        setup = withCalendar { refuseLater = { if ("Buy bread" in it) "the task could not be read" else null } },
    ) { ship ->
        openCalendar()
        addTask("Buy bread")
        waitUntil(timeoutMillis = 10_000) { showing("\"Buy bread\" was not added: the task could not be read") }
        onNodeWithContentDescription("Chats").performClick()
        home()
        openCalendar()
        waitForIdle()
        assertTrue(!showing("was not added"), "said once")
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

    // ---- Recording a 1:1 call (sneagan chose the chat notice, 2026-10-17) ----

    /** A call's media, as the test feeds it; [chatAtTap] is how many chat pokes there were when audio was first tapped. */
    private class RecordedEngine(val ship: FakeShip) : io.nisfeb.talon.call.CallEngine {
        private val desc = io.nisfeb.talon.call.SessionDesc("v=0\na=fingerprint:sha-256 AA:BB\n", "sha-256 AA:BB")
        override val state = kotlinx.coroutines.flow.MutableStateFlow(io.nisfeb.talon.call.MediaState.Idle)
        override suspend fun createOffer() = desc
        override suspend fun acceptOffer(remote: io.nisfeb.talon.call.SessionDesc) = desc
        override suspend fun setAnswer(remote: io.nisfeb.talon.call.SessionDesc) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override val video = kotlinx.coroutines.flow.MutableStateFlow(io.nisfeb.talon.call.VideoState())
        override suspend fun setCameraEnabled(enabled: Boolean) = false
        @Volatile var remote: ((ByteArray, Int) -> Unit)? = null
        @Volatile var chatAtTap = -1
        override fun onPcm(mic: ((pcm: ByteArray, sampleRate: Int) -> Unit)?, remote: ((pcm: ByteArray, sampleRate: Int) -> Unit)?) {
            if (remote != null && chatAtTap < 0) chatAtTap = ship.pokesTo("chat").size
            this.remote = remote
        }
        override fun close() = Unit
    }

    private val callingShip: FakeShip.() -> Unit = {
        scries["trunk/policy"] = "{}"
        scries["trunk/version"] = """{"wire":${io.nisfeb.talon.call.TrunkWire.WIRE_VERSION}}"""
    }

    /** ~bus rings, and the call is answered and live. */
    private fun ComposeUiTest.liveCallFromBus(ship: FakeShip, engine: RecordedEngine) = liveCallFromBus(ship, engine.state)

    private fun ComposeUiTest.liveCallFromBus(ship: FakeShip, media: kotlinx.coroutines.flow.MutableStateFlow<io.nisfeb.talon.call.MediaState>) {
        waitUntil(timeoutMillis = 10_000) { ship.subIds.containsKey("trunk/calls") }
        fun fact(json: String) = runBlocking { ship.emit("""{"id":${ship.subIds.getValue("trunk/calls")},"response":"diff","json":$json}""") }
        fact("""{"recv":{"from":"~bus","sig":{"ring":{"id":"r1"}}}}""")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Answer").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Answer").performClick()
        fact("""{"recv":{"from":"~bus","sig":{"offer":{"id":"r1","sdp":"v=0\na=fingerprint:sha-256 AA:BB\n","fpr":"sha-256 AA:BB"}}}}""")
        media.value = io.nisfeb.talon.call.MediaState.Live
        waitUntil(timeoutMillis = 10_000) { showing("· 00:0") }
    }

    private fun ComposeUiTest.recordTheCall() {
        onAllNodesWithContentDescription("Call controls")[0].performClick()
        onAllNodesWithText("Record")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("in your chat with") }
        onNodeWithText("Start recording").performClick()
    }

    private fun chatSaid(ship: FakeShip, text: String) = ship.pokesTo("chat").any { text in it.json.toString() }

    @Test
    fun `a call is recorded only after the DM tells them, and is offered when it ends`() {
        lateinit var engine: RecordedEngine
        app(setup = { callingShip(); engine = RecordedEngine(this) }, calls = io.nisfeb.talon.call.CallEngineProvider { engine }) { ship ->
            liveCallFromBus(ship, engine)
            recordTheCall()
            waitUntil(timeoutMillis = 10_000) { engine.remote != null }
            assertTrue(chatSaid(ship, io.nisfeb.talon.call.CallController.RECORDING_ON))
            assertEquals(1, engine.chatAtTap, "the DM went, and was taken, before any audio was tapped")
            engine.remote!!(ByteArray(960) { 1 }, 48_000)
            onAllNodesWithContentDescription("Hang up")[0].performClick()
            waitUntil(timeoutMillis = 10_000) { chatSaid(ship, io.nisfeb.talon.call.CallController.RECORDING_OFF) }
            waitUntil(timeoutMillis = 10_000) { showing("Recording finished") }
            assertTrue(showing("Captured 1 speaker"))
        }
    }

    @Test
    fun `a call whose DM the ship refuses is not recorded`() {
        lateinit var engine: RecordedEngine
        app(setup = { callingShip(); refuse = { if (it.app == "chat") "no" else null }; engine = RecordedEngine(this) }, calls = io.nisfeb.talon.call.CallEngineProvider { engine }) { ship ->
            liveCallFromBus(ship, engine)
            recordTheCall()
            waitUntil(timeoutMillis = 10_000) { showing("Not recording") }
            assertTrue(engine.chatAtTap < 0 && engine.remote == null, "nothing tapped")
        }
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

    // ---- Getting out of a page, and landing in front (review 2026-10-17) ----

    private val busSaid: suspend AppDatabase.() -> Unit = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["hello there"]}]""", "/chat"))
    }

    /** The chat with ~bus is on screen: its message, with the chat's own button beside it. */
    private fun ComposeUiTest.busChatShown() = present("Message actions") && showing("hello there")

    /** ~bus's chat, then the page of ~bus/crew from the reference in it. */
    private fun ComposeUiTest.groupPageFromChat() {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("~bus").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("~bus")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Referenced post") }
        onNodeWithText("Referenced post").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("You haven't joined this group") }
    }

    // A group's page was a plain value outside the registry: Escape closed
    // the chat hidden behind it, and a shortcut (as the rail and the iOS
    // menu) put its section down and left the page, so nothing opened.
    @Test
    fun `a group's page is left by Escape and by a shortcut, and does not come back`() = app(seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"block":{"cite":{"group":"~bus/crew"}}}]""", "/chat"))
    }) {
        groupPageFromChat()
        press(Key.Escape)
        waitUntil(timeoutMillis = 5_000) { !showing("You haven't joined this group") }
        // Back on the chat it was opened from, which is still there.
        waitUntil(timeoutMillis = 5_000) { present("Message actions") }
        onNodeWithText("Referenced post").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("You haven't joined this group") }
        press(Key.Comma, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { showing("Appearance") }
        assertTrue(!showing("You haven't joined this group"))
        onNodeWithContentDescription("Back").performClick()
        home()
        Thread.sleep(300)
        waitForIdle()
        assertTrue(!showing("You haven't joined this group"), "the group page went with the pick")
    }

    // An iPhone tap on a chat's notification with Talon left on Home landed
    // on Home: the chat was set behind a tab that takes the whole area.
    @Test
    fun `a chat asked for from Home or Mail is shown, on the Chats tab`() {
        val ui = InMemoryUiSettings()
        app(ui = ui, seed = busSaid) {
            onNodeWithContentDescription("Home").performClick()
            waitUntil(timeoutMillis = 5_000) { showing("Set a location") }
            io.nisfeb.talon.notify.OpenChatRequests.request("~bus", null)
            waitUntil(timeoutMillis = 5_000) { busChatShown() }
            assertTrue(!showing("Set a location"), "Home is put down")
            assertEquals(RailTab.Chats, ui.activeRailTab.value)
            onNodeWithContentDescription("Mail").performClick()
            waitUntil(timeoutMillis = 5_000) { showing("Mail runs in Grubbery") }
            // A tapped DM request in Activity goes the same way.
            io.nisfeb.talon.notify.OpenNoticeRequests.request("dm:~bus")
            waitUntil(timeoutMillis = 5_000) { busChatShown() && !showing("Mail runs in Grubbery") }
        }
    }

    // A chat opened from a tab that shows one beside it stays on that tab.
    @Test
    fun `a chat asked for from Bookmarks opens beside the bookmarks`() {
        val ui = InMemoryUiSettings()
        app(ui = ui, seed = busSaid) {
            onNodeWithContentDescription("Bookmarks").performClick()
            waitUntil(timeoutMillis = 5_000) { showing("No bookmarks yet") }
            io.nisfeb.talon.notify.OpenChatRequests.request("~bus", null)
            waitUntil(timeoutMillis = 5_000) { busChatShown() }
            assertTrue(showing("No bookmarks yet"), "the list is still the bookmarks")
            assertEquals(RailTab.Bookmarks, ui.activeRailTab.value)
        }
    }

    // A full-screen page outside the registry: Escape (and the iOS back
    // gesture) closed Settings, hidden behind it, and left the QR up.
    @Test
    fun `Escape leaves the login QR for the Settings it was opened from`() = app {
        onNodeWithContentDescription("Settings").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Appearance") }
        onNodeWithText("Account").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Login QR generator") }
        onNodeWithText("Login QR generator").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Generate login QR") }
        press(Key.Escape)
        waitUntil(timeoutMillis = 5_000) { !showing("Generate login QR") }
        assertTrue(showing("Appearance") && !present("Chats"), "Settings, where it was opened")
        press(Key.Escape)
        home()
    }

    // A talon:// group link from a profile opened over Contacts: the sheet
    // handed it to the system, and the group's page, once Talon had it,
    // went behind Contacts.
    @Test
    fun `a group link in a profile over Contacts opens the group in front`() = app(seed = {
        contacts().upsert(io.nisfeb.talon.data.ContactEntity("~bus", null, null, null, status = "talon://group/~bus/crew"))
    }) { ship ->
        addOnContacts("~bus")
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("contacts").size >= 2 && showing("Search contacts") }
        onAllNodesWithText("~bus")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("talon://group/~bus/crew") }
        onAllNodesWithText("talon://group/~bus/crew").onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { showing("You haven't joined this group") }
        assertTrue(!showing("Search contacts") && !showing("talon://group/~bus/crew"), "Contacts and the profile are put down")
    }

    // ---- Adding a contact (review 2026-10-17) ----

    private fun ComposeUiTest.addOnContacts(who: String) {
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Contacts").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("~patp, or a word name") }
        onNodeWithText("~patp, or a word name").performTextInput(who)
        waitForIdle()
        onNodeWithText("Add").performClick()
    }

    // The fields cleared at once and the write's end was thrown away, so a
    // refusal left nothing to try again with and said nothing.
    @Test
    fun `an add the ship refuses keeps what was typed and says why`() = app(setup = {
        refuse = { if (it.app == "contacts") "not now" else null }
    }) { ship ->
        addOnContacts("~bus")
        waitUntil(timeoutMillis = 10_000) { showing("Couldn't add ~bus: the ship refused it (not now).") }
        assertTrue(ship.pokesTo("contacts").isNotEmpty())
        assertTrue(showing("~bus"), "what was typed is still in the box")
        assertTrue(showing("Copy error details"))
    }

    @Test
    fun `an add that never reaches the ship says it calmly, as something to try again`() = app(setup = {
        lose = { if (it.app == "contacts") kotlinx.io.IOException("connection reset") else null }
    }) {
        addOnContacts("~bus")
        waitUntil(timeoutMillis = 10_000) { showing("Couldn't add ~bus: your ship is slow or out of reach. Try again when it's back.") }
        assertTrue(onAllNodes(hasSetTextAction() and androidx.compose.ui.test.hasText("~bus")).fetchSemanticsNodes().isNotEmpty(), "kept")
    }

    // Review B1-8: Remove was fire and forget, and a refusal said nothing.
    @Test
    fun `a remove the ship refuses says why and keeps the contact`() = app { ship ->
        addOnContacts("~bus")
        waitUntil(timeoutMillis = 10_000) { showing("Remove") }
        ship.refuse = { if (it.app == "contacts") "not now" else null }
        onNodeWithText("Remove").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("Couldn't remove ~bus: the ship refused it (not now).") }
        assertTrue(showing("Remove"), "still in the book, to try again")
    }

    // Leaving Contacts mid-add does not stop it: it runs on the repo's scope.
    @Test
    fun `an add left midway still reaches the ship`() = app { ship ->
        ship.holdPoke = 1_500
        addOnContacts("~bus")
        onNodeWithContentDescription("Back").performClick()
        home()
        waitUntil(timeoutMillis = 10_000) { ship.pokesTo("contacts").size >= 2 }
        ship.holdPoke = 0
        assertTrue(ship.pokesTo("contacts").any { "page" in it.json.toString() }, ship.pokesTo("contacts").toString())
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

    /** ~zod, active, and ~bus, both at the fake ship, saved in a file as on desktop. */
    private fun twoShips(): io.nisfeb.talon.urbit.SessionStore {
        val store = io.nisfeb.talon.urbit.DesktopSessionStore(File(createTempDirectory("talon-ships-").toFile(), "sessions.json"))
        store.save(io.nisfeb.talon.urbit.SavedSession("https://ship.test", "~bus", "test-session", "0v2", "ship.test"), makeActive = false)
        store.save(io.nisfeb.talon.urbit.SavedSession("https://ship.test", "~zod", "test-session", "0v1", "ship.test"))
        return store
    }

    // A5-4: the chat list's Sign out logged out with no push cleanup and
    // landed on the login form, though another ship was saved.
    @Test
    fun `Sign out from the chat list stops the ship's pushes and opens the other saved ship`() {
        val store = twoShips()
        val relay = io.nisfeb.talon.notify.InMemoryRelaySettings().apply { setDeviceIdFor("~zod", "dev-zod") }
        app(sessions = { store }, relay = relay) { ship ->
            onAllNodesWithContentDescription("More").onFirst().performClick()
            waitUntil(timeoutMillis = 5_000) { showing("Sign out") }
            onNodeWithText("Sign out").performClick()
            waitUntil(timeoutMillis = 10_000) { store.activeShip() == "~bus" && present("New message") }
            assertTrue(!showing("Connect"), "the other ship, not the login form")
            assertEquals(listOf("~bus"), store.all().map { it.ship })
            waitUntil(timeoutMillis = 5_000) { "DELETE /devices/dev-zod" in ship.requests }
            waitUntil(timeoutMillis = 5_000) { relay.deviceIdFor("~zod").isBlank() }
        }
    }

    // A5-7: Add ship restored the ship it left into the signed-out tree,
    // which then polled that ship's mail and calendar into its database.
    @Test
    fun `Add ship asks nothing of the ship it left`() = app { ship ->
        waitUntil(timeoutMillis = 10_000) { ship.requests.any { "/apps/auspex" in it } }
        Thread.sleep(1_000)
        waitForIdle()
        onNodeWithContentDescription("Switch ship").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Add ship") }
        val before = ship.requests.size
        onNodeWithText("Add ship").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Connect") }
        mainClock.advanceTimeBy(30_000)
        Thread.sleep(3_000)
        waitForIdle()
        val after = ship.requests.drop(before)
        assertTrue(after.none { "/apps/auspex" in it || "/apps/calendar" in it }, after.toString())
        assertTrue(after.none { it.startsWith("GET /~/channel/") }, "no stream for a ship not open: $after")
    }

    // Picking the ship already open in the switcher closed the chat on screen.
    @Test
    fun `picking the ship already open keeps the chat`() = app(seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["hello there"]}]""", "/chat"))
    }) {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("hello there") }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        onNodeWithContentDescription("Switch ship").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Add ship") }
        onAllNodesWithText("~zod").onLast().performClick()
        waitForIdle()
        Thread.sleep(500)
        waitForIdle()
        assertTrue(!showing("Select a chat to begin"), "the chat is still open")
    }

    // A5-18, the screens' half: a lapsed sign-in is said, calmly, on the
    // login form, with the ship's address in it; the ship stays saved, and
    // Cancel goes back to it.
    @Test
    fun `a lapsed sign-in opens the login form saying so and keeps the ship`() {
        val store = twoShips()
        app(sessions = { store }) {
            io.nisfeb.talon.ui.screens.LoginLapses.lapsed("~zod")
            waitUntil(timeoutMillis = 5_000) { showing("Connect") }
            assertTrue(showing("You were signed out of ~zod. Sign in again"), "the login form says why")
            assertTrue(showing("https://ship.test"), "the address is filled in")
            assertEquals(setOf("~zod", "~bus"), store.all().map { it.ship }.toSet(), "nothing forgotten")
            onNodeWithText("Cancel").performClick()
            home()
            assertTrue(!showing("Connect"))
            assertTrue(io.nisfeb.talon.ui.screens.LoginLapses.ships.value.isEmpty(), "said once")
        }
    }

    // A5-18 end to end: the ship refuses a lapsed login (every channel
    // request a 403), the repo says signed out, and the shell opens the
    // login form saying so, with the ship still saved.
    @Test
    fun `a login the ship refuses is said on the login form, the ship kept`() {
        val store = twoShips()
        app(sessions = { store }) { ship ->
            // Signed in, then the login lapses and the stream drops: the
            // reconnect's fresh channel is refused.
            ship.signedOut = true
            kotlinx.coroutines.runBlocking { ship.endStreams() }
            waitUntil(timeoutMillis = 20_000) { showing("You were signed out of ~zod. Sign in again") }
            assertTrue(showing("https://ship.test"), "the address is filled in")
            assertEquals(setOf("~zod", "~bus"), store.all().map { it.ship }.toSet(), "nothing forgotten")
            // B6-2: Cancel goes back to the ship, still refused, and the
            // form stays away. It came straight back: the ship's fresh
            // repo was refused again and said so. Real time, since the
            // refusal is a request.
            val asked = ship.requests.size
            onNodeWithText("Cancel").performClick()
            home()
            // The rebuilt ship asks again and is refused: what used to
            // reopen the form.
            waitUntil(timeoutMillis = 15_000) { ship.requests.drop(asked).any { "/~/channel/" in it } }
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until) {
                Thread.sleep(250)
                waitForIdle()
                assertTrue(!showing("Connect"), "the form came back after Cancel")
            }
        }
    }

    // A3-4: the list's "Draft:" previews were missing after a launch, until
    // a chat had been opened.
    @Test
    fun `a draft kept from the last run shows in the list at launch`() {
        val files = mutableMapOf(draftsFileName("~zod") to """{"~bus":"half a thought"}""")
        app(
            seed = { messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["hello there"]}]""", "/chat")) },
            drafts = FileDraftStore({ "~zod" }, read = { files[it] }, write = { name, text -> files[name] = text }),
        ) {
            onNodeWithText("DMs").performClick()
            waitUntil(timeoutMillis = 5_000) { showing("Draft: half a thought") }
        }
    }

    // A4-20: "Hide composer buttons" hid them in the chat and not in its
    // thread, in the side pane of a wide window and over the chat in a
    // narrower one.
    @Test
    fun `hidden composer buttons stay hidden in a thread`() = threadWithButtonsHidden(1200.dp)

    @Test
    fun `hidden composer buttons stay hidden in a thread over the chat`() = threadWithButtonsHidden(900.dp)

    private fun threadWithButtonsHidden(width: androidx.compose.ui.unit.Dp) = app(
        width = width,
        ui = InMemoryUiSettings(initialHide = true),
        seed = { messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["shall we meet"]}]""", "/chat")) },
    ) {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("shall we meet") }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        val opened = { onAllNodesWithText("Reply in thread").fetchSemanticsNodes().isNotEmpty() }
        for (attempt in 1..3) {
            onAllNodesWithContentDescription("Message actions").onFirst().performClick()
            if (runCatching { waitUntil(timeoutMillis = 1_500) { opened() } }.isSuccess) break
        }
        onNodeWithText("Reply in thread").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Thread").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertTrue(!present("Attach file") && !present("Attach image"), "no composer shows them")
    }

    // ---- A share from another app (B3-2) ----

    /** Uploads land, as AttachmentWithTextTest's ship has them. */
    private val hostsUploads: FakeShip.() -> Unit = {
        scries["genuine/secret"] = "\"tok-1\""
        answerApi = { method, path, _ ->
            when {
                method == "PUT" && path == "/v1/zod/upload" ->
                    """{"uploadUrl":"https://bucket.test/put-here?sig=1","hostedUrl":"https://cdn.test/zod/cat.png"}"""
                method == "PUT" && path == "/put-here" -> ""
                else -> null
            }
        }
    }

    private fun ComposeUiTest.openBusChat() {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("~bus").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("~bus")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { busChatShown() }
    }

    /** Nothing of [ship]'s says the file was uploaded or posted. */
    private fun nothingSent(ship: FakeShip) =
        ship.api.none { "/upload" in it || "/put-here" in it } && ship.pokesTo("chat").isEmpty()

    // A forged direct share uploaded and posted as the owner. It waits in
    // the chat's composer now, and goes on the owner's Send alone.
    @Test
    fun `a file shared into a chat waits in its composer and goes only on Send`() = app(seed = busSaid, setup = hostsUploads) { ship ->
        // Shared before the chat opens, as the share sheet's shortcut does.
        SharedAttachments.put("~bus", PendingAttachment(byteArrayOf(1, 2, 3, 4), "image/png", "cat.png", isImage = true))
        openBusChat()
        waitUntil(timeoutMillis = 5_000) { showing("Image, sent with your message") }
        // Real time: an upload or a post would have gone by now.
        Thread.sleep(1_500)
        waitForIdle()
        assertTrue(nothingSent(ship), "sent before Send: ${ship.api} ${ship.pokesTo("chat")}")
        onNodeWithContentDescription("Send").performClick()
        waitUntil(timeoutMillis = 10_000) { ship.pokesTo("chat").any { "cdn.test/zod/cat.png" in it.json.toString() } }
        assertTrue(ship.api.any { it.startsWith("PUT /v1/zod/upload") }, "uploaded on Send")
        waitUntil(timeoutMillis = 5_000) { !showing("Image, sent with your message") }
    }

    @Test
    fun `a file shared into the chat on screen is staged there too`() = app(seed = busSaid, setup = hostsUploads) { ship ->
        openBusChat()
        runOnIdle { SharedAttachments.put("~bus", PendingAttachment("x".encodeToByteArray(), "text/plain", "notes.txt", isImage = false)) }
        waitUntil(timeoutMillis = 5_000) { showing("File, sent with your message") }
        assertTrue(showing("notes.txt"))
        Thread.sleep(1_000)
        waitForIdle()
        assertTrue(nothingSent(ship))
        assertEquals(null, SharedAttachments.peek("~bus"), "taken once, by the composer")
    }

    // A cloud photo that never downloaded reads as no bytes: the composer
    // says why, as it does for a pick, and nothing goes.
    @Test
    fun `an empty shared file says why and sends nothing`() = app(seed = busSaid, setup = hostsUploads) { ship ->
        SharedAttachments.put("~bus", PendingAttachment(ByteArray(0), "image/png", "cloud.png", isImage = true))
        openBusChat()
        waitUntil(timeoutMillis = 5_000) { showing("came back empty") }
        assertTrue(!showing("Image, sent with your message"), "staged with nothing in it")
        assertTrue(nothingSent(ship))
    }

    // ---- The chat header on a phone (2026-10-28) ----
    // "You can't see what chat you're in on a phone": the header lost
    // its name to its buttons. Below the wide layout a channel's
    // followed threads (and party line) are in its Info.

    private val channelWithThread: suspend AppDatabase.() -> Unit = {
        val nest = "chat/~bus/general"
        groups().upsertGroups(listOf(io.nisfeb.talon.data.GroupEntity("~bus/crew", "The Crew", null)))
        groups().upsertChannelGroups(listOf(io.nisfeb.talon.data.ChannelGroupEntity(nest, "~bus/crew")))
        messages().upsert(MessageEntity(nest, "170141184506", "~bus", 1_000, """[{"inline":["lunch plans"]}]""", "/chat"))
        messages().upsert(MessageEntity(nest, "170141184507", "~nec", 2_000, """[{"inline":["count me in"]}]""", "/chat", parentId = "170141184506"))
        followedThreads().upsert(io.nisfeb.talon.data.FollowedThreadEntity(nest, "170141184506", follow = true, sent = true, atMs = 1))
    }

    @Test
    fun `in a phone-width window a channel's threads are in Info, which opens one`() = app(width = 600.dp, seed = channelWithThread) {
        io.nisfeb.talon.notify.OpenChatRequests.request("chat/~bus/general", null)
        waitUntil(timeoutMillis = 10_000) { showing("lunch plans") && present("Info") }
        assertTrue(!present("Threads you follow") && !present("Topics in this chat"), "the header is the name and Info")
        onNodeWithContentDescription("Info").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Threads you follow (1)") }
        onNodeWithText("Threads you follow (1)").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Threads you follow here") }
        onAllNodesWithText("lunch plans", substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        // The thread, its reply on show, and Info gone from under it.
        waitUntil(timeoutMillis = 5_000) { showing("count me in") }
        assertTrue(!showing("Threads you follow (1)") && !showing("This channel"))
    }

    @Test
    fun `in a wide window the header keeps them and Info beside the chat does not repeat them`() = app(seed = channelWithThread) {
        io.nisfeb.talon.notify.OpenChatRequests.request("chat/~bus/general", null)
        waitUntil(timeoutMillis = 10_000) { showing("lunch plans") && present("Info") }
        assertTrue(present("Threads you follow") && !present("Topics in this chat"))
        onNodeWithContentDescription("Info").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("This channel") }
        Thread.sleep(500)
        waitForIdle()
        assertTrue(!showing("Threads you follow ("), "beside the chat, the header has them")
    }

    // ---- The evening review (orrery 130) ----

    private fun dayMs(plusDays: Long) = java.time.LocalDate.now().plusDays(plusDays).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun dayIso(plusDays: Long) = java.time.LocalDate.now().plusDays(plusDays).toString()

    /**
     * Orrery [version] with one approved task, "Call the dentist" (a1),
     * whose todo is late a day; the owner's own "Pay rent" due today and
     * "Buy milk" with no date. The calendar applies the pokes the review
     * sends to its tasks, and refuses each one [refuse] says.
     */
    private fun evening(version: Int = 130, refuse: (String) -> Boolean = { false }): FakeShip.() -> Unit = {
        val tasks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.serialization.json.JsonObject>()
        fun put(id: String, name: String, due: Long?, orrery: String? = null) {
            tasks[id] = kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(id)); put("cal", kotlinx.serialization.json.JsonPrimitive("default"))
                put("cat", kotlinx.serialization.json.JsonPrimitive("todo"))
                put("meta", kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive(name))
                    orrery?.let { put("orrery", kotlinx.serialization.json.JsonPrimitive(it)) }
                })
                due?.let { put("due_ms", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        }
        put("t-orr", "Call the dentist", dayMs(-1), orrery = "a1")
        put("t-rent", "Pay rent", dayMs(0))
        put("t-milk", "Buy milk", null)
        calendar = FakeShip.FakeCalendar().apply {
            fun render() { this.tasks = kotlinx.serialization.json.JsonArray(tasks.values.toList()).toString() }
            render()
            refusePoke = refuse
            onPoke = { body ->
                val poke = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                val id = poke["id"]?.jsonPrimitive?.content
                when (poke["action"]?.jsonPrimitive?.content) {
                    "del-event" -> tasks.remove(id)
                    "done-event" -> tasks.computeIfPresent(id!!) { _, t -> kotlinx.serialization.json.JsonObject(t + ("done" to kotlinx.serialization.json.JsonPrimitive(true))) }
                    "edit-event" -> tasks.computeIfPresent(id!!) { _, t ->
                        kotlinx.serialization.json.JsonObject(t - "due_ms" + listOfNotNull(poke["due_ms"]?.let { "due_ms" to it }))
                    }
                }
                render()
            }
        }
        answerApi = { method, path, _ ->
            when {
                path == "/apps/orrery/api/state" -> "{}"
                path == "/apps/orrery/api/version" -> """{"version":$version}"""
                path == "/apps/orrery/api/actions" -> """[{"id":"a1","kind":"task","title":"Call the dentist","payload":{},"due":"${dayIso(-1)}T00:00:00Z","status":"approved"}]"""
                method == "POST" && path == "/apps/orrery/api/actions/a1" -> """{"ok":true}"""
                else -> null
            }
        }
    }

    /** A menu's item, not a row's label that says the same ("Today"). */
    private fun ComposeUiTest.pick(item: String) =
        onNode(androidx.compose.ui.test.hasText(item) and androidx.compose.ui.test.hasClickAction()).performClick()

    /** The review's rows have a Drop each; nothing else in the app says it. */
    private fun ComposeUiTest.reviewOpen() = onAllNodesWithText("Drop").fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.openReviewFromHome() {
        onNodeWithContentDescription("Home").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("1 overdue, 1 due today, 1 with no date") }
        onNodeWithText("Evening review").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("Overdue") && showing("No date") }
    }

    @Test
    fun `the evening review opens from Home, and a day given there reaches the calendar and orrery`() = app(ai = orreryOn, setup = evening()) { ship ->
        openReviewFromHome()
        assertTrue(showing("Call the dentist") && showing("Pay rent") && showing("Buy milk") && showing("Yesterday"))
        // The owner's own: an edit on the calendar, whole, with the new day.
        onNodeWithText("Set a day").performClick()
        pick("Tomorrow")
        waitUntil(timeoutMillis = 10_000) { !showing("Buy milk") }
        val edit = ship.calendar!!.pokes.single { "edit-event" in it }.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        assertEquals("t-milk", edit["id"]!!.jsonPrimitive.content)
        assertEquals(dayMs(1), edit["due_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals("todo", edit["cat"]!!.jsonPrimitive.content)
        // Orrery's: edited on the calendar the same, its action's id kept;
        // orrery's mirror takes the calendar's due (130 +due-ops).
        onAllNodesWithText("Move")[0].performClick()
        pick("Today")
        waitUntil(timeoutMillis = 10_000) { ship.calendar!!.pokes.any { "edit-event" in it && "t-orr" in it } }
        val moved = ship.calendar!!.pokes.single { "edit-event" in it && "t-orr" in it }.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        assertEquals(dayMs(0), moved["due_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals("a1", moved["meta"]!!.jsonObject["orrery"]!!.jsonPrimitive.content)
        assertTrue(ship.api.none { "/due" in it }, "nothing to tell orrery: ${ship.api}")
        waitUntil(timeoutMillis = 10_000) { showing("2 due today") && !showing("Overdue") }
        // Back is the home page, whose entry says the same.
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("2 due today") && !reviewOpen() }
        assertTrue(!showing("overdue,") && !showing("with no date"), "Home's entry agrees with the review")
    }

    @Test
    fun `a drop waits for Undo, and goes through though the review is left`() = app(ai = orreryOn, setup = evening()) { ship ->
        openReviewFromHome()
        // Pay rent is the second row: Overdue, then Due today.
        onAllNodesWithText("Drop")[1].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Dropped \"Pay rent\".") }
        onNodeWithText("Undo").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Pay rent") && !showing("Dropped") }
        Thread.sleep(6_000)
        assertTrue(ship.calendar!!.pokes.none { "del-event" in it }, "taken back: ${ship.calendar!!.pokes}")
        onAllNodesWithText("Drop")[1].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Dropped \"Pay rent\".") }
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 10_000) { ship.calendar!!.pokes.any { "del-event" in it } }
        assertTrue(ship.calendar!!.pokes.single { "del-event" in it }.contains("t-rent"))
    }

    @Test
    fun `dropping orrery's todo dismisses its action, and ticking one off is done on the calendar`() = app(ai = orreryOn, setup = evening()) { ship ->
        openReviewFromHome()
        onAllNodes(androidx.compose.ui.test.isToggleable())[1].performClick()
        waitUntil(timeoutMillis = 10_000) { ship.calendar!!.pokes.any { "done-event" in it && "t-rent" in it } }
        waitUntil(timeoutMillis = 10_000) { !showing("Pay rent") }
        onAllNodesWithText("Drop")[0].performClick()
        waitUntil(timeoutMillis = 10_000) { ship.api.any { it.startsWith("POST /apps/orrery/api/actions/a1 ") } }
        assertEquals(listOf("POST /apps/orrery/api/actions/a1 {\"status\":\"dismissed\",\"note\":\"Removed from the calendar.\"}"), ship.api.filter { it.startsWith("POST /apps/orrery/api/actions/a1 ") })
        assertTrue(ship.calendar!!.pokes.none { "del-event" in it })
    }

    @Test
    fun `a move the calendar refuses says so and leaves it late`() = app(ai = orreryOn, setup = evening(refuse = { "edit-event" in it })) {
        openReviewFromHome()
        onAllNodesWithText("Move")[0].performClick()
        pick("Tomorrow")
        waitUntil(timeoutMillis = 10_000) { showing("The calendar did not take the new due.") }
        assertTrue(showing("Overdue") && onAllNodesWithText("Call the dentist").fetchSemanticsNodes().isNotEmpty(), "back where it was")
        assertTrue(showing("1 overdue, 1 due today, 1 with no date"), "the counts as they were")
    }

    @Test
    fun `the review opens from Orrery and back returns there`() = app(ai = orreryOn, setup = evening()) {
        openOrrery()
        waitUntil(timeoutMillis = 10_000) { showing("1 overdue, 1 due today, 1 with no date") }
        onNodeWithText("Evening review").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("Overdue") && showing("No date") }
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 10_000) { showing("1 overdue, 1 due today, 1 with no date") && !reviewOpen() }
    }

    // The daily push's tap (orrery-evening), over whatever was open; and
    // it can be left, by Escape and for another section, as every
    // section must (rule 7).
    @Test
    fun `the evening push opens the review over an open section, and it can be left`() = app(ai = orreryOn, setup = evening()) {
        onNodeWithContentDescription("My profile").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Edit profile") }
        io.nisfeb.talon.notify.OpenNoticeRequests.request(io.nisfeb.talon.orrery.EVENING_TAG)
        waitUntil(timeoutMillis = 10_000) { showing("Overdue") && showing("No date") }
        assertTrue(!showing("Edit profile"))
        press(Key.Escape)
        home()
        assertTrue(!reviewOpen())
        io.nisfeb.talon.notify.OpenNoticeRequests.request(io.nisfeb.talon.orrery.EVENING_TAG)
        waitUntil(timeoutMillis = 10_000) { reviewOpen() }
        press(Key.Comma, ctrl = true)
        waitUntil(timeoutMillis = 5_000) { showing("Appearance") }
        assertTrue(!reviewOpen(), "put down for Settings")
        press(Key.Escape)
        home()
        Thread.sleep(500)
        waitForIdle()
        assertTrue(!reviewOpen(), "and does not come back")
    }

    @Test
    fun `an orrery older than 130 offers no evening review`() = app(ai = orreryOn, setup = evening(version = 129)) { ship ->
        openOrrery()
        waitUntil(timeoutMillis = 10_000) { ship.requests.any { "/apps/orrery/api/version" in it } }
        Thread.sleep(1_000)
        waitForIdle()
        assertTrue(!showing("Evening review"))
        onNodeWithContentDescription("Back").performClick()
        home()
        onNodeWithContentDescription("Home").performClick()
        waitUntil(timeoutMillis = 5_000) { !present("New message") }
        Thread.sleep(1_500)
        waitForIdle()
        assertTrue(!showing("Evening review"))
    }

    // ---- The sync status (2026-10-29) ----
    // A Windows user's Talon stopped syncing for three days and said
    // nothing. The chat list's header says now whether the ship is heard
    // from, and says it plainly when it is not. Short thresholds here, real
    // time, and a ship that sends eyre's heartbeat.

    private val quickSync = io.nisfeb.talon.ui.SyncThresholds(heardRecentlyMs = 3_000, connectGraceMs = 2_000)

    @Test
    fun `the header says the ship is heard from, and when it is not, since when, with Try now`() = app(sync = quickSync, setup = { heartbeat(500) }) { ship ->
        waitUntil(timeoutMillis = 10_000) { present("Connected to ~zod") }
        Thread.sleep(4_000)
        waitForIdle()
        assertTrue(present("Connected to ~zod") && !showing("Can't reach"), "the heartbeat keeps it connected on a quiet ship")
        // The network goes: the stream drops and every new one is refused.
        ship.refuseStream = true
        kotlinx.coroutines.runBlocking { ship.endStreams() }
        waitUntil(timeoutMillis = 15_000) { showing("Can't reach ~zod since") }
        assertTrue(showing("Talon keeps trying."))
        assertTrue(onAllNodesWithContentDescription("Can't reach ~zod since", substring = true).fetchSemanticsNodes().isNotEmpty(), "the mark says it too")
        // Back, and asked at once.
        ship.refuseStream = false
        onNodeWithText("Try now").performClick()
        waitUntil(timeoutMillis = 10_000) { present("Connected to ~zod") && !showing("Can't reach") }
    }

    // The reporter's case, as far as the code shows one: the ship refused
    // the login, the form was put off with Cancel, and nothing said the
    // app was no longer hearing anything.
    @Test
    fun `a lapsed login put off with Cancel is said, and Sign in brings the form back`() {
        val store = twoShips()
        app(sessions = { store }, sync = quickSync, setup = { heartbeat(500) }) { ship ->
            waitUntil(timeoutMillis = 10_000) { present("Connected to ~zod") }
            ship.signedOut = true
            kotlinx.coroutines.runBlocking { ship.endStreams() }
            waitUntil(timeoutMillis = 20_000) { showing("You were signed out of ~zod. Sign in again") }
            onNodeWithText("Cancel").performClick()
            home()
            waitUntil(timeoutMillis = 15_000) { showing("Signed out of ~zod") }
            assertTrue(showing("New messages wait until you sign in again.") && !showing("Connect"))
            onNodeWithText("Sign in").performClick()
            waitUntil(timeoutMillis = 10_000) { showing("You were signed out of ~zod. Sign in again") }
        }
    }

    @Test
    fun `while all is well there is no banner, only the mark`() = app(sync = quickSync, setup = { heartbeat(500) }) {
        waitUntil(timeoutMillis = 10_000) { present("Connected to ~zod") }
        onNodeWithContentDescription("Connected to ~zod").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Heard from it") }
        assertTrue(!showing("Can't reach") && !showing("Signed out of"))
    }

    // ---- A 1:1 call's meeting view, cameras and share: what party lines had (sneagan, 2026-10-29) ----

    /** A call's media: a camera that opens or not, and a share, as the test says. */
    private class VideoEngine : io.nisfeb.talon.call.CallEngine {
        private val desc = io.nisfeb.talon.call.SessionDesc("v=0\na=fingerprint:sha-256 AA:BB\n", "sha-256 AA:BB")
        override val state = kotlinx.coroutines.flow.MutableStateFlow(io.nisfeb.talon.call.MediaState.Idle)
        override val video = kotlinx.coroutines.flow.MutableStateFlow(io.nisfeb.talon.call.VideoState())
        val tries = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val shares = java.util.concurrent.CopyOnWriteArrayList<io.nisfeb.talon.call.ScreenSource?>()
        @Volatile var opens = true
        /** Holds a camera that is closing until completed: a restart caught midway. */
        @Volatile var closing: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun setCameraEnabled(enabled: Boolean): Boolean {
            tries += enabled
            if (!enabled) {
                closing?.await()
                video.value = video.value.copy(localOn = false)
                return true
            }
            if (opens) video.value = video.value.copy(localOn = true, sharing = false)
            return opens
        }
        override suspend fun screenSources() = listOf(screen)
        override suspend fun setScreenShare(source: io.nisfeb.talon.call.ScreenSource?): Boolean {
            shares += source
            video.value = video.value.copy(localOn = source != null, sharing = source != null)
            return true
        }
        override suspend fun createOffer() = desc
        override suspend fun acceptOffer(remote: io.nisfeb.talon.call.SessionDesc) = desc
        override suspend fun setAnswer(remote: io.nisfeb.talon.call.SessionDesc) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun close() = Unit

        companion object {
            val screen = io.nisfeb.talon.call.ScreenSource(1, "Screen", isWindow = false)
        }
    }

    private class Desk : io.nisfeb.talon.call.AudioDevices {
        override val supported = true
        override fun inputs() = listOf(io.nisfeb.talon.call.AudioDevice("mic", "Desk mic"))
        override fun outputs() = listOf(io.nisfeb.talon.call.AudioDevice("hp", "Headphones"))
    }

    private class Cameras : io.nisfeb.talon.call.VideoDevices {
        override val supported = true
        override fun cameras() = listOf(
            io.nisfeb.talon.call.VideoDevice("front", "Front camera"),
            io.nisfeb.talon.call.VideoDevice("back", "Back camera"),
        )
        @Volatile var chosen: String? = "front"
        override val selectedCamera: String? get() = chosen
        override fun selectCamera(id: String?) { chosen = id }
    }

    /** A live call from ~bus on a desktop with a microphone and two cameras. */
    private fun videoCall(block: ComposeUiTest.(FakeShip, VideoEngine, Cameras) -> Unit) {
        val engine = VideoEngine()
        val cameras = Cameras()
        app(
            setup = callingShip,
            calls = io.nisfeb.talon.call.CallEngineProvider { engine },
            audioDevices = Desk(),
            videoDevices = cameras,
        ) { ship ->
            liveCallFromBus(ship, engine.state)
            block(ship, engine, cameras)
        }
    }

    private fun ComposeUiTest.openCallMeeting() {
        if (!showing("Meeting view")) onAllNodesWithContentDescription("Call controls")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Meeting view") }
        onNodeWithText("Meeting view").performClick()
        waitUntil(timeoutMillis = 5_000) { present("Minimize the call") }
    }

    private fun ComposeUiTest.pickCamera(label: String) {
        onAllNodesWithContentDescription("Call controls")[0].performClick()
        onNodeWithContentDescription("Choose audio devices").performClick()
        waitUntil(timeoutMillis = 5_000) { showing(label) }
        onNodeWithText(label).performClick()
    }

    private fun ComposeUiTest.pickMeetingCamera(label: String) {
        onNodeWithContentDescription("Devices").performClick()
        waitUntil(timeoutMillis = 5_000) { showing(label) }
        onNodeWithText(label).performClick()
    }

    private fun ComposeUiTest.pictures() = onAllNodes(androidx.compose.ui.test.hasTestTag("call-video")).fetchSemanticsNodes()

    private fun hungUp(ship: FakeShip) = ship.pokesTo("trunk").any { "hangup" in it.json.toString() }

    @Test
    fun `a call's meeting view fills the window with its picture, and hangs up`() = videoCall { ship, engine, _ ->
        engine.video.value = io.nisfeb.talon.call.VideoState(localOn = true, remoteOn = true)
        waitUntil(timeoutMillis = 5_000) { pictures().size == 1 }
        val strip = pictures().single().boundsInRoot
        openCallMeeting()
        waitForIdle()
        assertEquals(1, pictures().size, "the picture is drawn once, not again under the meeting")
        val meeting = pictures().single().boundsInRoot
        assertTrue(meeting.height > strip.height, "the meeting's picture is the larger: $meeting, the strip's $strip")
        for (control in listOf("Devices", "Full screen", "Share", "Camera off", "Mute")) {
            assertTrue(present(control), "the meeting has $control")
        }
        onAllNodesWithContentDescription("Hang up")[0].performClick()
        waitUntil(timeoutMillis = 10_000) { hungUp(ship) }
        waitUntil(timeoutMillis = 5_000) { !present("Minimize the call") }
    }

    @Test
    fun `the meeting view minimizes to the strip, and the call goes on`() = videoCall { ship, _, _ ->
        openCallMeeting()
        onNodeWithContentDescription("Minimize the call").performClick()
        waitUntil(timeoutMillis = 5_000) { !present("Minimize the call") && present("Call controls") }
        assertTrue(showing("· 00:0"), "still on the call")
        assertTrue(!hungUp(ship))
    }

    // With the camera off there is no picture above the strip, which
    // would push its device list below this short test window.
    @Test
    fun `a camera picked on the call's strip is the one that opens`() = videoCall { _, engine, cameras ->
        pickCamera("Back camera")
        waitUntil(timeoutMillis = 5_000) { cameras.chosen == "back" }
        Thread.sleep(300)
        assertEquals(emptyList(), engine.tries.toList(), "choosing a camera did not open it")
    }

    @Test
    fun `a camera picked in the meeting view reopens on it`() = videoCall { _, engine, cameras ->
        openCallMeeting()
        onNodeWithContentDescription("Camera").performClick()
        waitUntil(timeoutMillis = 5_000) { engine.tries.toList() == listOf(true) }
        pickMeetingCamera("Back camera")
        waitUntil(timeoutMillis = 5_000) { engine.tries.size == 3 }
        assertEquals("back", cameras.chosen)
        assertEquals(listOf(true, false, true), engine.tries.toList(), "closed, then opened on the new one")
    }

    @Test
    fun `a camera that won't start says so on the call`() = videoCall { _, engine, _ ->
        engine.opens = false
        onNodeWithContentDescription("Turn the camera on").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("The camera wouldn't start") }
        engine.opens = true
        onNodeWithContentDescription("Turn the camera on").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("The camera wouldn't start") }
    }

    @Test
    fun `the meeting view shares a screen, and stops`() = videoCall { _, engine, _ ->
        openCallMeeting()
        onNodeWithContentDescription("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { engine.shares.size == 1 && present("Stop sharing") }
        onNodeWithContentDescription("Stop sharing").performClick()
        waitUntil(timeoutMillis = 5_000) { engine.shares.size == 2 }
        assertEquals(listOf(VideoEngine.screen, null), engine.shares.toList())
    }

    // A camera being switched when the meeting view closes still comes
    // back, on the new one: the switch is the call's, not the view's.
    @Test
    fun `a camera picked in the meeting view reopens after the view is closed`() = videoCall { _, engine, cameras ->
        openCallMeeting()
        onNodeWithContentDescription("Camera").performClick()
        waitUntil(timeoutMillis = 5_000) { engine.tries.toList() == listOf(true) }
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.closing = held
        pickMeetingCamera("Back camera")
        waitUntil(timeoutMillis = 5_000) { engine.tries.size == 2 }
        // The device menu stays open while picking; a click outside it shuts it first.
        repeat(2) {
            if (present("Minimize the call")) onNodeWithContentDescription("Minimize the call").performClick()
            waitForIdle()
        }
        assertTrue(!present("Minimize the call"), "the meeting view closed")
        held.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { engine.tries.size == 3 }
        assertEquals(listOf(true, false, true), engine.tries.toList())
        assertTrue(engine.video.value.localOn, "the camera is back on")
        assertEquals("back", cameras.chosen)
    }

    // The consent dialog rides with the call's controls, so the meeting
    // view asks it too: the DM tells them before any audio is tapped.
    @Test
    fun `the meeting view records only after the DM tells them`() {
        lateinit var engine: RecordedEngine
        app(setup = { callingShip(); engine = RecordedEngine(this) }, calls = io.nisfeb.talon.call.CallEngineProvider { engine }) { ship ->
            liveCallFromBus(ship, engine)
            openCallMeeting()
            onNodeWithContentDescription("Record").performClick()
            waitUntil(timeoutMillis = 5_000) { showing("in your chat with") }
            onNodeWithText("Start recording").performClick()
            waitUntil(timeoutMillis = 10_000) { engine.remote != null }
            assertTrue(chatSaid(ship, io.nisfeb.talon.call.CallController.RECORDING_ON))
            assertEquals(1, engine.chatAtTap, "the DM went, and was taken, before any audio was tapped")
            waitUntil(timeoutMillis = 5_000) { present("Stop") }
        }
    }

    // Grubbery's stock apps on the way in, the whole way through the app.
    private fun quickDefaultApps(block: () -> Unit) {
        io.nisfeb.talon.urbit.DefaultApps.afterStartMs = 0
        io.nisfeb.talon.urbit.DefaultApps.pollMs = 50
        io.nisfeb.talon.urbit.DefaultApps.waitMs = 10_000
        try { block() } finally {
            io.nisfeb.talon.urbit.DefaultApps.afterStartMs = 20_000
            io.nisfeb.talon.urbit.DefaultApps.pollMs = 20_000
            io.nisfeb.talon.urbit.DefaultApps.waitMs = 5 * 60_000
        }
    }

    @Test
    fun `a ship without Armillary and Orrery is given them on the way in, and asked to approve them`() = quickDefaultApps {
        val stockApps = listOf("lattice", "auspex", "calendar", "armillary", "orrery")
        val missing = setOf("armillary", "orrery")
        app(defaultApps = true, setup = {
            scries["groups/v2/groups"] = "{}"
            val asked = { n: String -> api.any { it == """POST /apps/grubbery/desks/sync {"name":"$n"}""" } }
            answerApi = { m, p, _ ->
                when {
                    m == "GET" && p == "/apps/grubbery/desks/stock" ->
                        stockApps.joinToString(",", "[", "]") { n -> """{"name":"$n","kind":"github","synced":${n !in missing || asked(n)}}""" }
                    m == "POST" && p == "/apps/grubbery/desks/sync" -> "syncing"
                    // Each app asks for its approvals once it is here.
                    p == "/apps/grubbery/asks.json" ->
                        missing.filter(asked).joinToString(",", "[", "]") { """{"app":"$it","poke":[],"peek":[],"make":[]}""" }
                    p == "/apps/grubbery/approved.json" -> "{}"
                    else -> null
                }
            }
        }) { ship ->
            waitUntil(timeoutMillis = 20_000) { ship.api.count { it.startsWith("POST /apps/grubbery/desks/sync ") } == 2 }
            assertEquals(
                listOf("""POST /apps/grubbery/desks/sync {"name":"armillary"}""", """POST /apps/grubbery/desks/sync {"name":"orrery"}"""),
                ship.api.filter { it.startsWith("POST /apps/grubbery/") },
                "the two it lacks, by name, and nothing for the three it has",
            )
            assertTrue(ship.pokesTo("hood").isEmpty(), "Grubbery is there: no install")
            waitUntil(timeoutMillis = 20_000) { showing("2 apps need their permissions approved") }
        }
    }

    @Test
    fun `a ship without Grubbery has it installed from ricsul on the way in`() = quickDefaultApps {
        // eyre's 404 for every Grubbery path: the desk is not there.
        app(defaultApps = true, setup = { scries["groups/v2/groups"] = "{}" }) { ship ->
            waitUntil(timeoutMillis = 20_000) { ship.pokesTo("hood").isNotEmpty() }
            val install = ship.pokesTo("hood").single()
            assertEquals("kiln-install", install.mark)
            assertEquals(
                listOf("grubbery", "~ricsul-bilwyt", "grubbery"),
                listOf("local", "ship", "desk").map { install.json.jsonObject[it]!!.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `a ship without Groups has it installed on the way in, and its chats are read once it is there`() = quickDefaultApps {
        app(defaultApps = true, setup = {
            answerApi = { m, p, _ -> if (m == "GET" && p == "/apps/grubbery/desks/stock") """[{"name":"lattice","synced":true}]""" else null }
            // %groups answers once the install has come.
            answerScry = { p -> if (p == "groups/v2/groups" && pokesTo("hood").isNotEmpty()) "{}" else null }
        }) { ship ->
            waitUntil(timeoutMillis = 20_000) { ship.pokesTo("hood").isNotEmpty() }
            val install = ship.pokesTo("hood").first()
            assertEquals("kiln-install", install.mark)
            assertEquals(
                listOf("groups", "~nisfeb", "groups"),
                listOf("local", "ship", "desk").map { install.json.jsonObject[it]!!.jsonPrimitive.content },
            )
            val streams = ship.streamsOpened.size
            // The chats it had nothing to read from before: a new channel reads them.
            waitUntil(timeoutMillis = 20_000) { ship.streamsOpened.size > streams }
        }
    }
}

