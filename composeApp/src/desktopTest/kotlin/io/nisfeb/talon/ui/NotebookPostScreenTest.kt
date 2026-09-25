package io.nisfeb.talon.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.screens.NotebookPostScreen
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A notebook post in a group channel: read with its comments, edited
 * from its own markdown, deleted after asking, and commented on, with a
 * refused comment marked as not sent rather than looking posted.
 */
@OptIn(ExperimentalTestApi::class)
class NotebookPostScreenTest {
    private val whom = "diary/~bus/blog"
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun post(author: String, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-diary-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        runBlocking {
            db.messages().upsertAll(listOf(
                MessageEntity(whom, "170141184500100", author, 100, """[{"inline":["Hello ",{"bold":["world"]}]}]""", "/diary",
                    title = "Spring", image = "https://x.test/cover.png"),
                MessageEntity(whom, "170141184500200", "~nec", 200, """[{"inline":["lovely"]}]""", "/diary", parentId = "170141184500100"),
            ))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        NotebookPostScreen(
                            db = db, repo = repo, ourPatp = "~zod", whom = whom, postId = "170141184500100",
                            onBack = { did += "back" },
                            onEdit = { title, image, body, sent -> did += "edit $title $image $body $sent" },
                        )
                    }
                }
                waitUntil(timeoutMillis = 5_000) { shows("Comments · 1") }
                block(ship)
            }
        } finally {
            scope.cancel()
            runBlocking { repo.stopAndJoinForTest() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 5_000) { shows(text) }

    @Test
    fun `a post reads with its title, body and comments`() = post("~bus") { _ ->
        assertTrue(shows("Spring") && shows("Hello world") && shows("lovely"))
        assertTrue(onAllNodesWithText("More").fetchSemanticsNodes().isEmpty(), "someone else's post has no menu")
    }

    @Test
    fun `our post is edited from its own markdown`() = post("~zod") { _ ->
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Edit").performClick()
        assertEquals(listOf("edit Spring https://x.test/cover.png Hello **world** 100"), did)
    }

    @Test
    fun `our post is deleted after asking`() = post("~zod") { ship ->
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Delete").performClick()
        showing("Delete post?")
        onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("channels").isNotEmpty() }
        assertTrue("\"del\"" in ship.pokesTo("channels").single().json.toString())
    }

    @Test
    fun `a comment goes under the post`() = post("~bus") { ship ->
        onNode(hasSetTextAction()).performTextInput("agreed")
        onNodeWithContentDescription("Send").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("channels").isNotEmpty() }
        val reply = ship.pokesTo("channels").single().json.toString()
        assertTrue("reply" in reply && "agreed" in reply, reply)
        showing("Comments · 2")
    }

    @Test
    fun `a refused comment says so, keeps its text, and is marked not sent`() = post("~bus") { ship ->
        ship.refuse = { if (it.app == "channels") "no" else null }
        onNode(hasSetTextAction()).performTextInput("agreed")
        onNodeWithContentDescription("Send").performClick()
        showing("Couldn't send")
        val box = onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("agreed", box, "the text is back to send again")
        showing("Not sent")
    }
}
