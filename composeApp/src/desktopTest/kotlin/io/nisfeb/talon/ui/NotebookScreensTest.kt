package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.screens.NoteScreen
import io.nisfeb.talon.ui.screens.NotesChannelScreen
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
 * A notebook channel and one of its notes, over a notebook on a
 * [FakeShip]: folders walked into and out of, a note made, a note read,
 * edited against its revision, refused when someone saved first,
 * published and deleted.
 */
@OptIn(ExperimentalTestApi::class)
class NotebookScreensTest {
    private val whom = "notes/~bus/recipes"
    private val did = mutableListOf<String>()

    private fun notebook(block: ComposeUiTest.(FakeShip, TlonChatRepo) -> Unit, content: @androidx.compose.runtime.Composable (TlonChatRepo) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-nbui-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply {
            scries["notes/v0/notebooks"] = """[{"flagName":"recipes","host":"~bus","notebook":{"title":"Recipes","id":7,"rootFolderId":8,
                "createdBy":"~bus","createdAt":1784592399,"updatedAt":1784592399,"updatedBy":"~bus"},"visibility":"private"}]"""
            scries["notes/v0/folders/~bus/recipes"] = """[
                {"name":"/","notebookId":7,"id":8,"createdBy":"~bus","createdAt":1,"parentFolderId":null,"updatedAt":1,"updatedBy":"~bus"},
                {"name":"Soups","notebookId":7,"id":9,"createdBy":"~bus","createdAt":1,"parentFolderId":8,"updatedAt":1,"updatedBy":"~bus"}]"""
            scries["notes/v0/notes/~bus/recipes"] = """[{"folderId":9,"notebookId":7,"title":"Pho","revision":3,"id":11,"createdBy":"~bus",
                "createdAt":1784592455,"bodyMd":"Simmer **long**.","updatedAt":1784592505,"updatedBy":"~bus","slug":null}]"""
            scries["notes/v0/published"] = "[]"
        }
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod"); notes.attach(ship.channel) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runBlocking { repo.notes.bootstrap() }
            runComposeUiTest {
                setContent { TalonTheme(darkTheme = false) { content(repo) } }
                block(ship, repo)
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun channel(block: ComposeUiTest.(FakeShip, TlonChatRepo) -> Unit) = notebook(block) { repo ->
        NotesChannelScreen(repo = repo, whom = whom, onBack = { did += "back" }, onOpenNote = { did += "note $it" })
    }

    private fun note(block: ComposeUiTest.(FakeShip, TlonChatRepo) -> Unit) = notebook(block) { repo ->
        NoteScreen(repo = repo, whom = whom, noteId = 11, onBack = { did += "back" })
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 5_000) { shows(text) }

    // ─── the channel ──────────────────────────────────────────────

    @Test
    fun `folders open into their notes, and back climbs out before leaving`() = channel { _, _ ->
        showing("Soups")
        assertTrue(!shows("Pho"), "the note is inside the folder")
        onNodeWithText("Soups").performClick()
        showing("Pho")
        onNodeWithText("Pho").performClick()
        onNodeWithContentDescription("Back").performClick()
        showing("Soups")
        onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("note 11", "back"), did)
    }

    @Test
    fun `a new note is made in the folder being looked at`() = channel { ship, _ ->
        showing("Soups")
        onNodeWithText("Soups").performClick()
        showing("Pho")
        onNodeWithContentDescription("Add").performClick()
        onNodeWithText("New note").performClick()
        onNode(hasSetTextAction()).performTextInput("Laksa")
        onNodeWithText("Create").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("notes").isNotEmpty() }
        val made = ship.pokesTo("notes").single().json.toString()
        assertTrue("create-note" in made && "Laksa" in made && "\"folder\":9" in made, made)
    }

    // ─── one note ─────────────────────────────────────────────────

    @Test
    fun `a note reads formatted, with its revision`() = note { _, _ ->
        showing("rev 3")
        assertTrue(shows("Pho") && shows("Simmer"))
        assertTrue(!shows("**"), "the markdown is drawn, not shown")
    }

    @Test
    fun `an edit is saved against the revision it was opened at`() = note { ship, _ ->
        ship.answerApi = { method, _, _ -> if (method == "PUT") """{"body":{"type":"ok"}}""" else null }
        showing("rev 3")
        onNodeWithContentDescription("Edit").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Markdown") }
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("Simmer all day.")
        onNodeWithContentDescription("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { !shows("Markdown") }
        val sent = ship.api.single()
        assertTrue("\"expectedRevision\":3" in sent && "Simmer all day." in sent, sent)
    }

    @Test
    fun `a save someone else beat says it could not save`() = note { ship, _ ->
        ship.answerApi = { _, _, _ -> """{"body":{"type":"error","errorType":"revision-mismatch"}}""" }
        showing("rev 3")
        onNodeWithContentDescription("Edit").performClick()
        onNode(hasSetTextAction()).performTextInput(" more")
        onNodeWithContentDescription("Save").performClick()
        showing("Couldn't save")
        onNodeWithText("OK").performClick()
        assertTrue(shows("Markdown"), "the edit is still there to keep or copy")
    }

    @Test
    fun `publishing asks first, sends the HTML, and gives the address`() = note { ship, _ ->
        showing("rev 3")
        onNodeWithContentDescription("Publish to web").performClick()
        showing("Publish to the web?")
        onNodeWithText("Publish").performClick()
        showing("/notes/pub/~bus/recipes/11")
        assertTrue("<strong>long</strong>" in ship.pokesTo("notes").single().json.toString())
        onNodeWithContentDescription("Unpublish").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("notes").size == 2 }
    }

    @Test
    fun `deleting asks, then leaves the note`() = note { ship, _ ->
        showing("rev 3")
        onNodeWithContentDescription("Delete").performClick()
        showing("Delete note?")
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 5_000) { did == listOf("back") }
        assertTrue("\"delete\"" in ship.pokesTo("notes").single().json.toString())
    }
}
