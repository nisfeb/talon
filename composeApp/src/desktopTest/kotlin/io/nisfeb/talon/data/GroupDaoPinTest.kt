package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins down the `setPinnedPostId` contract that backs the channel
 * pinned-message banner. The bug we're guarding against:
 *
 *   Pre-0.8.8, `setPinnedPostId` was `@Query("UPDATE channel_groups
 *   SET pinnedPostId = …")` returning Unit. If the channel_groups
 *   row didn't exist (e.g., a chat whose group hadn't been bootstrapped),
 *   the UPDATE matched 0 rows and the write was silently lost. The
 *   `streamPinnedPostId` flow kept emitting null and the banner never
 *   appeared. Since pinPost / unpinPost / OrderUpdate all flow through
 *   this single DAO method, every pin path was broken.
 *
 *   The fix changed the DAO to return Int (rows-affected) and added a
 *   helper in TlonChatRepo that ensures a channel_groups row exists
 *   before the UPDATE fires. Both halves are tested here so the
 *   regression can't silently come back: the DAO returns the
 *   rows-affected count, and the upsert-stub-then-update flow is the
 *   shape callers are expected to use.
 */
class GroupDaoPinTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-grouppin-test-").toFile()
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

    private fun stubChannelGroup(nest: String) = ChannelGroupEntity(
        nest = nest,
        groupFlag = nest.removePrefix("chat/"),
        title = null,
        pinnedPostId = null,
        ordinal = 0,
    )

    @Test
    fun `setPinnedPostId returns 0 when row absent`() = runBlocking {
        // The regression precondition: an unbootstrapped channel has no
        // row, and a bare UPDATE silently no-ops. The DAO surfaces this
        // via the rows-affected return so callers can recover.
        val affected = db.groups().setPinnedPostId(
            "chat/~sampel/missing", "post-1",
        )
        assertEquals(0, affected)
        // No row materialised; the read still returns null.
        assertNull(db.groups().pinnedPostIdFor("chat/~sampel/missing"))
    }

    @Test
    fun `setPinnedPostId returns 1 and updates row when row present`() = runBlocking {
        val nest = "chat/~sampel/group"
        db.groups().upsertChannelGroups(listOf(stubChannelGroup(nest)))
        val affected = db.groups().setPinnedPostId(nest, "post-1")
        assertEquals(1, affected)
        assertEquals("post-1", db.groups().pinnedPostIdFor(nest))
    }

    @Test
    fun `streamPinnedPostId emits the new value after a pin write on an existing row`() =
        runBlocking {
            val nest = "chat/~sampel/group"
            db.groups().upsertChannelGroups(listOf(stubChannelGroup(nest)))
            db.groups().setPinnedPostId(nest, "post-42")
            // first() returns the current value the Flow holds — Room
            // queries with a WHERE-clause emit per table-write.
            assertEquals("post-42", db.groups().streamPinnedPostId(nest).first())
        }

    @Test
    fun `upsertChannelGroups stub then setPinnedPostId persists - the fix path`() =
        runBlocking {
            // What TlonChatRepo.ensureChannelGroupRow + pinPost does
            // end-to-end on a fresh DB: insert a stub row keyed only on
            // the nest, then UPDATE the pinnedPostId.
            val nest = "chat/~ricsul-bilwyt/general"
            db.groups().upsertChannelGroups(listOf(stubChannelGroup(nest)))
            val affected = db.groups().setPinnedPostId(nest, "post-99")
            assertEquals(1, affected)
            assertEquals("post-99", db.groups().pinnedPostIdFor(nest))
            assertEquals("post-99", db.groups().streamPinnedPostId(nest).first())
        }

    @Test
    fun `unpin via setPinnedPostId(null) clears the slot and Flow re-emits`() =
        runBlocking {
            val nest = "chat/~sampel/group"
            db.groups().upsertChannelGroups(
                listOf(stubChannelGroup(nest).copy(pinnedPostId = "post-1")),
            )
            assertEquals("post-1", db.groups().streamPinnedPostId(nest).first())
            val affected = db.groups().setPinnedPostId(nest, null)
            assertEquals(1, affected)
            assertNull(db.groups().pinnedPostIdFor(nest))
            assertNull(db.groups().streamPinnedPostId(nest).first())
        }
}
