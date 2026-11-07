package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.data.NotifyPreferenceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Settings, Notifications, "Every group at once": each joined group's
 * level set as a group's own, through the app's settings path against a
 * stand-in ship. A review found it wrote every group every time, two
 * ship events each, on a ship with sixty groups (2026-11-05).
 */
class EveryGroupLevelTest {
    private val dir = createTempDirectory(prefix = "talon-every-group-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val ship = FakeShip("~zod")
    private val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
    private val repo = TlonChatRepo(db, settingsSync = sync).apply { attachForTest(ship.channel, "~zod") }

    @AfterTest
    fun close() {
        runBlocking { repo.stopAndJoinForTest() }
        io.nisfeb.talon.urbit.closeAfter(db)
        dir.deleteRecursively()
    }

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        db.groups().upsertGroups(listOf(GroupEntity("~bus/a", "A", null), GroupEntity("~bus/b", "B", null), GroupEntity("~bus/c", "C", null)))
        try { body() } finally { events.cancel() }
    }

    private fun levelPuts() = ship.pokesTo("settings").map { it.json.toString() }.filter { "\"bucket-key\":\"notify-prefs\"" in it }

    @Test
    fun `only the groups that change are written, each as the ship's own body, and the same tap again sends nothing`() = live {
        db.notifyPrefs().upsert(NotifyPreferenceEntity("group/~bus/b", NotifyLevel.ALL))
        assertEquals(2, repo.setEveryGroupLevel(NotifyLevel.ALL))
        assertEquals(
            listOf("~bus/a", "~bus/c").map {
                """{"put-entry":{"desk":"talon","bucket-key":"notify-prefs","entry-key":"group/$it","value":"{\"level\":\"all\"}"}}"""
            },
            levelPuts(),
        )
        assertEquals(2, ship.pokesTo("activity").size, "each told to %activity as a group's own is")
        assertEquals(setOf(NotifyLevel.ALL), db.notifyPrefs().all().map { it.level }.toSet())

        assertEquals(0, repo.setEveryGroupLevel(NotifyLevel.ALL))
        assertEquals(2, levelPuts().size, "nothing more for groups already there")
    }

    @Test
    fun `the default counts as a level, so a group never set is not written to set it`() = live {
        assertEquals(0, repo.setEveryGroupLevel(NotifyLevel.DEFAULT))
        assertTrue(levelPuts().isEmpty())
        assertEquals(3, repo.setEveryGroupLevel(NotifyLevel.NONE))
    }

    // Testing standard: leave the screen during a write and it finishes.
    // Real time, a slow ship: the caller is cancelled while it waits.
    @Test
    fun `leaving Settings midway still sets every group`() = live {
        ship.holdPoke = 300
        val screen = CoroutineScope(Dispatchers.IO)
        screen.launch { repo.setEveryGroupLevel(NotifyLevel.NONE) }
        delay(100)
        screen.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        withTimeout(10_000) { while (levelPuts().size < 3) delay(20) }
        assertEquals(setOf("group/~bus/a", "group/~bus/b", "group/~bus/c"), db.notifyPrefs().all().filter { it.level == NotifyLevel.NONE }.map { it.whom }.toSet())
    }

    @Test
    fun `a device that cannot sync settings says so and writes nothing`() = live {
        val bare = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        try {
            assertFailsWith<IllegalStateException> { bare.setEveryGroupLevel(NotifyLevel.ALL) }
            assertTrue(levelPuts().isEmpty())
        } finally {
            bare.stopAndJoinForTest()
        }
    }
}
