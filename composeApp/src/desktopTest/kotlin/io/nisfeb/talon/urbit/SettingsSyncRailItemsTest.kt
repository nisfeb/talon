package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.RailItemPrefEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class SettingsSyncRailItemsTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var sync: SettingsSyncImpl

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("settings-sync-rail-items-test-").toFile()
        val dbFile = File(tmpDir, "ui.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        // Mirror SettingsSyncApplyBucketTest.setUp — same fakes + ctor.
        sync = SettingsSyncImpl(
            db = db,
            aiSettings = FakeAiSettings(),
            dailyDigestSettings = FakeDailyDigest(),
            rearmDailyDigest = { /* no-op for these tests */ },
        )
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    @Test
    fun `applyBucket persists a 'visible false' entry as a row`() = runBlocking {
        val incoming: JsonObject = buildJsonObject {
            put("Settings", buildJsonObject { put("visible", false) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_RAIL_ITEMS, incoming)
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `applyBucket skips 'visible true' entries (absence is the default)`() = runBlocking {
        val incoming: JsonObject = buildJsonObject {
            put("Settings", buildJsonObject { put("visible", true) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_RAIL_ITEMS, incoming)
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `applyBucket replace semantics — old rows that are absent get cleared`() = runBlocking {
        // Pre-seed an explicit hide
        db.railItemPrefs().upsert(RailItemPrefEntity("Watchwords", false))
        // New bucket with only Settings hidden
        val incoming: JsonObject = buildJsonObject {
            put("Settings", buildJsonObject { put("visible", false) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_RAIL_ITEMS, incoming)
        val rows = db.railItemPrefs().streamAll().first().sortedBy { it.itemName }
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `applyBucket ignores unknown enum names (forward-compat)`() = runBlocking {
        val incoming: JsonObject = buildJsonObject {
            put("Settings", buildJsonObject { put("visible", false) })
            put("UnknownFutureItem", buildJsonObject { put("visible", false) })
        }
        sync.applyBucket(SettingsSyncImpl.BUCKET_RAIL_ITEMS, incoming)
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `applyEntry put visible=false adds a row`() = runBlocking {
        sync.applyEntry(
            SettingsSyncImpl.BUCKET_RAIL_ITEMS,
            "Settings",
            buildJsonObject { put("visible", false) },
        )
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `applyEntry put visible=true deletes the row`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", false))
        sync.applyEntry(
            SettingsSyncImpl.BUCKET_RAIL_ITEMS,
            "Settings",
            buildJsonObject { put("visible", true) },
        )
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `removeEntry deletes the row`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", false))
        sync.removeEntry(SettingsSyncImpl.BUCKET_RAIL_ITEMS, "Settings")
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `clearBucketLocally wipes all rows`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", false))
        db.railItemPrefs().upsert(RailItemPrefEntity("Profile", false))
        sync.clearBucketLocally(SettingsSyncImpl.BUCKET_RAIL_ITEMS)
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }
}
