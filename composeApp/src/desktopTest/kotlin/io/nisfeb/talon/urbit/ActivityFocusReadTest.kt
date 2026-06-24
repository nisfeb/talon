package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for "DMs silently marked read": an incoming %activity
 * update for the open chat was auto-zeroed (no unread badge) and pushed
 * a markRead to the ship — but only [openWhom] was checked, so a chat
 * left open while the app was backgrounded (Android) or its window
 * unfocused (desktop) kept auto-reading. The fix gates the focus
 * override on [TlonChatRepo.setForeground]; this pins it.
 */
class ActivityFocusReadTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var repo: TlonChatRepo

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-activity-focus-test-").toFile()
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

    /** %activity "activity" envelope: one DM (`~sampel`) with 7 unread. */
    private fun unreadActivity(whom: String = "~sampel", count: Int = 7): JsonObject =
        buildJsonObject {
            put("activity", buildJsonObject {
                put("ship/$whom", buildJsonObject {
                    put("count", count)
                    put("notify-count", count)
                    put("recency", 1_777_000_000_000L)
                    put("notify", true)
                })
            })
        }

    @Test
    fun `open chat while foregrounded is treated as read`() = runBlocking {
        repo.setOpenChat("~sampel")
        repo.setForeground(true)
        repo.applyActivityUpdate(unreadActivity())
        // Actively viewing it → badge cleared.
        assertEquals(0, db.unreads().getOne("~sampel")?.count ?: 0)
    }

    @Test
    fun `open chat while backgrounded keeps its unread badge`() = runBlocking {
        // The bug: this used to zero the badge because only openWhom was
        // checked. Backgrounded → not actually being read → badge stays.
        repo.setOpenChat("~sampel")
        repo.setForeground(false)
        repo.applyActivityUpdate(unreadActivity())
        assertEquals(7, db.unreads().getOne("~sampel")?.count)
    }

    @Test
    fun `returning to foreground resumes treating the open chat as read`() = runBlocking {
        repo.setOpenChat("~sampel")
        repo.setForeground(false)
        repo.applyActivityUpdate(unreadActivity())
        assertEquals(7, db.unreads().getOne("~sampel")?.count)

        repo.setForeground(true)
        repo.applyActivityUpdate(unreadActivity())
        assertEquals(0, db.unreads().getOne("~sampel")?.count)
    }

    @Test
    fun `a chat that isn't the open one always keeps its unread`() = runBlocking {
        repo.setOpenChat("~other")
        repo.setForeground(true)
        repo.applyActivityUpdate(unreadActivity(whom = "~sampel"))
        // Not the focused chat → never zeroed, foreground or not.
        assertEquals(7, db.unreads().getOne("~sampel")?.count)
    }
}
