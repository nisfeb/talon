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

class AssistantHistoryDaoTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("assistant-history-test-").toFile()
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

    private fun entry(question: String, at: Long, mode: String = "Ask") =
        AssistantHistoryEntity(mode = mode, question = question, answer = "a:$question", createdAt = at)

    @Test
    fun `recent returns newest first`() = runBlocking {
        val dao = db.assistantHistory()
        dao.insert(entry("oldest", at = 100))
        dao.insert(entry("middle", at = 200))
        dao.insert(entry("newest", at = 300))
        val rows = dao.recent(10).first()
        assertEquals(listOf("newest", "middle", "oldest"), rows.map { it.question })
    }

    @Test
    fun `recent honours the limit`() = runBlocking {
        val dao = db.assistantHistory()
        repeat(5) { dao.insert(entry("q$it", at = it.toLong())) }
        val rows = dao.recent(2).first()
        assertEquals(listOf("q4", "q3"), rows.map { it.question })
    }

    @Test
    fun `trim keeps only the newest N`() = runBlocking {
        val dao = db.assistantHistory()
        repeat(6) { dao.insert(entry("q$it", at = it.toLong())) }
        dao.trim(keep = 3)
        val rows = dao.recent(100).first()
        assertEquals(listOf("q5", "q4", "q3"), rows.map { it.question })
    }

    @Test
    fun `clearAll empties the table`() = runBlocking {
        val dao = db.assistantHistory()
        dao.insert(entry("q", at = 1))
        dao.clearAll()
        assertEquals(emptyList(), dao.recent(100).first())
    }
}
