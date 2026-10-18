package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.BookmarkEntity
import io.nisfeb.talon.data.BookmarkFolderEntity
import io.nisfeb.talon.data.BookmarkFolderMemberEntity
import io.nisfeb.talon.data.FolderEntity
import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.GroupOrderEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.data.NotifyPreferenceEntity
import io.nisfeb.talon.data.RailItemPrefEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A settings change the ship has not taken: kept, held over the ship's
 * word, and sent when it answers (review H7, 2026-10-17). Before, a
 * change made before the channel was attached or while the ship was out
 * of reach was dropped, and the next bootstrap's ship-wins apply wiped
 * it here too. Also the keys folders sync under, and a list emptied by a
 * deletion (M16).
 */
class SettingsSyncOutboxTest {
    private val dbs = mutableListOf<AppDatabase>()
    private fun newDb(): AppDatabase = createTempDirectory(prefix = "talon-outbox-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }.also { dbs += it }
    private val db: AppDatabase = newDb()
    private val ship = FakeShip("~zod")
    private val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings())
    private val lost = java.io.IOException("The network connection was lost.")

    @AfterTest
    fun close() = dbs.forEach { it.close() }

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private fun sent() = ship.pokesTo("settings").map { it.json.toString() }
    private suspend fun folders() = db.folders().streamFolders().first().map { it.name }

    // ─── kept until the ship takes it ─────────────────────────────

    @Test
    fun `a folder made before the ship connects is kept over the ship's word and goes up at connect`() = live {
        val id = sync.createFolder("Early", 0)
        assertTrue(sent().isEmpty(), "no channel yet")
        ship.scries["settings/desk/talon"] = """{"desk":{"folders":{"5":"{\"name\":\"From the ship\",\"sortOrder\":1}"}}}"""
        sync.attach(ship.channel)
        sync.bootstrap()
        assertEquals(setOf("From the ship", "Early"), folders().toSet(), "the ship's, and the one it had not heard of")
        assertTrue(sent().any { "put-entry" in it && "\"entry-key\":\"$id\"" in it && "Early" in it }, sent().toString())
        assertEquals(emptyList(), db.settingsOutbox().all(), "taken: nothing waits")
    }

    @Test
    fun `a change the ship does not answer is kept, and goes at the next connect`() = live {
        sync.attach(ship.channel)
        ship.lose = { if (it.app == "settings") lost else null }
        sync.setNotifyLevel("~bus", NotifyLevel.NONE)
        assertEquals(1, db.settingsOutbox().all().size)
        ship.lose = { null }
        ship.scries["settings/desk/talon"] = """{"desk":{"notify-prefs":{"~nec":"{\"level\":\"all\"}"}}}"""
        sync.bootstrap()
        assertEquals(NotifyLevel.NONE, db.notifyPrefs().levelFor("~bus"), "kept over the ship's word")
        assertEquals(NotifyLevel.ALL, db.notifyPrefs().levelFor("~nec"))
        // Wire: the kept write goes as it would have, byte for byte.
        assertEquals(ship.attempted.first().json, ship.pokesTo("settings").single { "~bus" in it.json.toString() }.json)
        assertEquals(emptyList(), db.settingsOutbox().all())
    }

    @Test
    fun `a refused change is not kept or sent again`() = live {
        sync.attach(ship.channel)
        ship.refuse = { if (it.app == "settings") "bad entry" else null }
        sync.setNotifyLevel("~bus", NotifyLevel.ALL)
        assertEquals(emptyList(), db.settingsOutbox().all())
        ship.refuse = { null }
        sync.flushPending()
        assertEquals(1, ship.pokesTo("settings").size)
    }

