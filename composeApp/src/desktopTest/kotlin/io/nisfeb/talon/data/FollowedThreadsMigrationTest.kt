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
 * Followed threads are kept from 54 on. The step from 53 adds the one
 * table and keeps the rest: with no step, the fallback would have
 * dropped every table, messages and all.
 */
class FollowedThreadsMigrationTest {
    @Test
    fun `an upgrade adds the followed threads and keeps the messages`() = runBlocking {
        val dir = createTempDirectory(prefix = "talon-followed-").toFile()
        try {
            val path = File(dir, "talon.db").absolutePath
            // No destructive fallback: an open that succeeds ran the step.
            fun open() = Room.databaseBuilder<AppDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(FOLLOWED_THREADS_MIGRATION)
                .build()
            open().apply {
                messages().upsert(MessageEntity("chat/~bus/general", "170141184506", "~bus", 1_000, """[{"inline":["kept"]}]""", "/chat"))
                close()
            }
            // As 1.8.1-rc24 left it: version 53, no table.
            BundledSQLiteDriver().open(path).apply {
                execSQL("DROP TABLE followed_threads")
                execSQL("PRAGMA user_version = 53")
                close()
            }
            open().apply {
                assertEquals("~bus", messages().getOne("chat/~bus/general", "170141184506")?.author, "the messages are kept")
                assertNull(followedThreads().get("chat/~bus/general", "170141184506"))
                val row = FollowedThreadEntity("chat/~bus/general", "170141184506", follow = true, sent = false, atMs = 5)
                followedThreads().upsert(row)
                assertEquals(row, followedThreads().get("chat/~bus/general", "170141184506"))
                assertEquals(listOf(row), followedThreads().unsent())
                close()
            }
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }
}
