package io.nisfeb.talon.ui

import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.OrreryNoticedEntity
import io.nisfeb.talon.data.UnreadEntity
import io.nisfeb.talon.ui.screens.DmListScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import io.nisfeb.talon.data.DmInviteEntity
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The home list, from what is stored: conversations with their last
 * words and unread counts, groups under their own tab, and a tap that
 * opens the right conversation.
 */
@OptIn(ExperimentalTestApi::class)
class HomeListTest {
    private val opened = mutableListOf<String>()
    private lateinit var db: AppDatabase
    private lateinit var repo: TlonChatRepo

    /** Where the list sent the user: each menu destination by name. */
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** [shown] false takes the list off screen, as opening a chat does on a phone. */
    private fun home(
        seed: suspend AppDatabase.() -> Unit,
        synced: Boolean = false,
        shown: androidx.compose.runtime.State<Boolean> = androidx.compose.runtime.mutableStateOf(true),
        /** Groups by their latest activity, as a phone set to Recent has them; else the saved order. */
        recent: Boolean = false,
        drafts: InMemoryDraftStore = InMemoryDraftStore(),
        /** The shell's settings, as [io.nisfeb.talon.ui.LocalUiSettings] hands them on. */
        ui: io.nisfeb.talon.ui.UiSettings = io.nisfeb.talon.ui.InMemoryUiSettings(),
        /** The shells' own lists for these tabs; null hides the tab. */
        threadsTab: (@androidx.compose.runtime.Composable () -> Unit)? = null,
        partyLinesTab: (@androidx.compose.runtime.Composable () -> Unit)? = null,
        block: ComposeUiTest.(FakeShip) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-home-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        this.db = db
        val ship = FakeShip("~zod")
        val sync = if (synced) io.nisfeb.talon.urbit.SettingsSyncImpl(db = db, aiSettings = io.nisfeb.talon.urbit.FakeAiSettings()).apply { attach(ship.channel) } else null
        val repo = TlonChatRepo(db, settingsSync = sync).apply { attachForTest(ship.channel, "~zod") }
        this.repo = repo
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        runBlocking { db.seed() }
        // The list keeps its tab and folder per ship for the whole process;
        // start each test from a fresh one.
        io.nisfeb.talon.ui.screens.forgetHomeListSnapshot("~zod")
        try {
            runComposeUiTest {
                setContent {
                    androidx.compose.runtime.CompositionLocalProvider(io.nisfeb.talon.ui.LocalUiSettings provides ui) {
                    TalonTheme(darkTheme = false) {
                        if (shown.value) DmListScreen(
                            db = db, repo = repo, drafts = drafts,
                            updateState = UpdateState(scope, StaticUpdateRuntime(), NoopUpdateInstallerHook()),
                            onOpenConversation = { opened += it }, onOpenSearch = { did += "Search" }, onNewMessage = {},
                            onOpenSelfProfile = { did += "My profile" },
                            onOpenStatusFeed = { did += "Statuses" }, onOpenBookmarks = { did += "Bookmarks" },
                            onOpenActivity = { did += "Activity" }, onOpenSettings = { did += "Settings" },
                            onOpenMail = { did += "Mail" }, onOpenCalendar = { did += "Calendar" },
                            onOpenContacts = { did += "Contacts" },
                            onOpenAdministration = { did += "Group Moderation" }, onOpenInvites = { did += "Invites" },
                            onOpenHome = { did += "Home" },
                            activeShip = "~zod", allShips = listOf("~zod"),
                            folderItemOrder = if (recent) io.nisfeb.talon.ui.FolderItemOrder.Recent else io.nisfeb.talon.ui.FolderItemOrder.Manual,
                            threadsTab = threadsTab,
                            partyLinesTab = partyLinesTab,
                        )
                    }
                    }
                }
                waitForIdle()
                dumpingScreenOnFailure(ship) { block(ship) }
            }
        } finally {
            io.nisfeb.talon.ui.screens.forgetHomeListSnapshot("~zod")
            scope.cancel()
            io.nisfeb.talon.urbit.closeAfter(db, repo)
            tmp.deleteRecursively()
        }
    }

    private fun msg(whom: String, id: String, author: String, text: String, sent: Long) =
        MessageEntity(whom, id, author, sent, """[{"inline":["$text"]}]""", "/chat")

