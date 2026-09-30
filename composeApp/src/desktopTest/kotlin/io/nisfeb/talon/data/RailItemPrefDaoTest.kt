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
    fun `upsert with same key replaces the value`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = true))
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(listOf(RailItemPrefEntity("Settings", true)), rows)
    }

    @Test
    fun `replaceAll with empty list clears the table`() = runBlocking {
        db.railItemPrefs().upsert(RailItemPrefEntity("Settings", visible = false))
        db.railItemPrefs().replaceAll(emptyList())
        val rows = db.railItemPrefs().streamAll().first()
        assertEquals(emptyList(), rows)
    }
}
