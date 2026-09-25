package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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

/**
 * The home list, from what is stored: conversations with their last
 * words and unread counts, groups under their own tab, and a tap that
 * opens the right conversation.
 */
@OptIn(ExperimentalTestApi::class)
class HomeListTest {
    private val opened = mutableListOf<String>()
    private lateinit var db: AppDatabase

    private fun home(seed: suspend AppDatabase.() -> Unit, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-home-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        this.db = db
        val ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        runBlocking { db.seed() }
        // The list keeps its tab and folder per ship for the whole process;
        // start each test from a fresh one.
        io.nisfeb.talon.ui.screens.forgetHomeListSnapshot("~zod")
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        DmListScreen(
                            db = db, repo = repo, drafts = InMemoryDraftStore(),
                            updateState = UpdateState(scope, StaticUpdateRuntime(), NoopUpdateInstallerHook()),
                            onOpenConversation = { opened += it }, onOpenSearch = {}, onNewMessage = {},
                            onSignOut = {}, onOpenSelfProfile = {}, onOpenStatusFeed = {}, onOpenBookmarks = {},
                            onOpenActivity = {}, onOpenSettings = {},
                            activeShip = "~zod", allShips = listOf("~zod"),
                        )
                    }
                }
                waitForIdle()
                block(ship)
            }
        } finally {
            io.nisfeb.talon.ui.screens.forgetHomeListSnapshot("~zod")
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun msg(whom: String, id: String, author: String, text: String, sent: Long) =
        MessageEntity(whom, id, author, sent, """[{"inline":["$text"]}]""", "/chat")

    private fun ComposeUiTest.shows(text: String, substring: Boolean = false) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    /** The list fills from database flows off the UI thread: wait for the target, then tap it. */
    private fun ComposeUiTest.tap(text: String, substring: Boolean = false) {
        shows(text, substring)
        onNodeWithText(text, substring = substring).performClick()
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

    @Test
    fun `an unread conversation shows how many`() = home(seed = {
        messages().upsert(msg("~bus", "~bus/170141184506", "~bus", "hello?", 1_000))
        unreads().upsert(UnreadEntity("~bus", count = 7, notifyCount = 0, recencyMs = 1_000))
    }) {
        tap("DMs · 7") // the tab carries the count
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("hello?") and hasText("7")).fetchSemanticsNodes().isNotEmpty() }
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
        onAllNodes(hasText("all caught up") and hasText("2")).assertCountEquals(0)
        tap("Unread", substring = true)
        shows("waiting on you")
        onAllNodesWithText("all caught up").assertCountEquals(0)
    }

    @Test
    fun `a group shows under Groups with its channels, and a channel opens`() = home(seed = {
        groups().upsertGroups(listOf(GroupEntity("~bus/garden", "The Garden", null)))
        groups().upsertChannelGroups(listOf(ChannelGroupEntity("chat/~bus/general", "~bus/garden", title = "general")))
        messages().upsert(msg("chat/~bus/general", "170141184506", "~bus", "in the garden", 1_000))
    }) {
        tap("Groups")
        shows("The Garden")
        shows("1 channel", substring = true)
        tap("The Garden") // a tap opens the group on its channel list
        tap("general")
        waitForIdle()
        assertEquals(listOf("chat/~bus/general"), opened)
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
        messages().upsert(msg("~bus", "~bus/170141184506", "~bus", "hey ~zod look at this", 2_000))
        messages().upsert(msg("~dev", "~dev/170141184507", "~dev", "nothing about you", 3_000))
        unreads().upsert(UnreadEntity("~bus", count = 1, notifyCount = 1, recencyMs = 2_000))
        unreads().upsert(UnreadEntity("~dev", count = 1, notifyCount = 1, recencyMs = 3_000))
        unreads().upsert(UnreadEntity("chat/~nec/general", count = 2, notifyCount = 2, recencyMs = 1_000))
    }) {
        tap("Mentions", substring = true)
        shows("hey ~zod look at this", substring = true)
        onAllNodesWithText("nothing about you", substring = true).assertCountEquals(0)
        // Nothing cached for the channel: a row made from what is known of it.
        val placeholder = onAllNodes(hasText("general", substring = true) and hasText("2")).fetchSemanticsNodes()
        assertTrue(placeholder.isNotEmpty(), "the channel's mention is listed with its count")
        onAllNodes(hasText("general", substring = true) and hasText("2"))[0].performClick()
        waitForIdle()
        assertEquals(listOf("chat/~nec/general"), opened)
    }
}
