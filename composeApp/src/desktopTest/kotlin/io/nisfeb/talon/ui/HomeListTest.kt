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

    private fun home(seed: suspend AppDatabase.() -> Unit, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-home-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
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
}
