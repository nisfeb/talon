package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 50 to 51 adds an index on messages.status, which the queued count reads
 * on every write to messages. The fallback for a migration that does not
 * match is to drop every table, so the one thing this must not do is
 * fail to match and lose the history.
 */
class MessageStatusIndexTest {
    @Test
    fun `a database from 50 keeps its messages and gains the index`() = runBlocking<Unit> {
        val dir = createTempDirectory(prefix = "talon-status-index-").toFile()
        val path = File(dir, "talon.db").absolutePath
        // No destructive fallback: a migration that does not match the entity fails here.
        fun db() = Room.databaseBuilder<AppDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MESSAGE_STATUS_INDEX_MIGRATION, io.nisfeb.talon.data.WATCHWORDS_DROP_MIGRATION)
            .build()
        try {
            db().also { it.messages().upsert(MessageEntity("~bus", "~bus/1", "~bus", 1_000, "[]", "/chat", status = "queued")); it.close() }
            BundledSQLiteDriver().open(path).also { c ->
                c.execSQL("DROP INDEX index_messages_status")
                c.execSQL("PRAGMA user_version = 50")
                c.close()
            }
            val db = db()
            try {
                assertEquals("~bus/1", db.messages().getOne("~bus", "~bus/1")?.id, "kept")
                assertEquals(1, db.messages().queuedCount().first())
            } finally {
                db.close()
            }
            BundledSQLiteDriver().open(path).also { c ->
                val has = c.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_messages_status'").use { it.step() }
                val plan = c.prepare("EXPLAIN QUERY PLAN SELECT COUNT(*) FROM messages WHERE status = 'queued'").use { s ->
                    buildString { while (s.step()) append(s.getText(3)).append('\n') }
                }
                c.close()
                assertTrue(has, "the index is there")
                assertTrue("index_messages_status" in plan, "and the queued count reads it: $plan")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
