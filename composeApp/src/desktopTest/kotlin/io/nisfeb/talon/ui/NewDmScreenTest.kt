package io.nisfeb.talon.ui

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
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Mittens").fetchSemanticsNodes().isNotEmpty() }
        block()
    }

    private fun ComposeUiTest.type(text: String) = onNode(hasSetTextAction() and hasText("~ship, or a word name")).performTextInput(text)

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
}
