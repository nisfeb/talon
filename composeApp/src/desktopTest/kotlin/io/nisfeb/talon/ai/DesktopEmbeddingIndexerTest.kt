package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEmbeddingEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smart search over the desktop indexer and client, with a model of the
 * test's own: what a pass embeds, what it drops, and that a search never
 * shows a deleted message by its old text (Ask and search_history go
 * through the same hybrid search).
 */
class DesktopEmbeddingIndexerTest {
    private val dir: File = Files.createTempDirectory("talon-index-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun close() { scope.cancel(); db.close(); dir.deleteRecursively() }

    /** A word per dimension, and one dimension every text has, so a text
     *  matches a query by the words they share. Counts its calls. */
    private class WordModel : DesktopEmbedder() {
        val calls = AtomicInteger()
        private val words = listOf("lunch", "dinner", "boat")
        override suspend fun embed(text: String): FloatArray {
            calls.incrementAndGet()
            val t = text.lowercase()
            val v = FloatArray(words.size + 1) { i -> if (i == words.size) 0.5f else if (words[i] in t) 1f else 0f }
            val n = sqrt(v.fold(0f) { a, x -> a + x * x })
            return FloatArray(v.size) { v[it] / n }
        }
    }

    private val model = WordModel()
    private val indexer = DesktopEmbeddingIndexer(db, model, scope)
    private val client = DesktopSearchEmbedderClient(db, model, indexer)

    private fun msg(id: String, text: String) = MessageEntity("~bus", id, "~nec", id.toLong(), """[{"inline":["$text"]}]""", "/chat")

    /** Run one pass to its end. */
    private suspend fun pass() {
        indexer.start()
        scope.coroutineContext.job.children.toList().forEach { it.join() }
    }

    private suspend fun found(query: String) = client.semanticSearch(query).map { it.id }

    @Test
    fun `a pass embeds each message once and the next with nothing new embeds nothing`() = runBlocking<Unit> {
        db.messages().upsertAll(listOf(msg("1", "lunch at noon"), msg("2", "dinner later"), msg("3", "ok"), msg("4", "boat")))
        pass()
        assertEquals(3, model.calls.get(), "too short to embed is skipped, four letters is not")
        assertEquals(listOf("1"), found("lunch"))
        assertEquals(IndexProgress(running = false, indexed = 3, total = 4), indexer.progress.value)
        val before = model.calls.get()
        pass()
        assertEquals(before, model.calls.get())
        assertEquals(IndexProgress(running = false, indexed = 3, total = 4), indexer.progress.value, "the short one is still to do")
    }

    @Test
    fun `a deleted message is not found by its old text and its vector goes at the next pass`() = runBlocking<Unit> {
        db.messages().upsertAll(listOf(msg("1", "lunch at noon"), msg("2", "lunch deleted")))
        pass()
        assertEquals(listOf("1", "2"), found("lunch").sorted())
        db.messages().softDelete("~bus", "2")
        assertEquals(listOf("1"), found("lunch"), "before any pass")
        assertEquals(listOf("1"), client.hybridSearch("lunch", 10).map { it.id }, "Ask and search_history")
        pass()
        assertNull(db.embeddings().get("~bus", "2"))
        assertNotNull(db.embeddings().get("~bus", "1"))
        assertEquals(IndexProgress(running = false, indexed = 1, total = 1), indexer.progress.value, "a deleted one is not to do")
    }

    @Test
    fun `a message or a chat no longer kept here loses its vectors`() = runBlocking<Unit> {
        db.messages().upsertAll(listOf(msg("1", "lunch at noon"), msg("2", "lunch plans"), msg("3", "boat trip").copy(whom = "~nec")))
        pass()
        db.messages().hardDelete("~bus", "2")
        db.messages().deleteConversation("~nec")
        pass()
        assertEquals(listOf("~bus" to "1"), db.embeddings().pageAfter("", "", 10).map { it.whom to it.id })
        assertEquals(listOf("1"), found("lunch"))
    }

    @Test
    fun `an edited message is found by its new text and not its old`() = runBlocking<Unit> {
        db.messages().upsertAll(listOf(msg("1", "lunch at noon")))
        pass()
        db.messages().upsertAll(listOf(msg("1", "dinner at eight")))
        pass()
        assertEquals(2, model.calls.get())
        assertEquals(listOf("1"), found("dinner"))
        assertEquals(emptyList(), found("lunch"))
        assertEquals(1, indexer.progress.value.indexed, "the same message, not a second one")
    }

    @Test
    fun `a message edited down to nothing to embed loses its vector`() = runBlocking<Unit> {
        db.messages().upsertAll(listOf(msg("1", "lunch at noon")))
        pass()
        db.messages().upsertAll(listOf(msg("1", "ok")))
        pass()
        assertNull(db.embeddings().get("~bus", "1"))
        assertEquals(0, indexer.progress.value.indexed)
    }

    @Test
    fun `a vector made before content hashes is restamped without the model`() = runBlocking<Unit> {
        val m = msg("1", "lunch at noon")
        db.messages().upsertAll(listOf(m))
        val old = StoryCache.previewFor(m).trim().take(1000).hashCode().toLong()
        db.embeddings().upsert(MessageEmbeddingEntity("~bus", "1", packEmbedding(model.embed("lunch")), 4, old))
        model.calls.set(0)
        pass()
        assertEquals(0, model.calls.get())
        assertEquals(embedHash(m), db.embeddings().get("~bus", "1")!!.textHash)
        assertEquals(listOf("1"), found("lunch"))
    }

    @Test
    fun `a message that came in after a pass is found after the next`() = runBlocking<Unit> {
        db.messages().upsertAll(listOf(msg("1", "lunch at noon")))
        pass()
        db.messages().upsertAll(listOf(msg("2", "boat trip")))
        assertEquals(emptyList(), found("boat"))
        pass()
        assertEquals(listOf("2"), found("boat"))
        assertEquals(2, indexer.progress.value.indexed)
    }

    @Test
    fun `stopped midway the index says it is not running and starts again`() = runBlocking<Unit> {
        val gate = CompletableDeferred<FloatArray>()
        val stuck = object : DesktopEmbedder() {
            override suspend fun embed(text: String): FloatArray = gate.await()
        }
        val stalled = DesktopEmbeddingIndexer(db, stuck, scope)
        val stalledClient = DesktopSearchEmbedderClient(db, stuck, stalled)
        db.messages().upsertAll(listOf(msg("1", "lunch at noon")))
        stalledClient.start()
        withTimeout(5_000) { stalled.progress.first { it.running } }
        stalledClient.stop()
        assertFalse(stalled.progress.value.running)
        stalledClient.start()
        withTimeout(5_000) { stalled.progress.first { it.running } }
        assertTrue(stalled.progress.value.running, "a pass runs again after a stop")
        stalledClient.stop()
    }
}
