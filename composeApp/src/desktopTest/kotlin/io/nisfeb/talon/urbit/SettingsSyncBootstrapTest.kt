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
import io.nisfeb.talon.data.WatchwordEntity
import io.nisfeb.talon.ui.InMemoryUiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * %settings as a device meets it at startup and after: a ship with
 * nothing takes this device's settings; a ship with some gives them
 * here and is filled in where it lacks; live facts land; folder edits
 * go up; a preference from the ship is not sent back as if new.
 */
class SettingsSyncBootstrapTest {
    private fun newDb(): AppDatabase = createTempDirectory(prefix = "talon-sync-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val db: AppDatabase = newDb()
    private val ship = FakeShip("~zod")
    private val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }

    @AfterTest
    fun close() = db.close()

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private fun sent() = ship.pokesTo("settings").map { it.json.toString() }

    /** A settings event as the ship sends it, applied here. */
    private suspend fun fact(json: String) = sync.applySettingsEvent(Json.parseToJsonElement(json).jsonObject)

    private suspend fun folders() = db.folders().streamFolders().first().map { it.name }

    private suspend fun settled(what: suspend () -> Boolean) = withTimeout(10_000) { while (!what()) delay(50) }

    @Test
    fun `a ship with no settings yet is given this device's`() = live {
        db.folders().upsert(FolderEntity(id = 1, name = "Work", sortOrder = 0))
        db.notifyPrefs().upsert(NotifyPreferenceEntity("~bus", NotifyLevel.NONE))
        ship.scries["settings/desk/talon"] = """{"desk":{}}"""
        sync.bootstrap()
        assertTrue(sent().any { "put-bucket" in it && "\"bucket-key\":\"folders\"" in it && "Work" in it }, sent().toString())
        assertTrue(sent().any { "\"bucket-key\":\"notify-prefs\"" in it && "~bus" in it })
        assertEquals(listOf("Work"), folders(), "nothing taken away here")
    }

    @Test
    fun `a ship's settings land here, and a bucket it lacks is filled from here`() = live {
        db.folders().upsert(FolderEntity(id = 1, name = "Work", sortOrder = 0))
        db.notifyPrefs().upsert(NotifyPreferenceEntity("~bus", NotifyLevel.NONE))
        db.watchwords().upsertTerm(WatchwordEntity(term = "mars", notify = true, createdMs = 1))
        ship.scries["settings/desk/talon"] = """{"desk":{
            "folders":{"5":"{\"name\":\"From the ship\",\"sortOrder\":0}"},
            "group-orders":{"~bus/garden":"{\"ordinal\":3}"}}}"""
        sync.bootstrap()
        assertEquals(listOf("From the ship"), folders(), "the ship's folders replace these")
        assertEquals(listOf("~bus/garden"), db.groupOrders().stream().first().map { it.flag })
        // What the ship lacks is kept here and sent up: it used to be
        // applied as empty first, erasing it before the seed read it.
        assertEquals(NotifyLevel.NONE, db.notifyPrefs().levelFor("~bus"))
        assertEquals(listOf("mars"), db.watchwords().streamTerms().first().map { it.term })
        assertTrue(sent().any { "\"bucket-key\":\"notify-prefs\"" in it && "~bus" in it }, "the ship had none: this device's go up")
        assertTrue(sent().any { "\"bucket-key\":\"watchwords\"" in it && "mars" in it })
        assertTrue(sent().none { "put-bucket" in it && "\"bucket-key\":\"folders\"" in it }, "the ship's folders are not overwritten")
    }

