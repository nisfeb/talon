package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A ship's database closes only once its work has stopped: closed under
 * a running query, the bundled SQLite driver crashes the process. Work
 * that will not stop leaves the database open instead.
 */
class CloseAfterWorkTest {
    private fun db(): AppDatabase = createTempDirectory(prefix = "talon-close-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    @Test
    fun `busy work is stopped and waited for, then the database closes`() = runBlocking<Unit> {
        val db = db()
        val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var queries = 0
        repeat(4) {
            work.launch {
                while (isActive) {
                    db.contacts().upsert(ContactEntity("~bus", "Bus $queries", null, null))
                    db.contacts().get("~bus")
                    queries++
                }
            }
        }
        while (queries < 50) kotlinx.coroutines.delay(5)
        assertTrue(closeAfterWork(db) { work.coroutineContext.job.cancelAndJoin() })
        assertFailsWith<Exception>("closed") { db.contacts().get("~bus") }
    }

    @Test
    fun `work that will not stop leaves the database open`() = runBlocking {
        val db = db()
        try {
            assertFalse(closeAfterWork(db, waitMs = 200) { awaitCancellation() })
            db.contacts().upsert(ContactEntity("~bus", "Bus", null, null))
            assertEquals("Bus", db.contacts().get("~bus")?.nickname, "still open, still usable")
        } finally {
            db.close()
        }
    }
}
