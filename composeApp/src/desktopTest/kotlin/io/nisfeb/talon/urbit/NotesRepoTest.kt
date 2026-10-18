package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val notes = NotesRepo(db, CoroutineScope(SupervisorJob()), deleteSettleMs = 200).apply { attach(ship.channel) }
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
        assertNull(notes.updateNote(book, 11, "Simmer all day.", expectedRevision = 3))
        val sent = ship.api.single { !it.startsWith("GET ") }
        assertTrue("\"expectedRevision\":3" in sent && "Simmer all day." in sent, sent)
        assertEquals(false, db.notes().note(book.flagString, 11)?.pending, "no longer marked in flight")

        ship.answerApi = { _, _, _ -> """{"body":{"type":"error","errorType":"revision-mismatch"}}""" }
        assertEquals(NotesRepo.NOTE_CHANGED, notes.updateNote(book, 11, "mine", expectedRevision = 2), "someone else saved first")
        ship.answerApi = { _, _, _ -> null }
        assertTrue(notes.updateNote(book, 11, "mine", expectedRevision = 3) != NotesRepo.NOTE_CHANGED, "a route the ship lacks is not a conflict")
        assertEquals("Simmer **long**.", db.notes().note(book.flagString, 11)?.bodyMd, "the ship's copy stands")
    }

    // A save marks the row pending, then clears it after the PUT settles. Run
    // on the caller's scope, leaving the note screen between the two cancelled
    // the clear, and replaceTree re-applied pending on every later scry,
    // pinning the note as "saving…" forever. On the repo scope the clear runs
    // regardless. Real time via a slow answerApi.
    @Test
    fun `a save left mid-flight does not pin the note as saving`() = live {
        notes.bootstrap()
        ship.answerApi = { method, _, _ ->
            if (method == "PUT") { Thread.sleep(600); """{"body":{"type":"ok"}}""" } else null
        }
        val screen = CoroutineScope(SupervisorJob())
        screen.launch { notes.updateNote(book, 11, "new body", expectedRevision = 3) }
        delay(200)
        assertEquals(true, db.notes().note(book.flagString, 11)?.pending, "marked saving while in flight")
        screen.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        val until = System.currentTimeMillis() + 5_000
        while (db.notes().note(book.flagString, 11)?.pending == true && System.currentTimeMillis() < until) delay(50)
        assertEquals(false, db.notes().note(book.flagString, 11)?.pending, "the clear ran on the repo scope despite leaving")
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
        val asked = ship.api.single { !it.startsWith("GET ") }
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

    // The scries first: a read, where an HTTP request is an event on the
    // ship, written to its log. %notes' HTTP reads (the same JSON, bare)
    // only where a scry is not served.
    @Test
    fun `a ship that serves the scries is asked nothing over HTTP`() = live {
        notes.bootstrap()
        assertEquals(listOf("Pho"), titles())
        assertTrue(ship.api.none { it.startsWith("GET ") }, "${ship.api}")
    }

    @Test
    fun `notebooks are read over HTTP where the scries are not served`() = live {
        val answers = mapOf(
            "/notes/~/v1/notebooks" to ship.scries.remove("notes/v0/notebooks"),
            "/notes/~/v1/notebooks/~bus/recipes/folders" to ship.scries.remove("notes/v0/folders/~bus/recipes"),
            "/notes/~/v1/notebooks/~bus/recipes/notes" to ship.scries.remove("notes/v0/notes/~bus/recipes"),
        )
        ship.answerApi = { method, path, _ -> if (method == "GET") answers[path] else null }
        notes.bootstrap()
        assertEquals(listOf("Pho"), titles())
    }

    @Test
    fun `a ship that does not answer is not asked again over HTTP`() = live {
        ship.loseScry = { kotlinx.io.IOException("The network connection was lost.") }
        notes.bootstrap()
        assertTrue(ship.api.none { it.startsWith("GET ") }, "silence says nothing of the path: ${ship.api}")
    }

    @Test
    fun `every notebook's stream is watched in one request`() = live {
        ship.scries["notes/v0/notebooks"] = "[${summary()},${summary().replace("recipes", "drinks").replace("Recipes", "Drinks")}]"
        notes.bootstrap()
        assertTrue("notes/v0/notes/~bus/recipes/stream" in ship.subscribed && "notes/v0/notes/~bus/drinks/stream" in ship.subscribed)
        assertTrue(2 in ship.subscribePuts, "${ship.subscribePuts}")
    }

    // Every failed save was called a conflict, and with the ship down the
    // unsaved body stood in the notebook as the saved one: the edit is put
    // in before the save, and the read after it failed too.
    @Test
    fun `a save with the ship down says so and leaves the host's copy, not the unsaved body`() = live {
        notes.bootstrap()
        ship.answerApi = { _, _, _ -> throw java.io.IOException("Connection refused") }
        ship.loseScry = { kotlinx.io.IOException("The network connection was lost.") }
        val said = notes.updateNote(book, 11, "not saved", expectedRevision = 3)
        assertEquals("Your ship is slow, so it didn't go through. Try again when it's back.", said)
        val row = db.notes().note(book.flagString, 11)
        assertEquals("Simmer **long**.", row?.bodyMd, "the host's copy, not the edit")
        assertEquals(false, row?.pending)
    }

    // A proxy's 502 for a ship that is down read "Your ship couldn't take
    // it (PUT /notes/~/v1/...: HTTP 502)": the internal path, and a refusal.
    @Test
    fun `a save or a new note behind a proxy's 502 says the ship is slow and not the path`() = live {
        notes.bootstrap()
        val proxy = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            respond("<html><body>502 Bad Gateway</body></html>", io.ktor.http.HttpStatusCode.BadGateway)
        })
        val down = NotesRepo(db, CoroutineScope(SupervisorJob()))
            .apply { attach(UrbitSession(proxy, ship.session).apply { tryRestore("~zod") }.openChannel()) }
        val slow = "Your ship is slow, so it didn't go through. Try again when it's back."
        assertEquals(slow, down.updateNote(book, 11, "not saved", expectedRevision = 3))
        assertEquals(slow, down.createNote(book, 9, "Laksa", ""))
        assertEquals("Simmer **long**.", db.notes().note(book.flagString, 11)?.bodyMd, "the host's copy, not the edit")
    }

    @Test
    fun `a save each host refuses is said as what it is`() = live {
        notes.bootstrap()
        ship.answerApi = { _, _, _ -> """{"body":{"type":"error","errorType":"not-authorized"}}""" }
        assertEquals("You can't edit this notebook. Its owner can make you an editor.", notes.updateNote(book, 11, "mine", expectedRevision = 3))
        ship.answerApi = { _, _, _ -> """{"body":{"type":"pending"}}""" }
        assertTrue(notes.updateNote(book, 11, "mine", expectedRevision = 3)!!.startsWith("Sent."), "a cross-ship save in flight")
        ship.answerApi = { _, _, _ -> """{"body":{"errorType":"unknown","message":[],"type":"error"}}""" }
        assertEquals(NotesRepo.NOTE_CHANGED, notes.updateNote(book, 11, "mine", expectedRevision = 2), "the live ship's word for a stale revision")
        assertEquals("Simmer **long**.", db.notes().note(book.flagString, 11)?.bodyMd)
    }

    // A reader's delete in someone else's notebook is answered before its
    // host has, and a host that refuses it says nothing: the note stayed
    // hidden here until the notebook next changed.
    @Test
    fun `a note deleted here comes back if its host kept it, and stays gone if not`() = live {
        notes.bootstrap()
        assertTrue(notes.deleteNote(book, 11))
        assertNull(db.notes().note(book.flagString, 11), "gone at once")
        // The host kept it: its notebook still has it.
        kotlinx.coroutines.withTimeout(5_000) { while (db.notes().note(book.flagString, 11) == null) delay(50) }
        ship.scries["notes/v0/notes/~bus/recipes"] = "[]"
        assertTrue(notes.deleteNote(book, 11))
        delay(600)
        assertNull(db.notes().note(book.flagString, 11), "the host took it: it stays gone")
    }

    // Read again three seconds on, a busy host's notebook still had the
    // note, and it came back before the host took it away: a delete that
    // looked refused. Hidden until the settle is over, unless the host has
    // let it go before then.
    @Test
    fun `a note deleted in a slow host's notebook stays hidden through a read before the settle`() = live {
        val slow = NotesRepo(db, CoroutineScope(SupervisorJob()), deleteSettleMs = 1_500).apply { attach(ship.channel) }
        slow.bootstrap()
        assertTrue(slow.deleteNote(book, 11))
        // Another change to the notebook is read while the host still has it.
        slow.refreshNotebook(book)
        assertNull(db.notes().note(book.flagString, 11), "still hidden: the host has not answered")
        // The host kept it: back once the settle is over.
        kotlinx.coroutines.withTimeout(5_000) { while (db.notes().note(book.flagString, 11) == null) delay(50) }
        // Taken this time: a read without it ends the hiding, so a note of
        // that id the host makes later is not hidden.
        assertTrue(slow.deleteNote(book, 11))
        ship.scries["notes/v0/notes/~bus/recipes"] = "[]"
        slow.refreshNotebook(book)
        ship.scries["notes/v0/notes/~bus/recipes"] = "[${note(11, "Pho", "Again.")}]"
        slow.refreshNotebook(book)
        assertNotNull(db.notes().note(book.flagString, 11), "the host's own again")
    }

    // Made from a group's page, on that page's scope: leaving while the
    // host answered lost the notebook it made from the cache and the stream.
    @Test
    fun `a group notebook made from a page that is left still finishes`() = live {
        ship.answerApi = { method, path, _ ->
            if (method == "POST" && path == "/notes/~/v1/notebooks") {
                Thread.sleep(500)
                """{"body":{"type":"notebook","notebook":${summary()}}}"""
            } else null
        }
        val page = CoroutineScope(SupervisorJob())
        page.launch { notes.createGroupNotebook("~bus/garden", "Recipes") }
        delay(150)
        page.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        kotlinx.coroutines.withTimeout(5_000) { while (db.notes().notebook(book.flagString) == null) delay(50) }
        assertEquals(listOf("Pho"), titles(), "read in, as when the page stays")
    }
}