    @Test
    fun `what one device seeds, another takes back whole`() = live {
        db.groupOrders().insertAll(listOf(GroupOrderEntity("~bus/garden", 2)))
        db.folders().insertFolders(listOf(FolderEntity(id = 3, name = "Work", sortOrder = 1)))
        db.folders().insertMembers(listOf(FolderMemberEntity(3, "~bus/garden", ordinal = 4, kind = FolderMemberEntity.KIND_GROUP)))
        db.notifyPrefs().upsert(NotifyPreferenceEntity("~bus", NotifyLevel.NONE))
        db.railItemPrefs().insertAll(listOf(RailItemPrefEntity("Calendar", visible = false)))
        db.bookmarks().insertAll(listOf(BookmarkEntity("chat/~bus/garden", "170.1", 1_700_000_000_000)))
        db.bookmarkFolders().insertFolders(listOf(BookmarkFolderEntity(id = 9, name = "Recipes", sortOrder = 2)))
        db.bookmarkFolders().insertMembers(listOf(BookmarkFolderMemberEntity(9, "chat/~bus/garden", "170.1", ordinal = 1)))
        ship.scries["settings/desk/talon"] = """{"desk":{}}"""
        sync.bootstrap()
        // The ship keeps what it was sent; another device reads it back.
        val desk = buildJsonObject {
            ship.pokesTo("settings").mapNotNull { it.json.jsonObject["put-bucket"]?.jsonObject }
                .forEach { put(it["bucket-key"]!!.jsonPrimitive.content, it["bucket"]!!) }
        }
        val otherDb = newDb()
        val other = FakeShip("~zod").apply { scries["settings/desk/talon"] = """{"desk":$desk}""" }
        val events = other.channel.events().launchIn(this)
        try {
            SettingsSyncImpl(db = otherDb, aiSettings = FakeAiSettings()).apply { attach(other.channel) }.bootstrap()
            assertEquals(db.groupOrders().stream().first(), otherDb.groupOrders().stream().first())
            assertEquals(db.folders().streamFolders().first(), otherDb.folders().streamFolders().first())
            assertEquals(db.folders().streamMembers().first(), otherDb.folders().streamMembers().first())
            assertEquals(NotifyLevel.NONE, otherDb.notifyPrefs().levelFor("~bus"))
            assertEquals(db.railItemPrefs().streamAll().first(), otherDb.railItemPrefs().streamAll().first())
            assertEquals(db.bookmarks().streamAll().first(), otherDb.bookmarks().streamAll().first())
            assertEquals(db.bookmarkFolders().streamFolders().first(), otherDb.bookmarkFolders().streamFolders().first())
            assertEquals(db.bookmarkFolders().streamMembers().first(), otherDb.bookmarkFolders().streamMembers().first())
        } finally {
            events.cancel()
            otherDb.close()
        }
    }

    @Test
    fun `without the settings app nothing is sent or changed`() = live {
        db.folders().upsert(FolderEntity(id = 1, name = "Work", sortOrder = 0))
        sync.bootstrap()
        assertTrue(sent().isEmpty())
        assertEquals(listOf("Work"), folders())
    }

    @Test
    fun `live facts put, remove and replace, for this desk only`() = live {
        fact("""{"put-entry":{"desk":"talon","bucket-key":"notify-prefs","entry-key":"~nec","value":"{\"level\":\"all\"}"}}""")
        assertEquals(NotifyLevel.ALL, db.notifyPrefs().levelFor("~nec"))
        fact("""{"put-entry":{"desk":"landscape","bucket-key":"notify-prefs","entry-key":"~wes","value":"{\"level\":\"all\"}"}}""")
        assertEquals(null, db.notifyPrefs().levelFor("~wes"), "another desk's settings are not ours")
        fact("""{"del-entry":{"desk":"talon","bucket-key":"notify-prefs","entry-key":"~nec"}}""")
        assertEquals(null, db.notifyPrefs().levelFor("~nec"))
        fact("""{"put-bucket":{"desk":"talon","bucket-key":"folders","bucket":{"7":"{\"name\":\"Replaced\",\"sortOrder\":0}"}}}""")
        assertEquals(listOf("Replaced"), folders())
    }

    @Test
    fun `another device switching watchword sync off leaves the terms here`() = live {
        db.watchwords().upsertTerm(WatchwordEntity(term = "mars", notify = true, createdMs = 1))
        fact("""{"del-bucket":{"desk":"talon","bucket-key":"watchwords"}}""")
        fact("""{"del-bucket":{"desk":"talon","bucket-key":"watchword-excludes"}}""")
        assertEquals(listOf("mars"), db.watchwords().streamTerms().first().map { it.term })
    }

    @Test
    fun `folders made, renamed, filled and emptied here reach the ship`() = live {
        val id = sync.createFolder("Work", 0)
        sync.renameFolder(id, "Play")
        sync.addGroupToFolder(id, "~bus/garden")
        sync.removeFolderMember(id, "~bus/garden")
        assertEquals(listOf("Play"), folders())
        val folderPokes = sent()
        assertTrue(folderPokes.any { "put-entry" in it && "\"entry-key\":\"$id\"" in it && "Play" in it }, folderPokes.toString())
        assertTrue(folderPokes.any { "put-entry" in it && "\"entry-key\":\"$id:~bus/garden\"" in it })
        assertTrue(folderPokes.last().let { "del-entry" in it && "$id:~bus/garden" in it })
    }

