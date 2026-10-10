package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.ui.screens.NewDmScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Starting a conversation: by @p, by a contact's name, from the list, and adding them. */
@OptIn(ExperimentalTestApi::class)
class NewDmScreenTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-newdm-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val did = mutableListOf<String>()

    init {
        runBlocking {
            db.contacts().upsert(ContactEntity("~mitlyn-ditrel", "Mittens", null, null))
            db.contacts().upsert(ContactEntity("~sampel-palnet", null, null, null))
        }
    }

    @AfterTest
    fun close() = db.close()

    private fun newDm(book: Set<String> = setOf("~mitlyn-ditrel"), block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                NewDmScreen(
                    db = db, onPickPeer = { did += "start $it" }, onBack = {},
                    onAddContact = { ship, nick -> did += "add $ship $nick" }, bookContacts = book,
                    onJoinGroup = { did += "join $it" },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Mittens").fetchSemanticsNodes().isNotEmpty() }
        block()
    }

    private fun ComposeUiTest.type(text: String) = onNode(hasSetTextAction() and hasText("~ship, a word name, or a group reference")).performTextInput(text)

    @Test
    fun `a ship typed with or without its sig starts a conversation`() = newDm {
        type("dopzod-bitnux")
        onNodeWithText("Start").assertIsEnabled().performClick()
        assertEquals(listOf("start ~dopzod-bitnux"), did)
    }

    @Test
    fun `words that are not a ship cannot start one`() = newDm {
        type("hello there")
        onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test
    fun `a contact's name finds them, in the list and for Start`() = newDm {
        type("mitt")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("~sampel-palnet").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Mittens").performClick()
        assertEquals(listOf("start ~mitlyn-ditrel"), did)
    }

    @Test
    fun `a ship not in the book can be added, with a name`() = newDm {
        type("~dopzod-bitnux")
        onNode(hasSetTextAction() and hasText("nickname (optional)")).performTextInput("Doppel")
        onNodeWithText("Add contact").performClick()
        assertEquals(listOf("add ~dopzod-bitnux Doppel"), did)
    }

    @Test
    fun `a ship already in the book is not offered to be added`() = newDm {
        type("~mitlyn-ditrel")
        waitForIdle()
        assertTrue(onAllNodesWithText("Add contact").fetchSemanticsNodes().isEmpty())
    }

    // A group reference copied from a group's info pane, or Tlon's (2026-10-09).
    @Test
    fun `a pasted group reference offers to join the group, and joins once confirmed`() = newDm {
        type("/1/group/~bus/the-club")
        onNodeWithText("Start").assertIsNotEnabled()
        onNodeWithText("Join group ~bus/the-club").performClick()
        onNodeWithText("Join group?").assertExists()
        assertEquals(emptyList(), did, "nothing before the confirm")
        onNodeWithText("Join").performClick()
        assertEquals(listOf("join ~bus/the-club"), did)
    }

    @Test
    fun `a group's flag alone offers the same`() = newDm {
        type("~bus/the-club")
        onNodeWithText("Join group ~bus/the-club").assertExists()
    }

    @Test
    fun `a ship offers no group to join`() = newDm {
        type("~dopzod-bitnux")
        assertTrue(onAllNodesWithText("Join group", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `enter after a pasted group code asks to join it`() = newDm {
        type("/1/group/~bus/the-club")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Join group?").assertExists()
        assertEquals(emptyList(), did)
    }
}
