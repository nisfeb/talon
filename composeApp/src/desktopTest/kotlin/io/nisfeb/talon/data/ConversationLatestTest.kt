package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The newest top-level message of each conversation, the home and chat
 * lists' one query. Rewritten to run its subquery once a conversation,
 * not once a message; what it answers must not move.
 */
class ConversationLatestTest {
    private val tmpDir: File = Files.createTempDirectory("latest-test-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(name = File(tmpDir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()

    @AfterTest
    fun tearDown() { db.close(); tmpDir.deleteRecursively() }

    private fun m(whom: String, id: String, sent: Long, parent: String? = null, deleted: Boolean = false) =
        MessageEntity(whom, id, "~zod", sent, "[]", "/chat", isDeleted = deleted, parentId = parent)

    @Test
    fun `one row a conversation, its newest top-level post, newest conversation first`() = runBlocking {
        db.messages().upsertAll(listOf(
            m("~bus", "a", 1_000), m("~bus", "b", 3_000),
            m("~bus", "r", 9_000, parent = "b"),        // a reply: not the latest
            m("~bus", "d", 8_000, deleted = true),      // deleted: not the latest
            m("~nec", "x", 5_000), m("~nec", "y", 5_000), // a tie: the larger id
            m("~wes", "only-a-reply", 7_000, parent = "gone"), // no top-level post: no row
        ))
        val rows = db.messages().conversationLatest().first()
        assertEquals(listOf("~nec" to "y", "~bus" to "b"), rows.map { it.whom to it.id })
    }

    @Test
    fun `every collector shares one flow for the database`() {
        assertSame(db.latestPerConversation(), db.latestPerConversation())
    }
}
