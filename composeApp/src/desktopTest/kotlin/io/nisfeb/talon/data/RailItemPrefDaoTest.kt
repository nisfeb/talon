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

class RailItemPrefDaoTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("rail-item-prefs-test-").toFile()
        val dbFile = File(tmpDir, "ui.db")
        db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    @Test
    fun `empty table streams empty list`() = runBlocking {
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `upsert then stream returns the row`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", false)), rows)
    }

    @Test
    fun `upsert with same key replaces the value`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = true))
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", true)), rows)
    }

    @Test
    fun `delete removes the row`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().delete("Settings")
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }

    @Test
    fun `replaceAll wipes existing rows and inserts new ones`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().upsert(RailItemPrefEntity("Profile", visible = false))
        db.railItemPrefs().replaceAll(listOf(RailItemPrefEntity("Watchwords", visible = false)))
        val rows = db.railItemPrefs().streamAll().first().sortedBy { it.itemName }
        assertEquals(listOf(RailItemPrefEntity("Watchwords", false)), rows)
    }

    @Test
    fun `replaceAll with empty list clears the table`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().replaceAll(emptyList())
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }
}