    @Test
    fun `a folder deleted here takes its members off the ship`() = live {
        val id = sync.createFolder("Work", 0)
        sync.addFolderMember(id, "~bus")
        sync.addGroupToFolder(id, "~nec/garden")
        val marks = sync.createBookmarkFolder("Recipes", 0)
        sync.addBookmarkToFolder(marks, "chat/~bus/garden", "170.1")
        sync.deleteFolder(id)
        sync.deleteBookmarkFolder(marks)
        val dels = sent().filter { "del-entry" in it }
        // Left there, the next folder a new device made under this id got them.
        for (key in listOf("$id:~bus", "$id:~nec/garden", "$marks|chat/~bus/garden|170.1")) {
            assertTrue(dels.any { "\"entry-key\":\"$key\"" in it }, "$key in $dels")
        }
    }

    @Test
    fun `members the ship holds for a folder it no longer has are dropped, here and there`() = live {
        ship.scries["settings/desk/talon"] = """{"desk":{
            "folders":{"1":"{\"name\":\"Work\",\"sortOrder\":0}"},
            "folder-members":{"1:~bus":"{\"ordinal\":0}","3:~nec":"{\"ordinal\":0}"},
            "bookmark-folders":{"9":"{\"name\":\"Recipes\",\"sortOrder\":0}"},
            "bookmark-folder-members":{"9|chat/x|1":"{\"ordinal\":0}","4|chat/x|2":"{\"ordinal\":0}"}}}"""
        sync.bootstrap()
        assertEquals(listOf(1L to "~bus"), db.folders().streamMembers().first().map { it.folderId to it.whom })
        assertEquals(listOf(9L), db.bookmarkFolders().streamMembers().first().map { it.folderId })
        val dels = sent().filter { "del-entry" in it }
        assertTrue(dels.any { "\"entry-key\":\"3:~nec\"" in it } && dels.any { "\"entry-key\":\"4|chat/x|2\"" in it }, dels.toString())
        assertTrue(dels.none { "1:~bus" in it || "9|chat/x|1" in it }, "a member of a folder that stands stays")
    }

    @Test
    fun `a drag reorders here at once, and goes up when it ends`() = live {
        db.groupOrders().insertAll(listOf(GroupOrderEntity("~bus/a", 0), GroupOrderEntity("~bus/b", 1)))
        sync.reorderGroupOrdersLocal(listOf("~bus/b", "~bus/a"))
        val id = sync.createFolder("Work", 0)
        sync.addFolderMember(id, "~bus")
        sync.addGroupToFolder(id, "~nec/garden")
        sync.reorderFolderMembersLocal(id, listOf("~nec/garden", "~bus"))
        val before = sent().size
        assertEquals(listOf("~bus/b", "~bus/a"), db.groupOrders().stream().first().sortedBy { it.ordinal }.map { it.flag })
        sync.pushGroupOrders()
        sync.pushFolderMembersOrder(id)
        val up = sent().drop(before)
        assertTrue(up.any { "\"bucket-key\":\"group-orders\"" in it && "~bus/b\":\"{\\\"ordinal\\\":0}" in it }, up.toString())
        assertTrue(up.any { "\"entry-key\":\"$id:~nec/garden\"" in it && "\\\"ordinal\\\":0" in it && "group" in it }, up.toString())
    }

    @Test
    fun `a preference from the ship applies here and is not sent back, a change here goes up`() = live {
        val ui = InMemoryUiSettings()
        // Its watchers run for as long as the scope does: one of their own.
        val watchers = CoroutineScope(SupervisorJob())
        try {
        sync.attachUiSettings(ui, watchers)
        fact("""{"put-entry":{"desk":"talon","bucket-key":"ui-prefs","entry-key":"power-features","value":"{\"enabled\":true}"}}""")
        settled { ui.powerFeaturesEnabled.value }
        delay(300)
        assertTrue(sent().none { "power-features" in it }, "the ship's own value is not echoed")
        ui.setSmartSearchPreferred(!ui.smartSearchPreferred.value)
        settled { sent().any { "smart-search-preferred" in it } }
        } finally {
            watchers.cancel()
        }
    }
}
