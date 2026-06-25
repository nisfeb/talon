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

class AssistantConversationDaoTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("assistant-conv-test-").toFile()
        db = Room.databaseBuilder<AppDatabase>(name = File(tmpDir, "ui.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    private fun conv(title: String, updatedAt: Long) = AssistantConversationEntity(
        title = title, createdAt = updatedAt, updatedAt = updatedAt,
        centroid = ByteArray(0), dim = 0, turnCount = 1,
    )

    @Test
    fun `recent orders by updatedAt desc and mostRecent matches`() = runBlocking {
        val dao = db.assistantConversations()
        dao.insert(conv("old", 100))
        dao.insert(conv("mid", 200))
        dao.insert(conv("new", 300))
        assertEquals(listOf("new", "mid", "old"), dao.recent(10).first().map { it.title })
        assertEquals("new", dao.mostRecent()?.title)
    }

    @Test
    fun `turns join their conversation`() = runBlocking {
        val dao = db.assistantConversations()
        val id = dao.insert(conv("topic", 100))
        val turns = db.assistantHistory()
        turns.insert(AssistantHistoryEntity(mode = "Assistant", question = "q1", answer = "a1", createdAt = 1, conversationId = id))
        turns.insert(AssistantHistoryEntity(mode = "Assistant", question = "q2", answer = "a2", createdAt = 2, conversationId = id))
        turns.insert(AssistantHistoryEntity(mode = "Assistant", question = "other", answer = "x", createdAt = 3, conversationId = id + 999))
        assertEquals(listOf("q1", "q2"), turns.forConversation(id).map { it.question })
    }

    @Test
    fun `trim keeps the newest N conversations`() = runBlocking {
        val dao = db.assistantConversations()
        repeat(5) { dao.insert(conv("c$it", it.toLong())) }
        dao.trim(keep = 2)
        assertEquals(listOf("c4", "c3"), dao.recent(10).first().map { it.title })
    }
}
