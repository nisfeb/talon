package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A turn keeps its run's log from 53 on. The step from 52 adds the one
 * column and keeps every turn: with no step, the fallback would have
 * dropped every table, conversations and all.
 */
class AssistantLogMigrationTest {
    @Test
    fun `an upgrade adds the log column and keeps the turns`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-assistant-log-").toFile()
        try {
            val path = File(dir, "talon.db").absolutePath
            // No destructive fallback: an open that succeeds ran the step.
            fun open() = Room.databaseBuilder<AppDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*SHARED_MIGRATIONS)
                .build()
            open().apply {
                assistantHistory().insert(AssistantHistoryEntity(gid = "t1", mode = "Assistant", question = "file this", answer = "Filed.", createdAt = 1_000, log = "✓ orrery_observe 2100ms, result 40 chars"))
                close()
            }
            // As a 1.8.1 install left it: version 52, the table without the column.
            BundledSQLiteDriver().open(path).apply {
                execSQL("ALTER TABLE assistant_history DROP COLUMN log")
                execSQL("PRAGMA user_version = 52")
                close()
            }
            open().apply {
                val turn = assistantHistory().getByGid("t1")!!
                assertEquals("file this", turn.question)
                assertEquals("", turn.log, "a turn from before has an empty log, not a dropped row")
                assistantHistory().insert(AssistantHistoryEntity(gid = "t2", mode = "Assistant", question = "q", answer = "a", createdAt = 2_000, log = "answer 1 chars"))
                assertEquals("answer 1 chars", assistantHistory().getByGid("t2")!!.log)
                close()
            }
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }
}