    // Leaving midway: the screen's scope goes with the write on the wire.
    // Kept, it goes with the next connect. Real time: the hold is the ship's.
    @Test
    fun `a change cut off midway goes later`() = live {
        sync.attach(ship.channel)
        ship.holdPoke = 2_000
        val screen = CoroutineScope(Dispatchers.Default)
        screen.launch { sync.addBookmark("chat/~bus/general", "170.1", 5) }
        delay(300)
        screen.cancel()
        delay(100)
        ship.holdPoke = 0
        assertEquals(listOf("bookmarks/chat/~bus/general|170.1"), db.settingsOutbox().all().map { it.slot })
        sync.flushPending()
        assertTrue(sent().any { "\"entry-key\":\"chat/~bus/general|170.1\"" in it }, sent().toString())
        assertEquals(emptyList(), db.settingsOutbox().all())
    }

    // A write the ship took after a bootstrap read the desk, but before it
    // applied it, was applied away, and was no longer kept to apply again.
    // The desk here is read when asked and answered half a second later.
    @Test
    fun `a change made while a bootstrap reads the desk survives it and goes after`() = live {
        sync.attach(ship.channel)
        val base = """{"desk":{"folders":{"5":"{\"name\":\"From the ship\",\"sortOrder\":1}"}}}"""
        ship.answerScry = { p -> if (p == "settings/desk/talon") ship.settingsDesk(base).also { Thread.sleep(500) } else null }
        val boot = launch { sync.bootstrap() }
        delay(150)
        val id = sync.createFolder("Mid", 0)
        assertTrue(sent().none { "Mid" in it }, "held while the desk is read")
        boot.join()
        assertEquals(setOf("From the ship", "Mid"), folders().toSet())
        assertTrue(sent().any { "\"entry-key\":\"$id\"" in it && "Mid" in it }, sent().toString())
        assertEquals(emptyList(), db.settingsOutbox().all())
    }

    // A seed is this device's state against what the ship held as read:
    // sent days later, it would overwrite what other devices wrote since.
    @Test
    fun `a seed that does not reach the ship is not kept`() = live {
        db.folders().upsert(FolderEntity(id = 1, name = "Work", sortOrder = 0))
        sync.attach(ship.channel)
        ship.scries["settings/desk/talon"] = """{"desk":{}}"""
        ship.lose = { if (it.app == "settings") lost else null }
        sync.bootstrap()
        assertTrue(ship.attempted.any { "put-bucket" in it.json.toString() }, "it was tried")
        assertEquals(emptyList(), db.settingsOutbox().all(), "the next bootstrap decides again")
        assertEquals(listOf("Work"), folders())
    }

    @Test
    fun `a newer change to a setting waiting replaces it, and only the newest goes`() = live {
        sync.attach(ship.channel)
        ship.lose = { if (it.app == "settings") lost else null }
        sync.setNotifyLevel("~bus", NotifyLevel.NONE)
        sync.setNotifyLevel("~bus", NotifyLevel.ALL)
        ship.lose = { null }
        sync.flushPending()
        val bus = ship.pokesTo("settings").map { it.json.toString() }.filter { "~bus" in it }
        assertEquals(1, bus.size, bus.toString())
        assertTrue("all" in bus.single())
    }

    // ─── folder keys (H7) ─────────────────────────────────────────

