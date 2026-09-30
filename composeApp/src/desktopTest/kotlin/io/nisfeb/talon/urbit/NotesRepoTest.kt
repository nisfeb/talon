package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Notebooks (%notes) against a [FakeShip]: the list and each tree read
 * and watched, re-read only when changed or told to, saves that carry
 * their revision, and joins, deletes, publishing and group notebooks,
 * each checked by what reaches the ship and what is kept here.
 */
class NotesRepoTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-notes-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val notes = NotesRepo(db, CoroutineScope(SupervisorJob())).apply { attach(ship.channel) }
    private val book = NotesFlag("~bus", "recipes")

    private fun summary(updatedAt: Long = 1784592399) =
        """{"flagName":"recipes","host":"~bus","notebook":{"title":"Recipes","id":7,"rootFolderId":8,"createdBy":"~bus",
           "createdAt":1784592399,"updatedAt":$updatedAt,"updatedBy":"~bus"},"visibility":"private"}"""

    private fun note(id: Long, title: String, body: String, folder: Long = 9) =
        """{"folderId":$folder,"notebookId":7,"title":"$title","revision":3,"id":$id,"createdBy":"~bus","createdAt":1784592455,
           "bodyMd":"$body","updatedAt":1784592505,"updatedBy":"~bus","slug":null}"""

    init {
        ship.scries["notes/v0/notebooks"] = "[${summary()}]"
        ship.scries["notes/v0/folders/~bus/recipes"] = """[
            {"name":"/","notebookId":7,"id":8,"createdBy":"~bus","createdAt":1784592399,"parentFolderId":null,"updatedAt":1784592399,"updatedBy":"~bus"},
            {"name":"Soups","notebookId":7,"id":9,"createdBy":"~bus","createdAt":1784592399,"parentFolderId":8,"updatedAt":1784592399,"updatedBy":"~bus"}]"""
        ship.scries["notes/v0/notes/~bus/recipes"] = "[${note(11, "Pho", "Simmer **long**.")}]"
    }

    @AfterTest
    fun close() = db.close()

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private suspend fun titles() = db.notes().streamNotes(book.flagString).first().map { it.title }
    private fun reads() = ship.scried.count { it == "notes/v0/notes/~bus/recipes" }

    @Test
    fun `bootstrap lists the notebooks, reads each tree and watches it`() = live {
        notes.bootstrap()
        assertEquals("Recipes", db.notes().notebook(book.flagString)?.title)
        assertEquals(listOf("/", "Soups"), db.notes().streamFolders(book.flagString).first().map { it.name }.sorted())
        assertEquals(listOf("Pho"), titles())
        assertTrue("notes/v0/notes/~bus/recipes/stream" in ship.subscribed)
    }

    @Test
    fun `a notebook is read again only when it changed`() = live {
        notes.bootstrap()
        notes.bootstrap()
        assertEquals(1, reads(), "unchanged: not read again")
        ship.scries["notes/v0/notebooks"] = "[${summary(updatedAt = 1784599999)}]"
        notes.bootstrap()
        assertEquals(2, reads())
    }

    // An answer that is not the lists replaced every note kept here with
    // nothing, as if the notebook had been emptied.
    @Test
    fun `an answer in a shape this build does not read keeps the notebook`() = live {
        notes.bootstrap()
        ship.scries["notes/v0/notes/~bus/recipes"] = """{"notes":"moved"}"""
        notes.applyNotesEvent(Json.parseToJsonElement("""{"type":"note-created","host":"~bus","flagName":"recipes"}""").jsonObject)
        assertEquals(listOf("Pho"), titles())
    }

    @Test
    fun `a stream fact reads its notebook again, and a deleted notebook goes`() = live {
        notes.bootstrap()
        ship.scries["notes/v0/notes/~bus/recipes"] = "[${note(11, "Pho", "x")},${note(12, "Ramen", "y")}]"
        notes.applyNotesEvent(Json.parseToJsonElement("""{"type":"note-created","host":"~bus","flagName":"recipes"}""").jsonObject)
        assertEquals(listOf("Pho", "Ramen"), titles().sorted())
        notes.applyNotesEvent(Json.parseToJsonElement("""{"type":"notebook-deleted","host":"~bus","flagName":"recipes"}""").jsonObject)
        assertNull(db.notes().notebook(book.flagString))
        assertTrue(titles().isEmpty())
    }

    @Test
    fun `a save carries the revision it was made from, and a refused one says so`() = live {
        notes.bootstrap()
        ship.answerApi = { method, path, _ ->
            if (method == "PUT" && path == "/notes/~/v1/notebooks/~bus/recipes/notes/11") """{"body":{"type":"ok"}}""" else null
        }
        assertTrue(notes.updateNote(book, 11, "Simmer all day.", expectedRevision = 3))
        val sent = ship.api.single()
        assertTrue("\"expectedRevision\":3" in sent && "Simmer all day." in sent, sent)
        assertEquals(false, db.notes().note(book.flagString, 11)?.pending, "no longer marked in flight")

        ship.answerApi = { _, _, _ -> """{"body":{"type":"error","errorType":"revision-mismatch"}}""" }
        assertFalse(notes.updateNote(book, 11, "mine", expectedRevision = 2), "someone else saved first")
        ship.answerApi = { _, _, _ -> null }
        assertFalse(notes.updateNote(book, 11, "mine", expectedRevision = 3), "the ship could not be reached")
        assertEquals("Simmer **long**.", db.notes().note(book.flagString, 11)?.bodyMd, "the ship's copy stands")
    }

    @Test
    fun `opening a notebook not yet joined joins, watches and reads it`() = live {
        notes.ensureJoined(book)
        val join = ship.pokesTo("notes").single().json.toString()
        assertTrue("\"type\":\"join\"" in join && "\"ship\":\"~bus\"" in join && "\"name\":\"recipes\"" in join, join)
        assertTrue("notes/v0/notes/~bus/recipes/stream" in ship.subscribed)
        assertEquals(listOf("Pho"), titles())
    }

    @Test
    fun `a refused join watches nothing`() = live {
        ship.refuse = { if (it.app == "notes") "no" else null }
        assertFalse(notes.joinNotebook(book))
        assertTrue(ship.subscribed.none { it.startsWith("notes") })
    }

    @Test
    fun `a note or folder deleted goes here once the ship agrees, and stays if not`() = live {
        notes.bootstrap()
        ship.refuse = { if (it.app == "notes") "no" else null }
        assertFalse(notes.deleteNote(book, 11))
        assertNotNull(db.notes().note(book.flagString, 11))
        ship.refuse = { null }
        assertTrue(notes.deleteNote(book, 11))
        assertNull(db.notes().note(book.flagString, 11))
        assertTrue(notes.deleteFolder(book, 9))
        assertTrue("\"recursive\":true" in ship.pokesTo("notes").last().json.toString())
    }

    @Test
    fun `publishing sends the note as HTML and gives its public address`() = live {
        notes.bootstrap()
        assertEquals("/notes/pub/~bus/recipes/11", notes.publishNote(book, 11))
        assertTrue("<strong>long</strong>" in ship.pokesTo("notes").single().json.toString())
        assertNull(notes.publishNote(book, 99), "no such note, nothing sent")
    }

    @Test
    fun `a notebook made in a group comes back with the name the host gave it`() = live {
        ship.answerApi = { method, path, _ ->
            if (method == "POST" && path == "/notes/~/v1/notebooks") {
                """{"body":{"type":"notebook","notebook":${summary().replace("recipes", "team-notes-3")}}}"""
            } else null
        }
        assertEquals("notes/~bus/team-notes-3", notes.createGroupNotebook("~bus/garden", "Team notes"))
        val asked = ship.api.single()
        assertTrue("\"host\":\"~bus\"" in asked && "\"flagName\":\"garden\"" in asked, asked)
        assertNull(notes.createGroupNotebook("not a flag", "x"))
    }

    @Test
    fun `leaving takes the notebook and its notes`() = live {
        notes.bootstrap()
        assertTrue(notes.leaveNotebook(book))
        assertNull(db.notes().notebook(book.flagString))
        assertTrue(titles().isEmpty())
    }

    @Test
    fun `without the notes app, bootstrap leaves things as they are`() = live {
        ship.scries.remove("notes/v0/notebooks")
        notes.bootstrap()
        assertTrue(db.notes().allNotebooks().isEmpty())
    }
}
