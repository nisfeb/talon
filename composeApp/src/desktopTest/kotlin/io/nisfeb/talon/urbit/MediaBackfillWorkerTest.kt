package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Correctness + idempotency tests for [MediaBackfillWorker.runIfNeeded].
 *
 * Uses a real (on-disk temp) Room database — same pattern as
 * EnsureChannelGroupRowTest, SettingsSyncFuzzTest, etc. Messages are
 * seeded via the bare [MessageDao.upsert] which does NOT touch
 * message_media, so the backfill has fresh work to do on first call.
 */
class MediaBackfillWorkerTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-backfill-test-").toFile()
        val dbFile = File(tmpDir, "test.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @AfterTest
    fun tearDown() {
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    @Test
    fun `empty messages table yields no media rows`() = runBlocking {
        MediaBackfillWorker.runIfNeeded(db)
        assertEquals(0, db.messageMedia().totalCount())
    }

    @Test
    fun `seeded messages get media rows`() = runBlocking {
        val m1 = MessageEntity(
            whom = "~zod",
            id = "~zod/1000",
            author = "~zod",
            sentMs = 100L,
            contentJson = """[{"inline":[{"link":{"href":"https://x.com/a.jpg","content":"img"}}]}]""",
            kind = "/chat",
        )
        val m2 = MessageEntity(
            whom = "~zod",
            id = "~zod/2000",
            author = "~zod",
            sentMs = 200L,
            contentJson = """[{"inline":[{"link":{"href":"https://x.com/r.pdf","content":"doc"}}]}]""",
            kind = "/chat",
        )
        db.messages().upsert(m1)
        db.messages().upsert(m2)
        MediaBackfillWorker.runIfNeeded(db)
        assertEquals(2, db.messageMedia().totalCount())
    }

    @Test
    fun `idempotent — second run is a no-op`() = runBlocking {
        val m = MessageEntity(
            whom = "~zod",
            id = "~zod/1000",
            author = "~zod",
            sentMs = 100L,
            contentJson = """[{"inline":[{"link":{"href":"https://x.com/a.jpg","content":"img"}}]}]""",
            kind = "/chat",
        )
        db.messages().upsert(m)
        MediaBackfillWorker.runIfNeeded(db)
        val before = db.messageMedia().totalCount()
        assertTrue(before > 0)
        // totalCount() > 0 — second call must short-circuit and not
        // insert duplicate rows.
        MediaBackfillWorker.runIfNeeded(db)
        assertEquals(before, db.messageMedia().totalCount())
    }
}