    // A folder's id was each device's own autoincrement, and its key on the
    // ship: two devices' first folders were both "1", and each overwrote
    // the other's name. An rc10 device reads this build's keys with the
    // decoder this build has (rowBuckets, key.toLongOrNull: unchanged), so
    // the device that reads them here reads them as rc10 does.
    @Test
    fun `folders made on two devices keep their own keys, and an rc10 key still reads`() = live {
        val phoneShip = FakeShip("~zod")
        val phoneEvents = phoneShip.channel.events().launchIn(this)
        try {
            val phone = SettingsSyncImpl(db = newDb(), aiSettings = FakeAiSettings()).apply { attach(phoneShip.channel) }
            sync.attach(ship.channel)
            val work = sync.createFolder("Work", 0)
            sync.addFolderMember(work, "~bus")
            val home = phone.createFolder("Home", 0)
            assertNotEquals(work, home, "two devices, two keys")
            assertTrue(work > 1_000_000_000_000_000L && work < (1L shl 53), "a number every build reads: $work")

            // A third device reads both, live and in a bootstrap.
            val reader = newDb()
            val laptop = SettingsSyncImpl(db = reader, aiSettings = FakeAiSettings())
            (ship.pokesTo("settings") + phoneShip.pokesTo("settings")).forEach { laptop.applySettingsEvent(it.json.jsonObject) }
            assertEquals(setOf(work to "Work", home to "Home"), reader.folders().streamFolders().first().map { it.id to it.name }.toSet())
            assertEquals(listOf(work to "~bus"), reader.folders().streamMembers().first().map { it.folderId to it.whom })
            val later = newDb()
            val lateShip = FakeShip("~zod").apply { scries["settings/desk/talon"] = ship.settingsDesk() }
            val lateEvents = lateShip.channel.events().launchIn(this)
            try {
                SettingsSyncImpl(db = later, aiSettings = FakeAiSettings()).apply { attach(lateShip.channel) }.bootstrap()
                assertEquals(listOf(work to "Work"), later.folders().streamFolders().first().map { it.id to it.name })
                assertEquals(listOf(work to "~bus"), later.folders().streamMembers().first().map { it.folderId to it.whom })
            } finally {
                lateEvents.cancel()
            }

            // The reverse: an rc10 device's folder, keyed by its small id.
            laptop.applySettingsEvent(buildJsonObject {
                put("put-entry", buildJsonObject {
                    put("desk", "talon"); put("bucket-key", "folders"); put("entry-key", "3")
                    put("value", """{"name":"Old","sortOrder":2}""")
                })
            })
            laptop.applySettingsEvent(buildJsonObject {
                put("put-entry", buildJsonObject {
                    put("desk", "talon"); put("bucket-key", "folder-members"); put("entry-key", "3:~nec")
                    put("value", """{"ordinal":0,"kind":"whom"}""")
                })
            })
            assertEquals("Old", reader.folders().get(3)?.name)
            assertTrue(reader.folders().streamMembers().first().any { it.folderId == 3L && it.whom == "~nec" })
            // And a folder made after it takes neither its key nor the next.
            val made = laptop.createFolder("New", 3)
            assertTrue(made != 3L && made != 4L, "$made")
        } finally {
            phoneEvents.cancel()
        }
    }

    // ─── a list emptied by a deletion (M16) ───────────────────────

    // "Empty or absent synced data never wins over local data, and a
    // deliberate deletion syncs until the user adds something back"
    // (sneagan). The last folder deleted on one device left the bucket
    // empty, and a device that had missed the delete filled it again
    // from its own rows at its next bootstrap.
    @Test
    fun `the last folder deleted here stays deleted on a device that missed it`() = live {
        sync.attach(ship.channel)
        val id = sync.createFolder("Work", 0)
        sync.addFolderMember(id, "~bus")
        val staleDb = newDb()
        staleDb.folders().upsert(FolderEntity(id = id, name = "Work", sortOrder = 0))
        staleDb.folders().addMemberRaw(id, "~bus", 1, FolderMemberEntity.KIND_WHOM)
        sync.deleteFolder(id)
        for (bucket in listOf("folders", "folder-members")) {
            assertTrue(sent().any { "\"bucket-key\":\"$bucket\"" in it && "\"entry-key\":\"emptied\"" in it }, "$bucket: ${sent()}")
        }
        val stale = FakeShip("~zod").apply { scries["settings/desk/talon"] = ship.settingsDesk() }
        val events = stale.channel.events().launchIn(this)
        try {
            SettingsSyncImpl(db = staleDb, aiSettings = FakeAiSettings()).apply { attach(stale.channel) }.bootstrap()
            assertEquals(emptyList(), staleDb.folders().streamFolders().first(), "the deletion took")
            assertEquals(emptyList(), staleDb.folders().streamMembers().first())
            assertTrue(stale.pokesTo("settings").none { "put-bucket" in it.json.toString() }, "nothing seeded back: ${stale.pokesTo("settings")}")
        } finally {
            events.cancel()
        }
    }

