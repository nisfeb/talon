package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.screens.NotebookComposeScreen
import io.nisfeb.talon.ui.screens.NotebookListScreen
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
 * A notebook channel's list and its composer: posts by title, a new
 * post written in markdown, an edit that keeps what it does not touch,
 * and a draft not thrown away without asking.
 */
@OptIn(ExperimentalTestApi::class)
class NotebookDiaryScreensTest {
    private val whom = "diary/~bus/blog"
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun diary(block: ComposeUiTest.(FakeShip) -> Unit, content: @Composable (TlonChatRepo, AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-blog-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        runBlocking {
            db.messages().upsertAll(listOf(
                MessageEntity(whom, "170141184500100", "~bus", 100, """[{"inline":["first words"]}]""", "/diary", title = "Spring"),
                MessageEntity(whom, "170141184500200", "~nec", 200, """[{"inline":["later words"]}]""", "/diary", title = "Summer"),
            ))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent { TalonTheme(darkTheme = false) { content(repo, db) } }
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

    private fun ComposeUiTest.posted(ship: FakeShip): String {
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("channels").isNotEmpty() }
        return ship.pokesTo("channels").single().json.toString()
    }

    @Test
    fun `the list shows posts by title, newest first, and opens one`() = diary({ _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Summer") }
        val summer = onNodeWithText("Summer").fetchSemanticsNode().boundsInRoot.top
        val spring = onNodeWithText("Spring").fetchSemanticsNode().boundsInRoot.top
        assertTrue(summer < spring, "newest first")
        onNodeWithText("Spring").performClick()
        onNodeWithContentDescription("New post").performClick()
        assertEquals(listOf("open 170141184500100", "compose"), did)
    }) { repo, db ->
        NotebookListScreen(db, repo, whom, onBack = {}, onOpenPost = { did += "open $it" }, onCompose = { did += "compose" })
    }

    @Test
    fun `a new post needs a title, and goes out with its markdown as a story`() = diary({ ship ->
        onNodeWithText("Post").assertIsNotEnabled()
        onNode(hasSetTextAction() and hasText("Title")).performTextInput("Autumn")
        onNode(hasSetTextAction() and hasText("Body (markdown)")).performTextInput("Leaves **fall**.")
        onNodeWithText("Post").performClick()
        val essay = posted(ship)
        assertTrue("\"title\":\"Autumn\"" in essay && "\"bold\":[\"fall\"]" in essay && "/diary" in essay, essay)
        waitUntil(timeoutMillis = 5_000) { did == listOf("posted") }
    }) { repo, _ ->
        NotebookComposeScreen(repo, whom, onBack = { did += "back" }, onPosted = { did += "posted" })
    }

    @Test
    fun `an edit keeps the post's time and sends the new words`() = diary({ ship ->
        onNode(hasSetTextAction() and hasText("Body (markdown)")).performTextReplacement("Rain, mostly.")
        onNodeWithText("Save").performClick()
        val edit = posted(ship)
        assertTrue("\"edit\"" in edit && "170.141.184.500.100" in edit && "Rain, mostly." in edit && "\"sent\":100" in edit, edit)
    }) { repo, _ ->
        NotebookComposeScreen(
            repo, whom, onBack = {}, onPosted = {},
            editPostId = "170141184500100", initialTitle = "Spring", initialBody = "first words", originalSentMs = 100,
        )
    }

    @Test
    fun `leaving a started draft asks, and Keep writing keeps it`() = diary({ _ ->
        onNode(hasSetTextAction() and hasText("Title")).performTextInput("Winter")
        onNodeWithContentDescription("Back").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Discard this draft?") }
        onNodeWithText("Keep writing").performClick()
        assertTrue(did.isEmpty() && shows("Winter"))
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Discard").performClick()
        assertEquals(listOf("back"), did)
    }) { repo, _ ->
        NotebookComposeScreen(repo, whom, onBack = { did += "back" }, onPosted = {})
    }
}
