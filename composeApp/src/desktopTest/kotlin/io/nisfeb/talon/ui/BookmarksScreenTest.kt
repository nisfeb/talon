package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.BookmarkEntity
import io.nisfeb.talon.data.BookmarkFolderEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.screens.BookmarksScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.SettingsSyncImpl
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bookmarks: what was kept and where it opens, folders made and chosen
 * to narrow the list, a bookmark filed into one, and folders renamed and
 * deleted, each kept here and sent to %settings.
 */
@OptIn(ExperimentalTestApi::class)
class BookmarksScreenTest {
    private val did = mutableListOf<String>()

    private fun bookmarks(block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-marks-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
        val repo = TlonChatRepo(db, settingsSync = sync).apply { attachForTest(ship.channel, "~zod") }
        runBlocking {
            db.messages().upsertAll(listOf(
                MessageEntity("~bus", "~bus/1", "~bus", 10, """[{"inline":["the recipe for soup"]}]""", "/chat"),
                MessageEntity("chat/~bus/general", "2", "~nec", 20, """[{"inline":["meeting notes"]}]""", "/chat"),
            ))
            db.bookmarks().upsert(BookmarkEntity("~bus", "~bus/1", 100))
            db.bookmarks().upsert(BookmarkEntity("chat/~bus/general", "2", 200))
            db.bookmarkFolders().upsert(BookmarkFolderEntity(id = 1, name = "Kitchen"))
            db.bookmarkFolders().addMember(1, "~bus", "~bus/1")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        BookmarksScreen(db = db, repo = repo, onOpenConversation = { whom, id -> did += "open $whom $id" }, onBack = {})
                    }
                }
                waitUntil(timeoutMillis = 5_000) { shows("meeting notes") }
                block(ship, db)
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.settled(what: () -> Boolean) = waitUntil(timeoutMillis = 5_000, condition = what)

    private fun sent(ship: FakeShip) = ship.pokesTo("settings").map { it.json.toString() }

    @Test
    fun `what was kept is listed newest first, and opens where it was said`() = bookmarks { _, _ ->
        val notes = onNodeWithText("meeting notes").fetchSemanticsNode().boundsInRoot.top
        val soup = onNodeWithText("the recipe for soup").fetchSemanticsNode().boundsInRoot.top
        assertTrue(notes < soup, "newest bookmark first")
        onNodeWithText("the recipe for soup").performClick()
        assertEquals(listOf("open ~bus ~bus/1"), did)
    }

    @Test
    fun `a folder narrows the list to what is in it`() = bookmarks { _, _ ->
        onNodeWithText("Kitchen").performClick()
        settled { !shows("meeting notes") }
        assertTrue(shows("the recipe for soup"))
        onNodeWithText("All").performClick()
        settled { shows("meeting notes") }
    }

    @Test
    fun `a new folder is kept here and sent`() = bookmarks { ship, db ->
        onNodeWithText("+ New").performClick()
        onNode(hasSetTextAction()).performTextInput("Work")
        onNodeWithText("Save").performClick()
        settled { runBlocking { db.bookmarkFolders().streamFolders().first().any { it.name == "Work" } } }
        settled { sent(ship).any { "bookmark-folders" in it && "Work" in it } }
    }

    @Test
    fun `a bookmark is filed into a folder from its long press`() = bookmarks { ship, db ->
        onNodeWithText("meeting notes").performTouchInput { longClick() }
        onNodeWithText("Add to folder…").performClick()
        settled { shows("Add to folder") }
        onAllNodesWithText("Kitchen").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        settled {
            runBlocking { db.bookmarkFolders().streamMembers().first() }.any { it.folderId == 1L && it.postId == "2" }
        }
        settled { sent(ship).any { "bookmark-folder-members" in it && "chat/~bus/general" in it } }
    }

    @Test
    fun `a folder's menu opens with a right-click too, and a tap still chooses it`() = bookmarks { _, _ ->
        onNodeWithText("Kitchen").performMouseInput { rightClick() }
        settled { shows("Rename") }
    }

    @Test
    fun `a folder is renamed and deleted from its long press, and the bookmarks stay`() = bookmarks { ship, db ->
        onNodeWithText("Kitchen").performTouchInput { longClick() } // as on a phone
        onNodeWithText("Rename").performClick()
        onNode(hasSetTextAction()).performTextReplacement("Kitchen things")
        onNodeWithText("Save").performClick()
        settled { shows("Kitchen things") }
        onNodeWithText("Kitchen things").performTouchInput { longClick() }
        onNodeWithText("Delete folder").performClick()
        settled { shows("The bookmarks themselves stay.") }
        onNodeWithText("Delete").performClick()
        settled { runBlocking { db.bookmarkFolders().streamFolders().first() }.isEmpty() }
        assertEquals(2, runBlocking { db.bookmarks().all() }.size)
        assertTrue(sent(ship).any { "del-entry" in it && "bookmark-folders" in it })
    }
}
