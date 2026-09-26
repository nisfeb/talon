package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import kotlinx.coroutines.launch
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

    private val soups = """{"name":"Soups","notebookId":7,"id":9,"createdBy":"~bus","createdAt":1,"parentFolderId":8,"updatedAt":1,"updatedBy":"~bus"}"""

    private fun notebook(
        block: ComposeUiTest.(FakeShip, TlonChatRepo) -> Unit,
        moreFolders: String = "",
        /** What the host says is published; null for a host that does not answer. */
        published: String? = "[]",
        content: @androidx.compose.runtime.Composable (TlonChatRepo) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-nbui-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply {
            scries["notes/v0/notebooks"] = """[{"flagName":"recipes","host":"~bus","notebook":{"title":"Recipes","id":7,"rootFolderId":8,
                "createdBy":"~bus","createdAt":1784592399,"updatedAt":1784592399,"updatedBy":"~bus"},"visibility":"private"}]"""
            scries["notes/v0/folders/~bus/recipes"] = """[
                {"name":"/","notebookId":7,"id":8,"createdBy":"~bus","createdAt":1,"parentFolderId":null,"updatedAt":1,"updatedBy":"~bus"},
                $soups$moreFolders]"""
            scries["notes/v0/notes/~bus/recipes"] = """[{"folderId":9,"notebookId":7,"title":"Pho","revision":3,"id":11,"createdBy":"~bus",
                "createdAt":1784592455,"bodyMd":"Simmer **long**.","updatedAt":1784592505,"updatedBy":"~bus","slug":null}]"""
            if (published != null) scries["notes/v0/published"] = published
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

    private fun channel(moreFolders: String = "", block: ComposeUiTest.(FakeShip, TlonChatRepo) -> Unit) = notebook(block, moreFolders) { repo ->
        NotesChannelScreen(repo = repo, whom = whom, onBack = { did += "back" }, onOpenNote = { did += "note $it" })
    }

    private fun note(published: String? = "[]", block: ComposeUiTest.(FakeShip, TlonChatRepo) -> Unit) = notebook(block, published = published) { repo ->
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

    private val laksa = """{"folderId":9,"notebookId":7,"title":"Laksa","revision":1,"id":12,"createdBy":"~zod",
        "createdAt":1784592600,"bodyMd":"","updatedAt":1784592600,"updatedBy":"~zod","slug":null}"""
    private val stocks = """{"name":"Stocks","notebookId":7,"id":12,"createdBy":"~zod","createdAt":1,"parentFolderId":8,"updatedAt":1,"updatedBy":"~zod"}"""

    /** Add something through the "+" menu: [what] is "New note" or "New folder". */
    private fun ComposeUiTest.add(what: String, name: String) {
        onNodeWithContentDescription("Add").performClick()
        onNodeWithText(what).performClick()
        onNode(hasSetTextAction()).performTextInput(name)
        onNodeWithText("Create").performClick()
    }

    // A create went as a channel poke, which the ship answers as soon as
    // it has it: the host's refusal never came back, and nothing appeared.
    // It goes to %notes' REST route now, which answers once the host has.

    @Test
    fun `a new note is made in the folder being looked at, and shows once the host has it`() = channel { ship, _ ->
        ship.answerApi = { method, path, _ ->
            if (method == "POST" && path.endsWith("/notes")) {
                ship.scries["notes/v0/notes/~bus/recipes"] = ship.scries.getValue("notes/v0/notes/~bus/recipes").trimEnd().removeSuffix("]") + ",$laksa]"
                """{"body":{"type":"ok","response":{}}}"""
            } else null
        }
        showing("Soups")
        onNodeWithText("Soups").performClick()
        showing("Pho")
        add("New note", "Laksa")
        showing("Laksa")
        assertEquals(listOf("""POST /notes/~/v1/notebooks/~bus/recipes/notes {"folder":9,"title":"Laksa","body":""}"""), ship.api.toList())
    }

    @Test
    fun `a new folder is made where it is asked for, and shows once the host has it`() = channel { ship, _ ->
        ship.answerApi = { method, path, _ ->
            if (method == "POST" && path.endsWith("/folders")) {
                ship.scries["notes/v0/folders/~bus/recipes"] = ship.scries.getValue("notes/v0/folders/~bus/recipes").trimEnd().removeSuffix("]") + ",$stocks]"
                """{"body":{"type":"ok","response":{}}}"""
            } else null
        }
        showing("Soups")
        add("New folder", "Stocks")
        showing("Stocks")
        // folderName, not name: the route's path has a name already.
        assertEquals(listOf("""POST /notes/~/v1/notebooks/~bus/recipes/folders {"folderName":"Stocks","parent":8}"""), ship.api.toList())
    }

    @Test
    fun `a folder the host will not take says why`() = channel { ship, _ ->
        ship.answerApi = { _, _, _ -> """{"body":{"type":"error","errorType":"not-authorized","message":[]}}""" }
        showing("Soups")
        add("New folder", "Stocks")
        showing("You can't add to this notebook")
        assertTrue(!shows("Stocks"))
    }

    @Test
    fun `a note the host has not answered for yet says so`() = channel { ship, _ ->
        ship.answerApi = { _, _, _ -> """{"body":{"type":"pending","status":"sending"}}""" }
        showing("Soups")
        add("New note", "Laksa")
        showing("hasn't answered yet")
    }

    @Test
    fun `a ship that cannot take it says so`() = channel { _, _ ->
        // answerApi says nothing: the route is not there (404).
        showing("Soups")
        add("New folder", "Stocks")
        showing("Your ship couldn't take it")
    }

    @Test
    fun `leaving the notebook while a folder is made still finishes it`() = channel { ship, repo ->
        ship.answerApi = { method, path, _ ->
            if (method == "POST" && path.endsWith("/folders")) {
                Thread.sleep(400) // a busy host
                ship.scries["notes/v0/folders/~bus/recipes"] = ship.scries.getValue("notes/v0/folders/~bus/recipes").trimEnd().removeSuffix("]") + ",$stocks]"
                """{"body":{"type":"ok","response":{}}}"""
            } else null
        }
        showing("Soups")
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        screen.launch { repo.notes.createFolder(io.nisfeb.talon.urbit.NotesFlag("~bus", "recipes"), 8, "Stocks") }
        Thread.sleep(100)
        screen.cancel()
        // Read again once the host took it, though nobody was waiting.
        showing("Stocks")
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

    // Read as nothing published, a public note offered to be published
    // again and never to be taken down.
    @Test
    fun `where the host cannot say what is published, neither is offered`() = note(published = null) { _, _ ->
        showing("rev 3")
        assertTrue(!shows("Publish to web") && onAllNodesWithContentDescription("Publish to web").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithContentDescription("Unpublish").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a published note offers to be taken down`() = note(published = """[{"host":"~bus","flagName":"recipes","noteId":11}]""") { _, _ ->
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Unpublish").fetchSemanticsNodes().isNotEmpty() }
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

    // ─── folders ──────────────────────────────────────────────────

    /** [action] from the Soups folder's own menu: the actions button level with its name. */
    private fun ComposeUiTest.folderAction(action: String) {
        showing("Soups")
        val y = onNodeWithText("Soups").fetchSemanticsNode().boundsInRoot.center.y
        val menus = onAllNodesWithContentDescription("Folder actions")
        menus[menus.fetchSemanticsNodes().indices.minBy { kotlin.math.abs(menus[it].fetchSemanticsNode().boundsInRoot.center.y - y) }].performClick()
        onNodeWithText(action).performClick()
    }

    private fun ComposeUiTest.notesPoke(ship: FakeShip, containing: String): String {
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("notes").any { containing in it.json.toString() } }
        return ship.pokesTo("notes").last { containing in it.json.toString() }.json.toString()
    }

    @Test
    fun `a folder is renamed from its menu`() = channel { ship, _ ->
        folderAction("Rename")
        onNode(hasSetTextAction()).performTextReplacement("Broths")
        onNodeWithText("Rename").performClick()
        assertTrue("Broths" in notesPoke(ship, "Broths"))
    }

    @Test
    fun `a folder moves only to somewhere that keeps the tree whole`() = channel(
        moreFolders = """,{"name":"Mains","notebookId":7,"id":10,"createdBy":"~bus","createdAt":1,"parentFolderId":8,"updatedAt":1,"updatedBy":"~bus"}""",
    ) { ship, _ ->
        showing("Mains")
        folderAction("Move…")
        showing("Move \"Soups\" to…")
        onAllNodesWithText("Mains", substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        val moved = notesPoke(ship, "\"move\"")
        assertTrue("\"newParent\":10" in moved, moved)
    }

    @Test
    fun `a folder with nowhere else to go says so`() = channel { ship, _ ->
        folderAction("Move…")
        showing("There's nowhere else to put this folder.")
        onNodeWithText("Cancel").performClick()
        assertTrue(ship.pokesTo("notes").isEmpty())
    }

    @Test
    fun `deleting a folder says what goes with it, then takes it all`() = channel { ship, _ ->
        folderAction("Delete")
        showing("This also deletes 1 item inside it, for everyone in the notebook.")
        onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        assertTrue("\"recursive\":true" in notesPoke(ship, "recursive"))
    }
}