    @Test
    fun `a delete that leaves rows marks nothing, and each list marks its own last`() = live {
        sync.attach(ship.channel)
        sync.addBookmark("chat/~bus/general", "170.2", 4)
        sync.setNotifyLevel("~bus", NotifyLevel.NONE)
        sync.setNotifyLevel("~nec", NotifyLevel.ALL)
        sync.clearNotifyLevel("~bus")
        sync.clearNotifyLevel("~nec")
        sync.addBookmark("chat/~bus/general", "170.1", 5)
        sync.removeBookmark("chat/~bus/general", "170.1")
        assertTrue(sent().none { "emptied" in it }, "170.2 stands")
        sync.removeBookmark("chat/~bus/general", "170.2")
        sync.setRailItemVisibility(io.nisfeb.talon.ui.RailItem.entries.first(), false)
        sync.setRailItemVisibility(io.nisfeb.talon.ui.RailItem.entries.first(), true)
        val marks = sync.createBookmarkFolder("Recipes", 0)
        sync.addBookmarkToFolder(marks, "chat/~bus/general", "170.1")
        sync.removeBookmarkFromFolder(marks, "chat/~bus/general", "170.1")
        sync.deleteBookmarkFolder(marks)
        sync.pushGroupOrders()
        val emptied = sent().filter { "emptied" in it }.map { Regex("\"bucket-key\":\"([a-z-]+)\"").find(it)!!.groupValues[1] }
        assertEquals(
            // notify-prefs held: trunk reads it, and its reader is not known here.
            listOf("bookmarks", "rail-items", "bookmark-folder-members", "bookmark-folders", "group-orders"),
            emptied,
        )
    }

    // What an rc10 device does with the marker: its decoders are this
    // build's (rowBuckets is unchanged), so each runs on it here, as a
    // whole bucket and as a live entry, over rows in every table.
    @Test
    fun `every list decoder reads the emptied marker as no row`() = runBlocking<Unit> {
        db.groupOrders().insertAll(listOf(GroupOrderEntity("~bus/garden", 2)))
        db.folders().insertFolders(listOf(FolderEntity(id = 3, name = "Work", sortOrder = 1)))
        db.folders().insertMembers(listOf(FolderMemberEntity(3, "~bus/garden", ordinal = 4, kind = FolderMemberEntity.KIND_GROUP)))
        db.notifyPrefs().upsert(NotifyPreferenceEntity("~bus", NotifyLevel.NONE))
        db.railItemPrefs().insertAll(listOf(RailItemPrefEntity("Calendar", visible = false)))
        db.bookmarks().insertAll(listOf(BookmarkEntity("chat/~bus/garden", "170.1", 1_700_000_000_000)))
        db.bookmarkFolders().insertFolders(listOf(BookmarkFolderEntity(id = 9, name = "Recipes", sortOrder = 2)))
        db.bookmarkFolders().insertMembers(listOf(BookmarkFolderMemberEntity(9, "chat/~bus/garden", "170.1", ordinal = 1)))
        suspend fun rows() = listOf(
            db.groupOrders().stream().first().size, db.folders().streamFolders().first().size,
            db.folders().streamMembers().first().size, db.notifyPrefs().streamAll().first().size,
            db.railItemPrefs().streamAll().first().size, db.bookmarks().streamAll().first().size,
            db.bookmarkFolders().streamFolders().first().size, db.bookmarkFolders().streamMembers().first().size,
        )
        val marker = JsonPrimitive("""{"emptied":1784592399000}""")
        assertEquals(8, SettingsSyncImpl.ROOM_BACKED_BUCKETS.size)
        for (bucket in SettingsSyncImpl.ROOM_BACKED_BUCKETS) sync.applyEntry(bucket, SettingsSyncImpl.ENTRY_EMPTIED, marker)
        assertEquals(List(8) { 1 }, rows(), "a live marker adds and takes nothing")
        for (bucket in SettingsSyncImpl.ROOM_BACKED_BUCKETS) {
            sync.applyBucket(bucket, buildJsonObject { put(SettingsSyncImpl.ENTRY_EMPTIED, marker) })
        }
        assertEquals(List(8) { 0 }, rows(), "a marked bucket is the ship's word: no rows")
    }
}
