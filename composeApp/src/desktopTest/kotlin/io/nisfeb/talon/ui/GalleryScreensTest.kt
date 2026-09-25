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
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.screens.GalleryComposeScreen
import io.nisfeb.talon.ui.screens.GalleryGridScreen
import io.nisfeb.talon.ui.screens.GalleryPostScreen
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
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A gallery channel: the grid of posts, one post with its comments, and
 * a new post of text or a link, each checked by the poke it sends.
 */
@OptIn(ExperimentalTestApi::class)
class GalleryScreensTest {
    private val whom = "heap/~bus/pics"
    private val did: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Pages a link's preview is read from. */
    private val web = HttpClient(MockEngine { req ->
        val html = """<html><head><title>Plain</title><meta property="og:title" content="A fine page"></head></html>"""
        if (req.url.host == "fine.example") respond(html, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        else respond("", HttpStatusCode.NotFound)
    })

    private fun post(id: String, author: String, json: String, parent: String? = null) =
        MessageEntity(whom, id, author, id.takeLast(3).toLong(), json, "/heap", parentId = parent)

    private fun gallery(block: ComposeUiTest.(FakeShip) -> Unit, content: @Composable (TlonChatRepo, AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-heap-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        runBlocking {
            db.messages().upsertAll(listOf(
                post("170141184500100", "~bus", """[{"inline":["a note on the wall"]}]"""),
                post("170141184500200", "~zod", """[{"block":{"image":{"src":"https://x.test/cat.png","alt":"a cat","height":300,"width":400}}}]"""),
                post("170141184500300", "~nec", """[{"inline":["nice cat"]}]""", parent = "170141184500200"),
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
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 5_000) { shows(text) }

    /** What the last post or reply sent to %channels carried. */
    private fun ComposeUiTest.sent(ship: FakeShip): String {
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("channels").isNotEmpty() }
        return ship.pokesTo("channels").last().json.toString()
    }

    // ─── the grid ─────────────────────────────────────────────────

    @Test
    fun `the grid shows each post by its kind and opens it`() = gallery({ _ ->
        showing("a note on the wall")
        assertTrue(!shows("nice cat"), "comments stay inside their post")
        onNodeWithText("a note on the wall").performClick()
        onNodeWithContentDescription("New post").performClick()
        assertEquals(listOf("open 170141184500100", "compose"), did)
    }) { repo, db ->
        GalleryGridScreen(db, repo, web, whom, onBack = {}, onOpenPost = { did += "open $it" }, onCompose = { did += "compose" })
    }

    // ─── one post ─────────────────────────────────────────────────

    @Test
    fun `a post shows its comments and takes a new one`() = gallery({ ship ->
        showing("Comments · 1")
        assertTrue(shows("nice cat"))
        onNode(hasSetTextAction()).performTextInput("agreed")
        onNodeWithContentDescription("Send").performClick()
        val reply = sent(ship)
        assertTrue("reply" in reply && "agreed" in reply, reply)
    }) { repo, db ->
        GalleryPostScreen(db, repo, web, ourPatp = "~zod", whom = whom, postId = "170141184500200", onBack = {})
    }

    @Test
    fun `our own post can be deleted, after asking`() = gallery({ ship ->
        showing("Comments · 1")
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Delete").performClick()
        showing("Delete post?")
        onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        assertTrue("\"del\"" in sent(ship))
    }) { repo, db ->
        GalleryPostScreen(db, repo, web, ourPatp = "~zod", whom = whom, postId = "170141184500200", onBack = { did += "back" })
    }

    @Test
    fun `someone else's post offers no delete`() = gallery({ _ ->
        showing("No comments yet")
        assertTrue(onAllNodesWithText("More").fetchSemanticsNodes().isEmpty())
        assertEquals(0, onAllNodesWithText("Delete").fetchSemanticsNodes().size)
    }) { repo, db ->
        GalleryPostScreen(db, repo, web, ourPatp = "~zod", whom = whom, postId = "170141184500100", onBack = {})
    }

    // ─── a new post ───────────────────────────────────────────────

    @Test
    fun `a text post keeps its lines, and breaks them as the ship reads them`() = gallery({ ship ->
        onNodeWithText("Post").assertIsNotEnabled()
        onNodeWithText("Text").performClick()
        onNode(hasSetTextAction() and hasText("Text")).performTextInput("first line\nsecond line")
        onNodeWithText("Post").performClick()
        val body = sent(ship)
        assertTrue("first line" in body && "second line" in body && "\"break\":null" in body, body)
        waitUntil(timeoutMillis = 5_000) { did == listOf("posted") }
    }) { repo, _ ->
        GalleryComposeScreen(repo, web, whom, onBack = { did += "back" }, onPosted = { did += "posted" })
    }

    @Test
    fun `a link post carries the page's title`() = gallery({ ship ->
        onNodeWithText("Link").performClick()
        onNode(hasSetTextAction() and hasText("URL")).performTextInput("https://fine.example/page")
        onNodeWithText("Post").performClick()
        val body = sent(ship)
        assertTrue("https://fine.example/page" in body && "A fine page" in body, body)
    }) { repo, _ ->
        GalleryComposeScreen(repo, web, whom, onBack = {}, onPosted = {})
    }

    @Test
    fun `leaving a started post asks, and Keep editing keeps it`() = gallery({ _ ->
        onNodeWithText("Text").performClick()
        onNode(hasSetTextAction() and hasText("Text")).performTextInput("half a thought")
        onNodeWithContentDescription("Back").performClick()
        showing("Discard this post?")
        onNodeWithText("Keep editing").performClick()
        assertTrue(shows("half a thought") && did.isEmpty())
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Discard").performClick()
        assertEquals(listOf("back"), did)
    }) { repo, _ ->
        GalleryComposeScreen(repo, web, whom, onBack = { did += "back" }, onPosted = {})
    }
}