    private fun ComposeUiTest.shows(text: String, substring: Boolean = false) =
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    /** The list fills from database flows off the UI thread: wait for the target, then tap it. */
    private fun ComposeUiTest.tap(text: String, substring: Boolean = false) {
        shows(text, substring)
        onNodeWithText(text, substring = substring).performClick()
    }

    // sneagan, 2026-10-19: "mark all read should also clear the reactions".
    // A reaction to the owner notifies and is never unread: no count here,
    // but the ship keeps it on the iPhone's icon until the chat is read.
    private val reactedAndUnread = """{"ship/~bus":{"count":0,"notify-count":1,"recency":2,"notify":true,"unread":null},""" +
        """"ship/~nec":{"count":1,"notify-count":1,"recency":3,"notify":true,"unread":{"id":"~nec/170.141.184.507","time":"170.141.184.507","count":1,"notify":true}},""" +
        """"ship/~wes":{"count":0,"notify-count":0,"recency":1,"notify":false,"unread":null}}"""

    private val threeDms: suspend AppDatabase.() -> Unit = {
        messages().upsert(msg("~bus", "~zod/170141184506", "~zod", "lunch?", 1_000))
        messages().upsert(msg("~nec", "~nec/170141184507", "~nec", "see you soon", 2_000))
        messages().upsert(msg("~wes", "~wes/170141184505", "~wes", "old news", 500))
    }

    private fun readOf(p: FakeShip.Poke) = p.json.toString().substringAfter("\"ship\":\"").substringBefore("\"")

