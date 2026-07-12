package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers the optimistic-twin reaping that flips a just-sent channel
 * post from grey (local_*) to white (server id): the exact-sentMs
 * match, its miss signal, and the oldest-first fallback used when the
 * host didn't round-trip essay.sent byte-for-byte.
 */
class MessageReapDaoTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private val whom = "chat/~host/room"
    private val me = "~author"

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("message-reap-test-").toFile()
        db = Room.databaseBuilder<AppDatabase>(name = File(tmpDir, "ui.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    private fun row(id: String, sentMs: Long, author: String = me, status: String? = null) =
        MessageEntity(whom, id, author, sentMs, "[]", "/chat", status = status)

    @Test
    fun `exact match reaps the twin and reports one row`() = runBlocking {
        db.messages().upsert(row("local_1", 1000, status = "pending"))
        val n = db.messages().reapLocalTwin(whom, me, 1000)
        assertEquals(1, n)
        assertNull(db.messages().getOne(whom, "local_1"))
    }

    @Test
    fun `sentMs mismatch reports zero and leaves the twin`() = runBlocking {
        db.messages().upsert(row("local_1", 1000, status = "pending"))
        val n = db.messages().reapLocalTwin(whom, me, 2000)
        assertEquals(0, n)
        assertNotNull(db.messages().getOne(whom, "local_1"))
        Unit
    }

    @Test
    fun `fallback removes only the oldest local twin, sparing newer and server rows`() = runBlocking {
        db.messages().upsert(row("local_1", 1000, status = "pending"))
        db.messages().upsert(row("local_2", 2000, status = "pending"))
        db.messages().upsert(row("~author/999", 1500)) // server-echo white row
        val n = db.messages().reapOldestLocalTwin(whom, me)
        assertEquals(1, n)
        assertNull(db.messages().getOne(whom, "local_1"))      // oldest twin gone
        assertNotNull(db.messages().getOne(whom, "local_2"))   // newer twin kept
        assertNotNull(db.messages().getOne(whom, "~author/999")) // white row untouched
        Unit
    }
}
