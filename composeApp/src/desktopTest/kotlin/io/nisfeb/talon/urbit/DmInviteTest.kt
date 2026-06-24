package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins pending-DM-request reconciliation: %chat sends the complete
 * invited-ship list (bootstrap scry + live `/v4` array facts), and
 * [TlonChatRepo.applyDmInvites] diffs it into the local table — adding
 * new requests (notifying only on live arrivals), and dropping ones
 * accepted/declined elsewhere. This is what makes a brand-new DM visible.
 */
class DmInviteTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var repo: TlonChatRepo

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-dm-invite-test-").toFile()
        db = Room.databaseBuilder<AppDatabase>(name = File(tmpDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        repo = TlonChatRepo(db = db)
    }

    @AfterTest
    fun tearDown() {
        runCatching { repo.stop() }
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    private fun ships(vararg s: String): JsonArray =
        buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }

    @Test
    fun `live invites are stored and notified`() = runBlocking {
        val notified = mutableListOf<String>()
        repo.dmInviteListener = { notified += it }
        repo.applyDmInvites(ships("~sampel-palnet", "~bus"), notify = true)
        assertEquals(setOf("~sampel-palnet", "~bus"), db.dmInvites().allShips().toSet())
        assertEquals(setOf("~sampel-palnet", "~bus"), notified.toSet())
    }

    @Test
    fun `bootstrap stores invites without notifying`() = runBlocking {
        val notified = mutableListOf<String>()
        repo.dmInviteListener = { notified += it }
        repo.applyDmInvites(ships("~sampel"), notify = false)
        assertEquals(listOf("~sampel"), db.dmInvites().allShips())
        assertTrue(notified.isEmpty(), "bootstrap must not fire notifications")
    }

    @Test
    fun `a fresh snapshot drops invites no longer present`() = runBlocking {
        repo.applyDmInvites(ships("~a-a", "~b-b"), notify = false)
        // ~a-a accepted/declined elsewhere → gone from the next snapshot.
        repo.applyDmInvites(ships("~b-b"), notify = false)
        assertEquals(listOf("~b-b"), db.dmInvites().allShips())
    }

    @Test
    fun `only newly-added ships notify, not already-pending ones`() = runBlocking {
        val notified = mutableListOf<String>()
        repo.dmInviteListener = { notified += it }
        repo.applyDmInvites(ships("~a-a"), notify = true)
        repo.applyDmInvites(ships("~a-a", "~c-c"), notify = true)
        // ~a-a notified once (first arrival), ~c-c once — re-sending the
        // full list must not re-notify a still-pending request.
        assertEquals(listOf("~a-a", "~c-c"), notified)
    }

    @Test
    fun `non-ship and non-string entries are ignored`() = runBlocking {
        val arr = buildJsonArray {
            add(JsonPrimitive("~ok-ok"))
            add(JsonPrimitive("notaship")) // no ~ prefix
            add(JsonPrimitive(5))          // not a string
        }
        repo.applyDmInvites(arr, notify = false)
        assertEquals(listOf("~ok-ok"), db.dmInvites().allShips())
    }
}
