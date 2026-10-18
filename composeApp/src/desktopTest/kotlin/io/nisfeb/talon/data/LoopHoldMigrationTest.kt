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

/**
 * 58 adds a loop's ask-before-sharing switch and the calls it holds. The
 * step from 57 adds the column, off, and the table, and keeps every loop:
 * with no step, the fallback would have dropped every table. Room checks
 * the tables it opens against the entities, so a statement that drifts
 * from them fails the open here.
 */
class LoopHoldMigrationTest {
    @Test
    fun `an upgrade adds the switch off and the held calls and keeps the loops`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-loop-hold-").toFile()
        try {
            val path = File(dir, "talon.db").absolutePath
            // No destructive fallback: an open that succeeds ran the step.
            fun open() = Room.databaseBuilder<AppDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*SHARED_MIGRATIONS)
                .build()
            open().apply {
                loops().upsert(LoopEntity(gid = "g1", name = "Shares", prompt = "share it", intervalMinutes = 60, writesAuthorized = true, askBeforeSharing = true, createdAt = 1, updatedAt = 1))
                close()
            }
            // As 1.8.30-rc11 left it: version 57, no column, no table.
            BundledSQLiteDriver().open(path).apply {
                execSQL("ALTER TABLE loop DROP COLUMN askBeforeSharing")
                execSQL("DROP TABLE loop_hold")
                execSQL("PRAGMA user_version = 57")
                close()
            }
            open().apply {
                val loop = loops().getByGid("g1")!!
                assertEquals("Shares" to true, loop.name to loop.writesAuthorized, "the loop is kept")
                assertEquals(false, loop.askBeforeSharing, "off, as every loop was before")
                val id = loopHolds().insert(LoopHoldEntity(loopId = loop.id, tool = "share_calendar", args = "{}", what = "Shares wants to share", heldAt = 10))
                assertEquals(listOf("Shares wants to share"), loopHolds().stream(since = 9).first().map { it.what })
                assertEquals(1, loopHolds().delete(id))
                assertEquals(0, loopHolds().delete(id), "a second tap takes nothing")
                close()
            }
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }
}
