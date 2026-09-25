package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.ui.screens.ContactsScreen
import io.nisfeb.talon.ui.screens.StatusFeedScreen
import io.nisfeb.talon.ui.theme.TalonTheme
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contact book (listed, searched, added to, pruned) and the status
 * feed (others' statuses newest first, ours set and cleared).
 */
@OptIn(ExperimentalTestApi::class)
class ContactsAndStatusTest {
    private val tmp = createTempDirectory(prefix = "talon-book-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    init {
        runBlocking {
            db.contacts().upsert(ContactEntity("~mitlyn-ditrel", "Mittens", null, null, status = "baking", statusUpdatedMs = 10))
            db.contacts().upsert(ContactEntity("~dopzod-bitnux", "Doppel", null, null, status = "away", statusUpdatedMs = 20))
            db.contacts().upsert(ContactEntity("~sampel-palnet", null, null, null))
            db.contacts().upsert(ContactEntity("~zod", "Zod", null, null, status = "here", statusUpdatedMs = 5))
        }
    }

    @AfterTest
    fun close() { db.close(); tmp.deleteRecursively() }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun book(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                ContactsScreen(
                    db = db, bookContacts = setOf("~mitlyn-ditrel", "~dopzod-bitnux"),
                    onAddContact = { ship, nick -> did += "add $ship $nick" },
                    onRemoveContact = { did += "remove $it" },
                    onOpenContact = { did += "open $it" },
                    onBack = {},
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { shows("Mittens") }
        block()
    }

    @Test
    fun `the book lists only its own, searches, opens and removes`() = book {
        assertTrue(shows("Doppel") && !shows("~sampel-palnet"), "a known ship outside the book is not listed")
        onNode(hasSetTextAction() and hasText("Search contacts")).performTextInput("mitt")
        waitUntil(timeoutMillis = 5_000) { !shows("Doppel") }
        onNodeWithText("Mittens").performClick()
        onNodeWithText("Remove").performClick()
        assertEquals(listOf("open ~mitlyn-ditrel", "remove ~mitlyn-ditrel"), did)
    }

    @Test
    fun `a ship already in the book, or words that are not a ship, cannot be added`() = book {
        onNode(hasSetTextAction() and hasText("~patp, or a word name")).performTextInput("~mitlyn-ditrel")
        onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun `a new ship goes in with its nickname`() = book {
        onNode(hasSetTextAction() and hasText("~patp, or a word name")).performTextInput("sampel-palnet")
        onNode(hasSetTextAction() and hasText("nickname")).performTextInput("Sam")
        onNodeWithText("Add").assertIsEnabled().performClick()
        assertEquals(listOf("add ~sampel-palnet Sam"), did)
    }

    // ─── the status feed ──────────────────────────────────────────

    private fun feed(block: ComposeUiTest.(FakeShip) -> Unit) {
        val ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        StatusFeedScreen(db = db, repo = repo, ourPatp = "~zod", onOpenContact = { did += "open $it" }, onBack = {})
                    }
                }
                waitUntil(timeoutMillis = 5_000) { shows("baking") }
                block(ship)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `others' statuses come newest first, without ours, and open the person`() = feed { _ ->
        val away = onNodeWithText("away").fetchSemanticsNode().boundsInRoot.top
        val baking = onNodeWithText("baking").fetchSemanticsNode().boundsInRoot.top
        assertTrue(away < baking, "newest first")
        assertEquals(1, onAllNodesWithText("here").fetchSemanticsNodes().size, "ours once, in its own row")
        onNodeWithText("away").performClick()
        assertEquals(listOf("open ~dopzod-bitnux"), did)
    }

    @Test
    fun `our status is set, and cleared, here and on the ship`() = feed { ship ->
        onNodeWithContentDescription("Edit status").performClick()
        onNode(hasSetTextAction()).performTextReplacement("here and there")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("here and there") }
        onNodeWithContentDescription("Edit status").performClick()
        onNodeWithText("Clear status").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Tap to set a status") }
        val sent = ship.pokesTo("contacts").map { it.json.toString() }
        assertTrue(sent.size == 2 && "here and there" in sent[0] && "\"value\":\"\"" in sent[1], sent.toString())
    }
}
