package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Orrery's last answer is kept from 55 on. The step from 54 adds the one
 * table and keeps the rest: with no step, the fallback would have dropped
 * every table, messages and all.
 */
class OrreryCacheMigrationTest {
    @Test
    fun `an upgrade adds the orrery cache and keeps the messages`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-orrery-cache-").toFile()
        try {
            val path = File(dir, "talon.db").absolutePath
            // No destructive fallback: an open that succeeds ran the step.
            fun open() = Room.databaseBuilder<AppDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(ORRERY_CACHE_MIGRATION)
                .build()
            open().apply {
                messages().upsert(MessageEntity("chat/~bus/general", "170141184506", "~bus", 1_000, """[{"inline":["kept"]}]""", "/chat"))
                close()
            }
            // As 1.8.1-rc35 left it: version 54, no table.
            BundledSQLiteDriver().open(path).apply {
                execSQL("DROP TABLE orrery_cache")
                execSQL("PRAGMA user_version = 54")
                close()
            }
            open().apply {
                assertEquals("~bus", messages().getOne("chat/~bus/general", "170141184506")?.author, "the messages are kept")
                assertNull(orreryCache().get("state"))
                orreryCache().put(OrreryCacheEntity("state", """{"bodies":[]}""", 5))
                assertEquals(OrreryCacheEntity("state", """{"bodies":[]}""", 5), orreryCache().get("state"))
                close()
            }
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }
}