    @Test
    fun `mark all read takes the chats whose only news is a reaction, and leaves the rest`() = home(seed = threeDms) { ship ->
        ship.scries["activity/v6/activity/full"] = reactedAndUnread
        runBlocking { repo.bootstrapActivityForTest() }
        // The Unread tab comes in ahead of All once the activity lands: a
        // press made before it moves All is lost (and the tab to tap does
        // not matter: Mark all read takes every chat).
        shows("Unread", substring = true)
        waitForIdle()
        onNodeWithText("All").performTouchInput { longClick() }
        shows("Clears the unread badge on 2 chats.")
        onNodeWithText("Mark read").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("activity").size >= 2 }
        waitForIdle()
        assertEquals(setOf("~bus", "~nec"), ship.pokesTo("activity").map(::readOf).toSet(), "not ~wes: nothing there")
        assertEquals(2, ship.pokesTo("activity").size)
    }

    @Test
    fun `with only reactions left mark all read still has something to do`() = home(seed = threeDms) { ship ->
        ship.scries["activity/v6/activity/full"] = """{"ship/~bus":{"count":0,"notify-count":1,"recency":2,"notify":true,"unread":null},""" +
            """"ship/~nec":{"count":0,"notify-count":0,"recency":3,"notify":false,"unread":null}}"""
        runBlocking { repo.bootstrapActivityForTest() }
        // Mark all read takes every chat whatever tab is showing: no tab to
        // tap, which the activity just written could redraw from under it.
        waitForIdle()
        onNodeWithText("All").performTouchInput { longClick() }
        shows("Clears the unread badge on 1 chat.")
        onNodeWithText("Mark read").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("activity").isNotEmpty() }
        assertEquals(listOf("~bus"), ship.pokesTo("activity").map(::readOf))
    }

    @Test
    fun `a DM shows its peer and last words, and opens on a tap`() = home(seed = {
        messages().upsert(msg("~bus", "~bus/170141184506", "~bus", "first", 1_000))
        messages().upsert(msg("~bus", "~bus/170141184507", "~bus", "see you soon", 2_000))
    }) {
        tap("DMs")
        shows("see you soon", substring = true)
        tap("~bus")
        waitForIdle()
        assertEquals(listOf("~bus"), opened)
    }

    // In a DM every message notifies; that is unread, not a mention (2026-10-11).
    @Test
    fun `a DM that notifies, even naming us, is unread and not a mention`() = home(seed = {
        messages().upsert(msg("~bus", "~bus/170141184506", "~bus", "hey ~zod", 1_000))
        unreads().upsert(UnreadEntity("~bus", count = 1, notifyCount = 1, recencyMs = 1_000))
    }) {
        shows("Unread")
        waitForIdle()
        assertTrue(onAllNodesWithText("Mentions").fetchSemanticsNodes().isEmpty(), "no Mentions tab for a DM")
    }

    // sneagan, 2026-11-05: the list's search bar "needs to hide when not
    // in use. maybe scroll down to access it". Tucked away at first; on a
    // touch screen pulled out of the list (RevealOnPullTest), and with a
    // pointer, the header's search opens Search. (A wheel past the top
    // never reaches the list's nested scroll, so it cannot pull the bar.)
    @Test
    fun `the search bar is tucked away, and a pointer opens search from the header`() = home(seed = {
        messages().upsert(msg("~bus", "~bus/170141184506", "~bus", "hello?", 1_000))
    }) {
        assertFalse(onAllNodes(hasSetTextAction() and hasContentDescription("Search")).fetchSemanticsNodes().isNotEmpty(), "tucked away at first")
        onNodeWithContentDescription("Search messages").performClick()
        waitForIdle()
        assertTrue("Search" in did)
    }

    // A screen reader cannot pull a list: the header offers search itself.
    @Test
    fun `with the bar tucked away, the header offers search to a screen reader`() = home(seed = {}) {
        fun actionsOf(n: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.CustomAccessibilityAction> =
            if (androidx.compose.ui.semantics.SemanticsActions.CustomActions in n.config) n.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions] else emptyList()
        val header = onNode(androidx.compose.ui.test.SemanticsMatcher("offers Search") { n -> actionsOf(n).any { a -> a.label == "Search" } })
        val action = actionsOf(header.fetchSemanticsNode()).first { a -> a.label == "Search" }
        runOnIdle { action.action() }
        assertTrue("Search" in did)
    }

    @Test
    fun `an unread conversation shows how many`() = home(seed = {
        messages().upsert(msg("~bus", "~bus/170141184506", "~bus", "hello?", 1_000))
        unreads().upsert(UnreadEntity("~bus", count = 7, notifyCount = 0, recencyMs = 1_000))
    }) {
        tap("DMs · 7") // the tab carries the count
        // The bubble says its count once, as "7 unread", not its text again.
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("hello?") and hasContentDescription("7 unread")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the unread folder holds only what is unread, and a read one has no count`() = home(seed = {
        messages().upsert(msg("~bus", "~bus/170141184506", "~bus", "waiting on you", 1_000))
        messages().upsert(msg("~nec", "~nec/170141184507", "~nec", "all caught up", 2_000))
        unreads().upsert(UnreadEntity("~bus", count = 2, notifyCount = 0, recencyMs = 1_000))
        unreads().upsert(UnreadEntity("~nec", count = 0, notifyCount = 0, recencyMs = 2_000))
    }) {
        tap("DMs · 2")
        shows("all caught up")
        onAllNodes(hasText("all caught up") and hasContentDescription("unread", substring = true)).assertCountEquals(0)
        tap("Unread", substring = true)
        shows("waiting on you")
        onAllNodesWithText("all caught up").assertCountEquals(0)
    }

    @Test
    fun `a group of one channel shows under Groups, and a tap on its card opens the channel`() = home(seed = {
        groups().upsertGroups(listOf(GroupEntity("~bus/garden", "The Garden", null)))
        groups().upsertChannelGroups(listOf(ChannelGroupEntity("chat/~bus/general", "~bus/garden", title = "general")))
        messages().upsert(msg("chat/~bus/general", "170141184506", "~bus", "in the garden", 1_000))
    }) {
        tap("Groups")
        shows("~bus: in the garden", substring = true)
        assertTrue(onAllNodesWithText("channel", substring = true).fetchSemanticsNodes().isEmpty(), "one channel has nothing to unfold")
        tap("The Garden")
        waitForIdle()
        assertEquals(listOf("chat/~bus/general"), opened)
    }

    // sneagan, 2026-11-06: the Threads tab lay under the chats' own empty
    // list ("No direct messages yet"), which took its taps. A dangling
    // else: the search bar's lines (e9aa110a) went between it and the
    // list, so the list was drawn on every tab.
    @Test
    fun `the Threads and Party lines tabs show their own list, alone, and it takes taps`() {
        var taps = 0
        home(
            seed = {},
            threadsTab = { androidx.compose.material3.TextButton(onClick = { taps++ }) { androidx.compose.material3.Text("A followed thread") } },
            partyLinesTab = { androidx.compose.material3.Text("A party line") },
        ) {
            tap("Threads", substring = true)
            tap("A followed thread")
            waitForIdle()
            assertEquals(1, taps, "the tab's own list takes the tap")
            assertTrue(onAllNodesWithText("No direct messages yet", substring = true).fetchSemanticsNodes().isEmpty(), "no chats list over it")
            tap("Party lines")
            shows("A party line")
            assertTrue(onAllNodesWithText("No direct messages yet", substring = true).fetchSemanticsNodes().isEmpty())
        }
    }

    // ---- Groups as cards (sneagan, 2026-11-06), the only groups view
    // since 2026-11-08 ----

    /** A group of five channels: one unread, one with our own newest words, two quiet. */
    private val garden: suspend AppDatabase.() -> Unit = {
        groups().upsertGroups(listOf(GroupEntity("~bus/garden", "The Garden", null)))
        groups().upsertChannelGroups(listOf("general", "plans", "photos", "recipes", "books").map {
            ChannelGroupEntity("chat/~bus/$it", "~bus/garden", title = it)
        })
        messages().upsert(msg("chat/~bus/photos", "170141184501", "~bus", "look at the roses", 1_000))
        messages().upsert(msg("chat/~bus/plans", "170141184502", "~wes", "saturday works", 2_000))
        messages().upsert(msg("chat/~bus/general", "170141184503", "~nec", "see you at 7", 3_000))
        messages().upsert(msg("chat/~bus/recipes", "170141184504", "~zod", "I will bring bread", 4_000))
        unreads().upsert(UnreadEntity("chat/~bus/plans", count = 2, notifyCount = 0, recencyMs = 2_000))
    }

    @Test
    fun `as a card, a group shows its newest words with who said them, and a tap on them unfolds its channels`() = home(seed = garden) {
        tap("Groups", substring = true) // "Groups · 2": its unread ride the tab
        shows("The Garden")
        shows("You: I will bring bread", substring = true)
        assertTrue(onAllNodesWithText("· active", substring = true).fetchSemanticsNodes().isEmpty(), "a card, not the head over its channels")
        // Only the channel with news is named (sneagan: chips for every
        // channel were disliked).
        shows("New in plans 2")
        assertTrue(onAllNodesWithText("photos", substring = true).fetchSemanticsNodes().isEmpty(), "a quiet channel is not named")
        // sneagan, 2026-11-08: a tap anywhere on the card, not only on
        // "N channels", unfolds them.
        tap("You: I will bring bread", substring = true)
        listOf("general", "plans", "photos", "recipes", "books").forEach { shows(it) }
        waitForIdle()
        assertEquals(emptyList(), opened, "unfolding opens no chat")
    }

    /** Twelve channels with long names: more than one line of chips holds. */
    private val hall: suspend AppDatabase.() -> Unit = {
        groups().upsertGroups(listOf(GroupEntity("~bus/hall", "The Hall", null)))
        groups().upsertChannelGroups((1..12).map {
            ChannelGroupEntity("chat/~bus/c$it", "~bus/hall", title = "a-long-channel-name-" + "$it".padStart(2, '0'))
        })
        messages().upsert(msg("chat/~bus/c1", "170141184501", "~nec", "hello", 1_000))
    }

    // sneagan, 2026-11-06: the channels as chips were disliked. A tap on
    // the card opens the list's own channel rows, and opening them opens
    // no chat. Anywhere on it (2026-11-08): the name, or "N channels".
    @Test
    fun `a tap on a card unfolds its channel rows without opening anything, and a row opens its channel`() = home(seed = hall) {
        tap("Groups")
        shows("12 channels")
        fun rows() = onAllNodesWithText("a-long-channel-name-", substring = true).fetchSemanticsNodes()
        assertEquals(0, rows().size, "no channel names on the card")
        assertTrue(onAllNodesWithText("New in", substring = true).fetchSemanticsNodes().isEmpty(), "a quiet group's card has no line of news")
        fun said(label: String) = onAllNodes(androidx.compose.ui.test.SemanticsMatcher(label) {
            val click = androidx.compose.ui.semantics.SemanticsActions.OnClick
            click in it.config && it.config[click].label == label
        }).fetchSemanticsNodes().size
        assertEquals(1, said("Show channels"), "a screen reader hears what the tap does")
        tap("The Hall")
        waitUntil(timeoutMillis = 5_000) { rows().size == 12 }
        assertEquals(emptyList(), opened, "showing the channels opens no chat")
        tap("a-long-channel-name-02")
        waitForIdle()
        assertEquals(listOf("chat/~bus/c2"), opened, "a row opens its own channel")
        assertEquals(1, said("Hide channels"))
        tap("12 channels")
        waitUntil(timeoutMillis = 5_000) { rows().isEmpty() }
    }

    // sneagan, 2026-11-06: edge to edge, touching, a fine rule between.
    @Test
    fun `cards run edge to edge and touch, with only a rule between`() = home(seed = { garden(); hall() }) {
        tap("Groups", substring = true) // "Groups · 2": its unread ride the tab
        shows("The Garden")
        shows("The Hall")
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val garden = onNodeWithText("The Garden").fetchSemanticsNode().boundsInRoot
        val hall = onNodeWithText("The Hall").fetchSemanticsNode().boundsInRoot
        assertEquals(root.left, garden.left, 0.5f)
        assertEquals(root.right, garden.right, 0.5f)
        // Touching but for the rule: 1 dp, no more, no less.
        assertEquals(1f * density.density, hall.top - garden.bottom, 0.5f)
    }

    // sneagan, 2026-11-06: the faces of recent speakers came off the cards.
    @Test
    fun `a card shows no faces of who has been talking`() = home(seed = garden) {
        tap("Groups", substring = true) // "Groups · 2": its unread ride the tab
        shows("You: I will bring bread", substring = true)
        val faces = androidx.compose.ui.test.SemanticsMatcher("a face pile") { n ->
            val cd = androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
            cd in n.config && n.config[cd].any { it.startsWith("Recently") }
        }
        assertTrue(onAllNodes(faces, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `in a folder of the owner's, a group is a card too`() = home(seed = {
        garden()
        val id = folders().createFolder(io.nisfeb.talon.data.FolderEntity(name = "Friends"))
        folders().addMemberRaw(id, "~bus/garden", 0, io.nisfeb.talon.data.FolderMemberEntity.KIND_GROUP)
    }) {
        tap("Friends", substring = true)
        shows("You: I will bring bread", substring = true)
        assertTrue(onAllNodesWithText("· active", substring = true).fetchSemanticsNodes().isEmpty(), "a card, not the head over its channels")
    }

    @Test
    fun `a draft waiting in a channel is on the card`() {
        val drafts = InMemoryDraftStore().apply { save("chat/~bus/general", "bring the cake") }
        home(seed = garden, drafts = drafts) {
            tap("Groups", substring = true) // "Groups · 2": its unread ride the tab
            shows("Draft in general: bring the cake", substring = true)
        }
    }

    // "when you scroll down, open a group, enter a channel, and go back it
    // takes you to the top of the group list rather than keeping your
    // scroll position".
    @Test
    fun `back from a channel, the group list is where it was left`() {
        val shown = androidx.compose.runtime.mutableStateOf(true)
        home(shown = shown, seed = {
            val names = (0 until 40).map { "Group %02d".format(it) }
            groups().upsertGroups(names.mapIndexed { i, n -> GroupEntity("~bus/g$i", n, null) })
            groups().upsertChannelGroups(names.indices.map { i -> ChannelGroupEntity("chat/~bus/c$i", "~bus/g$i", title = "general") })
            // Newest first, so the list reads Group 00 down to Group 39.
            messages().upsertAll(names.indices.map { i -> msg("chat/~bus/c$i", "1701411845${100 + i}", "~bus", "hi", 100_000L - i) })
        }) {
            shows("Group 00")
            val list = io.nisfeb.talon.ui.screens.HomeListSnapshot.active!!.listState
            runOnIdle { runBlocking { list.scrollToItem(25) } }
            shows("Group 25")
            shown.value = false // into the channel
            waitForIdle()
            shown.value = true // and back
            waitForIdle()
            shows("Group 25")
            assertEquals(25, list.firstVisibleItemIndex)
            onAllNodesWithText("Group 00").assertCountEquals(0)
        }
    }

    private val manyGroups: suspend AppDatabase.() -> Unit = {
        val names = (0 until 40).map { "Group %02d".format(it) }
        groups().upsertGroups(names.mapIndexed { i, n -> GroupEntity("~bus/g$i", n, null) })
        groups().upsertChannelGroups(names.indices.map { i -> ChannelGroupEntity("chat/~bus/c$i", "~bus/g$i", title = "general") })
        // Newest first, so the list reads Group 00 down to Group 39.
        messages().upsertAll(names.indices.map { i -> msg("chat/~bus/c$i", "1701411845${100 + i}", "~bus", "hi", 100_000L - i) })
    }

    /** What new messages do to a group, the oldest here: a post, and an unread count with its recency. */
    private fun newsForLast() = runBlocking {
        db.messages().upsert(msg("chat/~bus/c39", "170141184599", "~bus", "news", 200_000L))
        db.unreads().upsert(UnreadEntity("chat/~bus/c39", count = 1, notifyCount = 0, recencyMs = 200_000L))
    }

    private fun ComposeUiTest.top(text: String) = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    // At the top, a group with new messages moved above the first row; the
    // list anchored on that row, so the group landed just out of sight.
    @Test
    fun `at the top, a conversation that moves up stays in view`() = home(seed = manyGroups, recent = true) {
        shows("Group 00")
        val list = io.nisfeb.talon.ui.screens.HomeListSnapshot.active!!.listState
        newsForLast()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Group 39").fetchSemanticsNodes().isNotEmpty() && top("Group 39") < top("Group 00")
        }
        waitForIdle()
        assertEquals(0 to 0, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
    }

    @Test
    fun `scrolled down, a conversation that moves up does not pull the list to the top`() = home(seed = manyGroups, recent = true) {
        shows("Group 00")
        val list = io.nisfeb.talon.ui.screens.HomeListSnapshot.active!!.listState
        runOnIdle { runBlocking { list.scrollToItem(10) } }
        shows("Group 10")
        newsForLast()
        waitForIdle()
        Thread.sleep(500)
        waitForIdle()
        assertTrue(list.firstVisibleItemIndex > 0, "pulled to the top")
    }

    // Moved a little by the user, the list stays where it was put: the
    // check that brings a new arrival into view must not take it back.
    @Test
    fun `a small scroll from the top stays where it was put`() = home(seed = manyGroups, recent = true) {
        shows("Group 00")
        val list = io.nisfeb.talon.ui.screens.HomeListSnapshot.active!!.listState
        onNodeWithText("Group 02").performTouchInput { swipeUp(startY = centerY, endY = centerY - 60f, durationMillis = 400) }
        waitForIdle()
        Thread.sleep(300)
        waitForIdle()
        assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0, "taken back to the top")
    }

    @Test
    fun `each tab keeps its own place across a switch`() = home(seed = {
        val names = (0 until 40).map { "Group %02d".format(it) }
        groups().upsertGroups(names.mapIndexed { i, n -> GroupEntity("~bus/g$i", n, null) })
        groups().upsertChannelGroups(names.indices.map { i -> ChannelGroupEntity("chat/~bus/c$i", "~bus/g$i", title = "general") })
        messages().upsertAll(names.indices.map { i -> msg("chat/~bus/c$i", "1701411845${100 + i}", "~bus", "hi", 100_000L - i) })
        messages().upsert(msg("~nec", "~nec/170141184506", "~nec", "a DM", 1_000))
    }) {
        shows("Group 00")
        val list = io.nisfeb.talon.ui.screens.HomeListSnapshot.active!!.listState
        runOnIdle { runBlocking { list.scrollToItem(25) } }
        tap("DMs")
        shows("a DM")
        assertEquals(0, list.firstVisibleItemIndex, "DMs from its own top")
        tap("Groups")
        shows("Group 25")
        assertEquals(25, list.firstVisibleItemIndex)
    }

    // ─── DM requests ──────────────────────────────────────────────

    private fun rsvp(ship: FakeShip): Pair<String, String> {
        val j = ship.pokesTo("chat").single().json.jsonObject
        return j["ship"]!!.jsonPrimitive.content to j["ok"]!!.jsonPrimitive.content
    }

    @Test
    fun `a DM request is accepted from the list and opens the chat`() = home(seed = {
        dmInvites().upsertAll(listOf(DmInviteEntity("~bus", 1)))
    }) { ship ->
        shows("Requests")
        tap("Accept")
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("chat").isNotEmpty() }
        assertEquals("~bus" to "true", rsvp(ship))
        assertEquals(listOf("~bus"), opened)
    }

    // 2026-11-08: the ship sends a stranger's messages whether or not the
    // DM is accepted, and they showed as an ordinary chat beside the
    // request. They wait under Requests; accepted, the chat is there.
    @Test
    fun `a stranger's message waits under Requests, not as a chat, until it is accepted`() = home(seed = {
        dmInvites().upsertAll(listOf(DmInviteEntity("~bus", 1)))
        messages().upsert(msg("~bus", "~bus/170141184506", "~bus", "hey stranger", 1_000))
        messages().upsert(msg("~nec", "~nec/170141184507", "~nec", "a friend says hi", 2_000))
    }) {
        tap("DMs")
        shows("a friend says hi", substring = true)
        shows("wants to message you", substring = true)
        assertTrue(onAllNodesWithText("hey stranger", substring = true).fetchSemanticsNodes().isEmpty(), "not a chat yet")
        tap("Accept")
        shows("hey stranger", substring = true)
    }

    // With only people added, a stranger's request folds under one line,
    // closed; a favourite's stays in view (2026-11-08).
    @Test
    fun `with only people added, strangers' requests fold under one line`() = home(
        ui = io.nisfeb.talon.ui.InMemoryUiSettings().apply {
            setReach(io.nisfeb.talon.ui.ReachSettings(whoCanMessage = io.nisfeb.talon.ui.WhoCanMessage.Added, favourites = setOf("~nec")))
        },
        seed = { dmInvites().upsertAll(listOf(DmInviteEntity("~bus", 1), DmInviteEntity("~wes", 2), DmInviteEntity("~nec", 3))) },
    ) {
        shows("~nec")
        shows("2 from people you haven't added")
        assertTrue(onAllNodesWithText("~bus").fetchSemanticsNodes().isEmpty(), "folded away")
        tap("2 from people you haven't added")
        shows("~bus")
        shows("~wes")
    }

    @Test
    fun `a request is read with a tap before it is answered`() = home(seed = {
        dmInvites().upsertAll(listOf(DmInviteEntity("~bus", 1)))
    }) { ship ->
        tap("wants to message you", substring = true)
        waitForIdle()
        assertEquals(listOf("~bus"), opened)
        assertTrue(ship.pokesTo("chat").isEmpty(), "reading answers nothing")
    }

    @Test
    fun `a DM request declined is refused on the ship and opens nothing`() = home(seed = {
        dmInvites().upsertAll(listOf(DmInviteEntity("~bus", 1)))
    }) { ship ->
        tap("Decline")
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("chat").isNotEmpty() }
        assertEquals("~bus" to "false", rsvp(ship))
        assertTrue(opened.isEmpty())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Requests").fetchSemanticsNodes().isEmpty() }
    }

    // ─── what Orrery noticed ───────────────────────────────────────

    private fun noticed(id: String, subject: String, attr: String, value: String, snippet: String, at: Long) = OrreryNoticedEntity(
        id = id, ship = "~zod", subject = subject, attr = attr, valueJson = value, atMs = 1_000, untilMs = null, conf = 80,
        sourceKind = "chat", sourceId = "~bus/1", bodyJson = null, whom = "~bus", postId = "1", snippet = snippet,
        state = "pending", createdMs = at,
    )

    @Test
    fun `claims Orrery noticed wait above the list, each confirmed or discarded`() = home(seed = {
        orreryNoticed().insertIfNew(noticed("n1", "person/bus", "birthday", "\"12 March\"", "mine's the 12th of March", at = 2_000))
        orreryNoticed().insertIfNew(noticed("n2", "person/me", "manager", """{"ref":"person/nec"}""", "nec runs my team now", at = 1_000))
    }) {
        shows("Noticed")
        shows("~bus: birthday is 12 March")
        shows("You: manager is ~nec")
        shows("mine's the 12th of March")
        // Newest first.
        onAllNodesWithText("Confirm")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.orreryNoticed().get("n1")?.state } == "confirming" }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("~bus: birthday is 12 March").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Discard").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.orreryNoticed().get("n2")?.state } == "discarded" }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Noticed").fetchSemanticsNodes().isEmpty() }
    }

    // ─── mentions ──────────────────────────────────────────────────

    @Test
    fun `mentions of us are gathered, even where nothing is cached, and open on a tap`() = home(seed = {
        messages().upsert(msg("chat/~bus/talk", "170141184506", "~bus", "hey ~zod look at this", 2_000))
        messages().upsert(msg("chat/~dev/talk", "170141184507", "~dev", "nothing about you", 3_000))
        // A DM notifies for every message, so even one naming us is not a mention.
        messages().upsert(msg("~nec", "~nec/170141184508", "~nec", "dm for ~zod", 4_000))
        unreads().upsert(UnreadEntity("chat/~bus/talk", count = 1, notifyCount = 1, recencyMs = 2_000))
        unreads().upsert(UnreadEntity("chat/~dev/talk", count = 1, notifyCount = 1, recencyMs = 3_000))
        unreads().upsert(UnreadEntity("~nec", count = 1, notifyCount = 1, recencyMs = 4_000))
        unreads().upsert(UnreadEntity("chat/~nec/general", count = 2, notifyCount = 2, recencyMs = 1_000))
    }) {
        tap("Mentions", substring = true)
        shows("hey ~zod look at this", substring = true)
        onAllNodesWithText("nothing about you", substring = true).assertCountEquals(0)
        onAllNodesWithText("dm for ~zod", substring = true).assertCountEquals(0)
        // Nothing cached for the channel: a row made from what is known of it.
        val placeholder = onAllNodes(hasText("general", substring = true) and hasContentDescription("2 unread")).fetchSemanticsNodes()
        assertTrue(placeholder.isNotEmpty(), "the channel's mention is listed with its count")
        onAllNodes(hasText("general", substring = true) and hasContentDescription("2 unread"))[0].performClick()
        waitForIdle()
        assertEquals(listOf("chat/~nec/general"), opened)
    }

    // ─── the More menu and folders ─────────────────────────────────

    @Test
    fun `every place in the More menu opens`() = home(seed = {}) {
        val places = listOf("Home", "My profile", "Mail", "Bookmarks", "Activity", "Calendar",
            "Group Moderation", "Invites", "Settings")
        for (place in places) {
            onNodeWithContentDescription("More").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(place).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText(place).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            waitUntil(timeoutMillis = 5_000) { did.lastOrNull() == place }
        }
        assertEquals(places, did.toList())
    }

    @Test
    fun `statuses, contacts and the sidebar are one entry in the More menu, and sign out is not there`() = home(seed = {}) {
        for (place in listOf("Statuses", "Contacts")) {
            onNodeWithContentDescription("More").performClick()
            assertTrue(onAllNodesWithText("Sign out").fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText(place).fetchSemanticsNodes().isEmpty(), "folded until opened")
            onNodeWithText("People & sidebar").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(place).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText(place).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            waitUntil(timeoutMillis = 5_000) { did.lastOrNull() == place }
        }
    }

    @Test
    fun `a folder is made from the plus tab, and opens saying how to fill it`() = home(seed = {}, synced = true) { ship ->
        tap("+")
        shows("New folder")
        onNodeWithText("Create").assertIsNotEnabled()
        onNode(hasSetTextAction() and hasText("Name")).performTextInput("Friends")
        onNodeWithText("Create").performClick()
        tap("Friends")
        shows("This folder is empty. Right-click a chat or group to add it.")
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("settings").any { "Friends" in it.json.toString() } }
    }

    @Test
    fun `a folder is renamed and deleted from its tab while reordering`() = home(seed = {}, synced = true) { ship ->
        tap("+")
        onNode(hasSetTextAction() and hasText("Name")).performTextInput("Friends")
        onNodeWithText("Create").performClick()
        tap("Friends")
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Reorder").performClick()
        onNodeWithContentDescription("Rename folder").performClick()
        shows("Edit folder")
        onNodeWithText("Rename").assertIsNotEnabled()
        onNode(hasSetTextAction() and hasText("Friends")).performTextReplacement("Mates")
        onNodeWithText("Rename").performClick()
        shows("Mates")
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("settings").any { "put-entry" in it.json.toString() && "Mates" in it.json.toString() } }
        onNodeWithContentDescription("Rename folder").performClick()
        shows("Edit folder")
        onNodeWithText("Delete").performClick()
        // And asked again, saying the conversations stay.
        shows("The conversations themselves stay", substring = true)
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Mates").fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("settings").any { "del-entry" in it.json.toString() && "\"bucket-key\":\"folders\"" in it.json.toString() } }
    }

    @Test
    fun `without a settings sink no folder is made`() = home(seed = {}) {
        tap("+")
        onNode(hasSetTextAction() and hasText("Name")).performTextInput("Friends")
        onNodeWithText("Create").performClick()
        Thread.sleep(300)
        waitForIdle()
        assertTrue(onAllNodesWithText("Friends").fetchSemanticsNodes().isEmpty())
    }
}
