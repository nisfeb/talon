package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
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
        val n = db.messages().reapOldestLocalTwin(whom, me, 2000)
        assertEquals(1, n)
        assertNull(db.messages().getOne(whom, "local_1"))      // oldest twin gone
        assertNotNull(db.messages().getOne(whom, "local_2"))   // newer twin kept
        assertNotNull(db.messages().getOne(whom, "~author/999")) // white row untouched
        Unit
    }

    // The echo of a post sent from another device found no exact twin and
    // took this device's oldest local row: a queued or failed post, or a
    // reply, which then never went (review M2, 2026-10-17).
    @Test
    fun `fallback takes only a post in flight, top-level, sent no later than the echo`() = runBlocking {
        db.messages().upsert(row("local_1", 1000, status = "queued"))
        db.messages().upsert(row("local_2", 1100, status = "failed"))
        db.messages().upsert(row("local_3", 1200, status = "pending").copy(parentId = "170141184506"))
        db.messages().upsert(row("local_4", 9000, status = "pending"))
        assertEquals(0, db.messages().reapOldestLocalTwin(whom, me, 5000), "an echo older than every post in flight takes none")
        assertEquals(1, db.messages().reapOldestLocalTwin(whom, me, 8500), "a second of skew either way")
        assertNull(db.messages().getOne(whom, "local_4"))
        for (kept in listOf("local_1", "local_2", "local_3")) assertNotNull(db.messages().getOne(whom, kept), kept)
        Unit
    }

    // A refresh's purge of old ghosts deleted every local row not queued:
    // a refused post's "Not sent" went, and a post deleted mid-send had no
    // row for its send to mark (review M1).
    @Test
    fun `the purge takes only a row with no send-state`() = runBlocking {
        db.messages().upsert(row("local_1", 1000))
        db.messages().upsert(row("~author/170.1", 1000))
        for ((id, status) in listOf("local_2" to "pending", "local_3" to "queued", "local_4" to "failed")) {
            db.messages().upsert(row(id, 2000, status = status))
        }
        db.messages().upsert(row("170141184506", 3000, author = "~bus"))
        db.messages().purgeStaleLocalIds(whom)
        assertNull(db.messages().getOne(whom, "local_1"))
        assertNull(db.messages().getOne(whom, "~author/1701"))
        for (kept in listOf("local_2", "local_3", "local_4", "170141184506")) assertNotNull(db.messages().getOne(whom, kept), kept)
        Unit
    }

    // A queued DM deleted the usual way (soft) was still in the queue, and
    // the drain sent it (review A4-1).
    @Test
    fun `a deleted message is not in the queue or its counts`() = runBlocking {
        db.messages().upsert(row("local_1", 1000, status = "queued"))
        db.messages().upsert(row("local_2", 2000, status = "queued").copy(isDeleted = true))
        db.messages().upsert(row("local_3", 3000, status = "queued").copy(whom = "~bus", isDeleted = true))
        assertEquals(listOf("local_1"), db.messages().queued().map { it.id })
        assertEquals(1, db.messages().queuedIn(whom))
        assertEquals(0, db.messages().queuedIn("~bus"))
        assertEquals(1, db.messages().queuedCount().first())
    }

    @Test
    fun `a post left in flight is queued at start, and nothing else moves`() = runBlocking {
        db.messages().upsert(row("local_1", 1000, status = "pending"))
        db.messages().upsert(row("local_2", 2000, status = "failed"))
        db.messages().upsert(row("local_3", 3000, status = "queued"))
        assertEquals(1, db.messages().requeueStalePending())
        assertEquals(listOf("queued", "failed", "queued"), listOf("local_1", "local_2", "local_3").map { db.messages().getOne(whom, it)?.status })
    }
}
