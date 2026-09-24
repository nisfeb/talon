package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ThreadUnreadEntity
import io.nisfeb.talon.ui.screens.ThreadList
import io.nisfeb.talon.ui.theme.TalonTheme
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
 * A thread, opened: its parent and replies, fetched fresh from the ship,
 * read on opening, and answered from its own composer. A [FakeShip]
 * stands in for the ship.
 */
@OptIn(ExperimentalTestApi::class)
class ThreadListTest {
    private val parent = "~bus/170141184506"

    private fun thread(
        seed: suspend AppDatabase.() -> Unit = {},
        prepare: FakeShip.() -> Unit = {},
        block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-thread-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply(prepare)
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val stream = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(stream)
        runBlocking { db.seed() }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        ThreadList(
                            db = db, repo = repo, http = createAppHttpClient(), drafts = InMemoryDraftStore(),
                            ourPatp = "~zod", whom = "~bus", parentId = parent, initialScrollReplyId = null,
                            onOpenConversation = {}, onOpenImage = {},
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

    private fun msg(id: String, author: String, text: String, sent: Long, parentId: String? = null, status: String? = null) =
        MessageEntity("~bus", id, author, sent, """[{"inline":["$text"]}]""", "/chat", parentId = parentId, status = status)

    private fun essay(author: String, text: String, sent: Long) =
        """{"content":[{"inline":["$text"]}],"author":"$author","sent":$sent,"blob":null}"""

    /** Waits for [text]; if it never comes, says what the screen showed instead. */
    private fun ComposeUiTest.shows(text: String) {
        runCatching {
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }.onFailure {
            val screen = onAllNodes(isRoot()).printToString(maxDepth = 60).lines()
                .filter { "Text = " in it }.joinToString(" | ") { it.substringAfter("Text = ") }
            error("never showed \"$text\"; the screen showed: $screen")
        }
    }

    @Test
    fun `a thread shows its parent and its replies, and nothing else`() = thread(seed = {
        messages().upsert(msg(parent, "~bus", "the question", 1_000))
        messages().upsert(msg("~zod/170141184507", "~zod", "first answer", 2_000, parentId = parent))
        messages().upsert(msg("~nec/170141184508", "~nec", "second answer", 3_000, parentId = parent))
        messages().upsert(msg("~bus/170141184509", "~bus", "elsewhere", 4_000))
    }) { _, _ ->
        onNodeWithText("the question").assertIsDisplayed()
        onNodeWithText("first answer").assertIsDisplayed()
        onNodeWithText("second answer").assertIsDisplayed()
        onAllNodesWithText("elsewhere").assertCountEquals(0)
    }

    @Test
    fun `opening a thread fetches replies the database did not have`() = thread(prepare = {
        // The stored id is undotted; the ship is asked with its dots back.
        scries["chat/v4/dm/~bus/writs/writ/id/~bus/170.141.184.506"] = """{
            "seal":{"id":"$parent","reacts":{},"meta":{"replyCount":1,"lastReply":null,"lastRepliers":[]},
              "replies":{"x":{"seal":{"id":"~nec/170141184507","parent-id":"$parent","reacts":{}},
                              "reply-essay":${essay("~nec", "fetched answer", 2_000)}}}},
            "essay":${essay("~bus", "the question", 1_000)}}"""
    }) { ship, db ->
        // Stored first, then shown: a failure here says which half broke.
        runCatching {
            waitUntil(timeoutMillis = 5_000) { runBlocking { db.messages().getOne("~bus", "~nec/170141184507") } != null }
        }.onFailure { error("the fetch never stored the reply; the ship was asked ${ship.scried}") }
        shows("the question")
        shows("fetched answer")
    }

    @Test
    fun `opening a thread reads it, here and on the ship`() = thread(seed = {
        messages().upsert(msg(parent, "~bus", "the question", 1_000))
        threadUnreads().upsert(ThreadUnreadEntity("~bus", parent, count = 2, notifyCount = 0, recencyMs = 1))
    }) { ship, db ->
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.threadUnreads().getOne("~bus", parent) } == null }
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("activity").isNotEmpty() }
    }

    @Test
    fun `enter answers in the thread`() = thread(seed = {
        messages().upsert(msg(parent, "~bus", "the question", 1_000))
    }) { ship, _ ->
        onNode(hasSetTextAction()).performTextInput("my answer")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("chat").isNotEmpty() }
        val reply = ship.pokesTo("chat").single().json.toString()
        assertTrue("\"reply\"" in reply && "my answer" in reply, reply)
        shows("my answer")
    }

    @Test
    fun `a reply that did not go says so, and one on its way says that`() = thread(seed = {
        messages().upsert(msg(parent, "~bus", "the question", 1_000))
        messages().upsert(msg("~zod/170141184507", "~zod", "refused one", 2_000, parentId = parent, status = "failed"))
        // An hour on, so it heads its own group: the clock sits in the header.
        messages().upsert(msg("~zod/170141184508", "~zod", "in flight", 3_602_000, parentId = parent, status = "pending"))
    }) { _, _ ->
        shows("refused one")
        assertEquals(1, onAllNodesWithContentDescription("Send failed").fetchSemanticsNodes().size)
        onAllNodesWithText("Not sent").assertCountEquals(1)
        assertEquals(1, onAllNodesWithContentDescription("Sending").fetchSemanticsNodes().size)
    }
}
