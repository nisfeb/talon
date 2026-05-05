package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression guard for the "every reinstall stamps every status to now"
 * bug fixed in 0.8.9.
 *
 * Symptom: each app upgrade re-ran `bootstrapContacts`. The bootstrap
 * pre-stamped any contact with a status but no server-provided
 * `mod-at` to `System.currentTimeMillis()`, then `mergeContact`'s
 * `incoming.statusUpdatedMs != null` branch unconditionally won —
 * overwriting the previously-trusted timestamp. Knock-on: every
 * status looked newer than `lastSeenStatusesMs`, lighting the
 * Statuses-feed pip on every upgrade for users with no actual
 * status changes.
 *
 * Fix: stop pre-stamping in bootstrap. Push the "stamp now" decision
 * into `mergeContact`, which sees the existing row and only stamps
 * when the contact is genuinely first-seen.
 *
 * These tests pin the resolution order so the regression can't
 * silently come back. Order is the contract documented in
 * `mergeContact`'s KDoc.
 */
class MergeContactStatusTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var repo: TlonChatRepo

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-mergecontact-test-").toFile()
        val dbFile = File(tmpDir, "test.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
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

    private fun contact(
        ship: String,
        status: String? = null,
        statusUpdatedMs: Long? = null,
    ) = ContactEntity(
        ship = ship,
        nickname = null,
        bio = null,
        avatarUrl = null,
        status = status,
        statusUpdatedMs = statusUpdatedMs,
        color = null,
    )

    @Test
    fun `cleared status returns null timestamp`() = runBlocking {
        // Status went blank — the timestamp goes with it. Even when
        // existing has both a status and a timestamp, the merge must
        // honor the incoming clear.
        db.contacts().upsert(
            contact("~sampel", status = "old", statusUpdatedMs = 100L),
        )
        val merged = repo.mergeContact(contact("~sampel", status = null))
        assertEquals(null, merged.status)
        assertNull(merged.statusUpdatedMs)
    }

    @Test
    fun `incoming server-provided timestamp wins over existing`() = runBlocking {
        db.contacts().upsert(
            contact("~sampel", status = "hello", statusUpdatedMs = 100L),
        )
        val merged = repo.mergeContact(
            contact("~sampel", status = "hello", statusUpdatedMs = 200L),
        )
        assertEquals(200L, merged.statusUpdatedMs)
    }

    @Test
    fun `existing timestamp survives when incoming has none — the upgrade-install case`() =
        runBlocking {
            // The exact bug we shipped 0.8.9 to fix:
            //   1. Existing contact had timestamp T from an earlier session.
            //   2. App upgrade triggers bootstrapContacts.
            //   3. Server's /v1/all entry omits mod-at (older Tlon ships do this).
            //   4. parseContact returns statusUpdatedMs=null because no mod-at.
            //   5. Pre-fix: bootstrap pre-stamped now into incoming, then
            //      mergeContact's "incoming != null wins" branch overwrote T.
            //      Every status pushed past lastSeenStatusesMs → spurious pip.
            //   6. Post-fix: bootstrap does NOT pre-stamp; mergeContact sees
            //      incoming.statusUpdatedMs=null, existing!=null, keeps T.
            db.contacts().upsert(
                contact("~sampel", status = "hello", statusUpdatedMs = 100L),
            )
            val merged = repo.mergeContact(
                contact("~sampel", status = "hello", statusUpdatedMs = null),
            )
            assertEquals(100L, merged.statusUpdatedMs)
        }

    @Test
    fun `first-seen contact with status and no server timestamp stamps now`() = runBlocking {
        // Truly first observation — the feed needs SOMETHING to sort
        // by, otherwise this contact's status sinks to the bottom
        // forever. Stamp the current wall clock; subsequent merges
        // (with a real mod-at, or without any timestamp at all) will
        // settle on the right value via the priority order.
        val before = System.currentTimeMillis()
        val merged = repo.mergeContact(
            contact("~sampel", status = "first time", statusUpdatedMs = null),
        )
        val after = System.currentTimeMillis()
        assertNotNull(merged.statusUpdatedMs)
        assertTrue(
            merged.statusUpdatedMs in before..after,
            "expected stamp in [$before, $after], got ${merged.statusUpdatedMs}",
        )
    }

    @Test
    fun `first-seen contact with status and a server timestamp uses the server value`() =
        runBlocking {
            // The happy path: ship sent us mod-at, we trust it.
            val merged = repo.mergeContact(
                contact("~sampel", status = "hello", statusUpdatedMs = 1_700_000_000_000L),
            )
            assertEquals(1_700_000_000_000L, merged.statusUpdatedMs)
        }

    @Test
    fun `nickname bio avatar still survive when incoming omits them`() = runBlocking {
        // Same merge function carries the other contact fields. Pin
        // the existing behaviour so a regression doesn't take those
        // out alongside the status fix.
        db.contacts().upsert(
            ContactEntity(
                ship = "~sampel",
                nickname = "Sam",
                bio = "Earth",
                avatarUrl = "https://example.com/sam.png",
                status = "hello",
                statusUpdatedMs = 100L,
                color = "#FF0000",
            ),
        )
        val merged = repo.mergeContact(
            ContactEntity(
                ship = "~sampel",
                nickname = null,
                bio = null,
                avatarUrl = null,
                status = "hello",
                statusUpdatedMs = null,
                color = null,
            ),
        )
        assertEquals("Sam", merged.nickname)
        assertEquals("Earth", merged.bio)
        assertEquals("https://example.com/sam.png", merged.avatarUrl)
        assertEquals(100L, merged.statusUpdatedMs)
    }
}
