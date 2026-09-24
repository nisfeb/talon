package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

    private fun home(
        seed: suspend AppDatabase.() -> Unit = {},
        statuses: List<ContactEntity> = emptyList(),
        invites: List<String> = emptyList(),
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
}
