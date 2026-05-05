package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression guard for the silent UPDATE-no-op pin bug (#0.8.8).
 *
 * `TlonChatRepo.ensureChannelGroupRow(nest)` is the helper that runs
 * before every pin / unpin / OrderUpdate write so a chat whose
 * `channel_groups` row hasn't been seeded yet (partial group bootstrap,
 * fresh comet, etc.) gets a stub row first. Without that helper, the
 * subsequent `UPDATE … WHERE nest = :nest` matches 0 rows and the
 * pin write is silently lost.
 *
 * These tests construct a real Room database and call the helper
 * directly — no UrbitChannel, no network. The contract being pinned:
 * after `ensureChannelGroupRow(nest)`, a `setPinnedPostId(nest, …)`
 * call updates exactly one row.
 */
class EnsureChannelGroupRowTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var repo: TlonChatRepo

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-ensure-row-test-").toFile()
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

    @Test
    fun `ensureChannelGroupRow inserts a stub when row is missing`() = runBlocking {
        val nest = "chat/~ricsul-bilwyt/general"
        assertNull(db.groups().channelGroupFor(nest))
        repo.ensureChannelGroupRow(nest)
        val row = db.groups().channelGroupFor(nest)
        assertEquals(nest, row?.nest)
        // Flag derives from the nest by stripping the `chat/` prefix.
        assertEquals("~ricsul-bilwyt/general", row?.groupFlag)
        assertNull(row?.pinnedPostId)
    }

    @Test
    fun `ensureChannelGroupRow does not clobber an existing row`() = runBlocking {
        val nest = "chat/~sampel/group"
        // Seed a row with a non-default title and a pre-existing pin.
        db.groups().upsertChannelGroups(
            listOf(
                ChannelGroupEntity(
                    nest = nest,
                    groupFlag = "~sampel/group",
                    title = "General Chat",
                    pinnedPostId = "preexisting-post",
                    ordinal = 7,
                )
            )
        )
        repo.ensureChannelGroupRow(nest)
        val row = db.groups().channelGroupFor(nest)
        // Helper bailed early because the row existed; nothing
        // overwritten. (Without that bail-out, an UPSERT would have
        // replaced title/pinnedPostId/ordinal with stub-row defaults.)
        assertEquals("General Chat", row?.title)
        assertEquals("preexisting-post", row?.pinnedPostId)
        assertEquals(7, row?.ordinal)
    }

    @Test
    fun `ensureChannelGroupRow then setPinnedPostId persists the pin`() = runBlocking {
        val nest = "chat/~sampel/general"
        repo.ensureChannelGroupRow(nest)
        val affected = db.groups().setPinnedPostId(nest, "post-id-1")
        assertEquals(1, affected)
        assertEquals("post-id-1", db.groups().pinnedPostIdFor(nest))
    }

    @Test
    fun `ensureChannelGroupRow no-ops on non-chat nest`() = runBlocking {
        // The helper's nest-to-flag derivation only meaningfully fires
        // for chat channels. Heaps, diaries, DMs aren't pin-eligible
        // anyway. Silent no-op is correct here — pinPost's require()
        // is the single source of truth for which whom-shapes pin.
        val nest = "diary/~sampel/notes"
        repo.ensureChannelGroupRow(nest)
        // No row created; ensure read-back is null.
        assertNull(db.groups().channelGroupFor(nest))
    }
}
