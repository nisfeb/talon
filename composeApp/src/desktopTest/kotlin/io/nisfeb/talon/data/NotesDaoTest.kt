package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NotesDao.replaceTree] swaps in a fresh host snapshot of a notebook.
 * The risk it guards is data loss: a background scry lands while the
 * user is mid-save, and a naive replace reverts the body they just
 * typed. Rows still marked pending must survive the swap.
 */
class NotesDaoTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private val flag = "~ricsul-bilwyt/handbook"

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-notes-dao-test-").toFile()
        db = Room.databaseBuilder<AppDatabase>(name = File(tmpDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @AfterTest
    fun tearDown() {
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    private fun note(id: Long, body: String, pending: Boolean = false) = NotesNoteEntity(
        flag = flag,
        noteId = id,
        folderId = 1,
        title = "n$id",
        slug = null,
        bodyMd = body,
        createdBy = "~ricsul-bilwyt",
        createdAtMs = 1_000L,
        updatedBy = "~ricsul-bilwyt",
        updatedAtMs = 1_000L,
        revision = 1,
        pending = pending,
    )

    private fun folder(id: Long, parent: Long?) = NotesFolderEntity(
        flag = flag,
        folderId = id,
        name = if (parent == null) "/" else "f$id",
        parentFolderId = parent,
        createdBy = "~ricsul-bilwyt",
        createdAtMs = 1_000L,
        updatedBy = "~ricsul-bilwyt",
        updatedAtMs = 1_000L,
    )

    @Test
    fun `replaceTree keeps a note pending across a host snapshot`() = runBlocking {
        val dao = db.notes()
        dao.upsertNotes(listOf(note(1, "original")))
        // User edits: body swapped locally, row marked in flight.
        dao.applyLocalEdit(flag, 1, "user's unsaved edit", 2_000L)
        assertTrue(dao.note(flag, 1)!!.pending)

        // A scry races in still carrying the host's older body.
        dao.replaceTree(flag, listOf(folder(1, null)), listOf(note(1, "original")))

        // The row must stay flagged so the UI keeps showing "saving…"
        // instead of silently reverting to the host's copy.
        assertTrue(
            dao.note(flag, 1)!!.pending,
            "an in-flight edit must survive a racing snapshot",
        )
    }

    @Test
    fun `replaceTree drops rows the host no longer has`() = runBlocking {
        val dao = db.notes()
        dao.upsertNotes(listOf(note(1, "a"), note(2, "b")))
        dao.upsertFolders(listOf(folder(1, null), folder(2, 1)))

        // Host snapshot without note 2 / folder 2 — a plain upsert would
        // leave the deleted rows behind forever.
        dao.replaceTree(flag, listOf(folder(1, null)), listOf(note(1, "a")))

        assertEquals(null, dao.note(flag, 2), "a note the host dropped must not linger")
        assertEquals("a", dao.note(flag, 1)?.bodyMd, "surviving rows keep their content")
    }

    @Test
    fun `a settled save stops reading as saving`() = runBlocking {
        // The bug: pending was set when a save started and only cleared
        // on failure, while replaceTree re-applies it to in-flight rows.
        // So one successful save pinned the note as "saving…" forever,
        // and every later refresh restored the mark.
        val dao = db.notes()
        dao.upsertNotes(listOf(note(1, "original")))
        dao.applyLocalEdit(flag, 1, "edited", 2_000L)
        assertTrue(dao.note(flag, 1)!!.pending)

        // Save settles: clear the mark, then take the host's snapshot.
        dao.setPending(flag, 1, false)
        dao.replaceTree(flag, emptyList(), listOf(note(1, "edited").copy(revision = 2)))

        val row = dao.note(flag, 1)!!
        assertFalse(row.pending, "a settled save must not keep reading as saving")
        assertEquals(2L, row.revision, "the host's new revision must land for the next edit")

        // And it stays cleared across further refreshes.
        dao.replaceTree(flag, emptyList(), listOf(note(1, "edited").copy(revision = 2)))
        assertFalse(dao.note(flag, 1)!!.pending)
    }

    @Test
    fun `clearing pending lets the next snapshot win`() = runBlocking {
        val dao = db.notes()
        dao.upsertNotes(listOf(note(1, "original")))
        dao.applyLocalEdit(flag, 1, "rejected edit", 2_000L)
        // Save was rejected, so the optimistic state is abandoned.
        dao.setPending(flag, 1, false)

        dao.replaceTree(flag, emptyList(), listOf(note(1, "host version")))

        val row = dao.note(flag, 1)!!
        assertFalse(row.pending)
        assertEquals("host version", row.bodyMd, "host must win once the edit is abandoned")
    }
}
