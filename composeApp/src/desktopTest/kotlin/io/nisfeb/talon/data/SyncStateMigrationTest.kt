package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 57 adds the settings outbox and the marks. The step from 56 adds the two
 * tables and keeps the rest: with no step, the fallback would have dropped
 * every table, messages and all. Room checks the tables it opens against
 * the entities, so a statement that drifts from them fails the open here.
 */
class SyncStateMigrationTest {
    @Test
    fun `an upgrade adds the outbox and the marks and keeps the messages`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-sync-state-").toFile()
        try {
            val path = File(dir, "talon.db").absolutePath
            // No destructive fallback: an open that succeeds ran the step.
            fun open() = Room.databaseBuilder<AppDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*SHARED_MIGRATIONS)
                .build()
            open().apply {
                messages().upsert(MessageEntity("chat/~bus/general", "170141184506", "~bus", 1_000, """[{"inline":["kept"]}]""", "/chat"))
                close()
            }
            // As 1.8.30-rc10 left it: version 56, neither table.
            BundledSQLiteDriver().open(path).apply {
                execSQL("DROP TABLE settings_outbox")
                execSQL("DROP TABLE marks")
                execSQL("PRAGMA user_version = 56")
                close()
            }
            open().apply {
                assertEquals("~bus", messages().getOne("chat/~bus/general", "170141184506")?.author, "the messages are kept")
                assertEquals(emptyList(), settingsOutbox().all())
                assertNull(marks().get(MarkDao.POSTS_READ))
                close()
            }
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }

    // A newer write to a slot replaces the one waiting there and goes after
    // everything kept before it: what the outbox's order rests on.
    @Test
    fun `a slot keeps its newest write, which goes last`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-outbox-").toFile()
        try {
            val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
                .setDriver(BundledSQLiteDriver()).build()
            val out = db.settingsOutbox()
            val first = out.keep(SettingsOutboxEntity(slot = "folders/1", payload = "a"))
            out.keep(SettingsOutboxEntity(slot = "notify-prefs/~bus", payload = "b"))
            val again = out.keep(SettingsOutboxEntity(slot = "folders/1", payload = "c"))
            assertEquals(listOf("notify-prefs/~bus" to "b", "folders/1" to "c"), out.all().map { it.slot to it.payload })
            assertFalse(out.has(first), "replaced")
            assertTrue(out.has(again))
            out.done(again)
            assertEquals(listOf("notify-prefs/~bus"), out.all().map { it.slot })
            db.marks().put(MarkEntity(MarkDao.POSTS_READ, 5))
            db.marks().put(MarkEntity(MarkDao.POSTS_READ, 7))
            assertEquals(7, db.marks().get(MarkDao.POSTS_READ))
            db.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
