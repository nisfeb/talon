package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEmbeddingEntity
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The search and the indexers page by key now, not by OFFSET (which read
 * every earlier row again for each page, and for the embeddings had no
 * order to promise the same pages twice). Every row once, across pages.
 */
class SemanticSearchPagingTest {
    private val dir: File = Files.createTempDirectory("keyset-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()

    @AfterTest
    fun close() { db.close(); dir.deleteRecursively() }

    @Test
    fun `a search over several pages sees every row once`() = runBlocking {
        val v = floatArrayOf(1f, 0f)
        val keys = listOf("~bus" to "1", "~bus" to "2", "~nec" to "1", "~nec" to "3", "~wes" to "9")
        db.embeddings().upsertAll(keys.map { (w, id) -> MessageEmbeddingEntity(w, id, packEmbedding(v), 2, 0) })
        val hits = semanticSearch(v, db.embeddings(), k = 10, pageSize = 2)
        assertEquals(keys.toSet(), hits.map { it.whom to it.id }.toSet())
        assertEquals(keys.size, hits.size)
    }

    @Test
    fun `messages page by key across conversations`() = runBlocking {
        val keys = listOf("~bus" to "1", "~bus" to "2", "~nec" to "1", "~wes" to "9", "~wes" to "a")
        db.messages().upsertAll(keys.map { (w, id) -> MessageEntity(w, id, "~zod", 1, "[]", "/chat") })
        val seen = mutableListOf<Pair<String, String>>()
        var after = "" to ""
        while (true) {
            val page = db.messages().pageAfter(after.first, after.second, 2)
            if (page.isEmpty()) break
            seen += page.map { it.whom to it.id }
            after = page.last().whom to page.last().id
        }
        assertEquals(keys, seen)
    }
}
