package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.createAppHttpClient
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
import kotlin.test.assertTrue

/**
 * The conversation screen end to end: what it shows from the database,
 * and what typing and sending does, down to the poke a [FakeShip]
 * receives. The repo and the rows underneath have their own tests;
 * these are the wiring between them and the screen.
 */
@OptIn(ExperimentalTestApi::class)
class DmChatScreenTest {
    private val threads = mutableListOf<String>()

    private fun chat(
        seed: suspend AppDatabase.() -> Unit = {},
        prepare: FakeShip.() -> Unit = {},
        block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-dmchat-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply(prepare)
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        // Acks reach the waiting pokes only while the stream is read.
        val stream = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(stream)
        runBlocking { db.seed() }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        DmChatScreen(
                            db = db, repo = repo, drafts = InMemoryDraftStore(), http = createAppHttpClient(),
                            aiSettings = FakeAiSettings(), uiSettings = InMemoryUiSettings(),
                            ourPatp = "~zod", whom = "~bus",
                            onBack = {}, onOpenThread = { threads += it }, onOpenConversation = {},
                            onOpenImage = {}, onOpenSelfProfile = {},
                        )
                    }
                }
                waitForIdle()
                block(ship, db)
            }
        } finally {
            stream.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun msg(id: String, author: String, text: String, sent: Long, deleted: Boolean = false, parent: String? = null) =
        MessageEntity("~bus", id, author, sent, """[{"inline":["$text"]}]""", "/chat", isDeleted = deleted, parentId = parent)

    private fun ComposeUiTest.send(text: String) {
        onNode(hasSetTextAction()).performTextInput(text)
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
    }

    @Test
    fun `the conversation shows its messages and not the deleted ones`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "hello from bus", 1_000))
        messages().upsert(msg("~bus/170141184507", "~bus", "taken back", 2_000, deleted = true))
    }) { _, _ ->
        onNodeWithText("hello from bus").assertIsDisplayed()
        onAllNodesWithText("taken back").assertCountEquals(0)
    }

    @Test
    fun `enter sends what was typed to the ship and shows it at once`() = chat { ship, _ ->
        send("hi bus")
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("chat").isNotEmpty() }
        val poke = ship.pokesTo("chat").single()
        assertEquals("chat-dm-action-2", poke.mark)
        assertTrue("hi bus" in poke.json.toString())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("hi bus").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a message the ship refuses says it failed`() = chat(prepare = { refuse = { if (it.app == "chat") "nope" else null } }) { _, _ ->
        send("will not land")
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithContentDescription("Send failed").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("will not land").assertIsDisplayed()
    }

    @Test
    fun `a reply count opens the thread`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "a question", 1_000))
        messages().upsert(msg("~zod/170141184507", "~zod", "an answer", 2_000, parent = "~bus/170141184506"))
    }) { _, _ ->
        onNodeWithText("1 reply").performClick()
        waitForIdle()
        assertEquals(listOf("~bus/170141184506"), threads)
        onAllNodesWithText("an answer").assertCountEquals(0)
    }

    @Test
    fun `a reaction shows beneath its message`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "react to me", 1_000))
        reactions().upsert(ReactionEntity("~bus", "~bus/170141184506", "~nec", "🔥"))
    }) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("🔥", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
