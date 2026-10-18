package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the agent tool argument-handling that the model can drive:
 * required-arg validation (incl. blank-content rejection), the k clamp,
 * and the empty-result format — all of which short-circuit BEFORE any
 * repo poke, so a malformed tool call never reaches the network and a
 * write never fires on garbage input.
 */
class ToolCatalogTest {

    private lateinit var tmpDir: File
    private lateinit var db: AppDatabase
    private lateinit var repo: TlonChatRepo
    private lateinit var tools: List<Tool>

    /** Returns a fixed (empty by default) hit list; never touches the network. */
    private val embedder = object : SearchEmbedderClient {
        override val progress = MutableStateFlow(IndexProgress())
        override suspend fun start() {}
        override suspend fun semanticSearch(query: String): List<MessageEntity> = emptyList()
        override suspend fun keywordSearch(terms: List<String>): List<MessageEntity> = emptyList()
        override suspend fun embed(text: String): FloatArray? = null
        override suspend fun computeHighlights(): List<MessageEntity> = emptyList()
    }

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-toolcatalog-test-").toFile()
        db = Room.databaseBuilder<AppDatabase>(name = File(tmpDir, "test.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        repo = TlonChatRepo(db = db)
        tools = ToolCatalog.default(repo, db, embedder) { it }
    }

    @AfterTest
    fun tearDown() {
        runCatching { repo.stop() }
        runCatching { db.close() }
        tmpDir.deleteRecursively()
    }

    private fun argsOf(vararg p: Pair<String, String>): JsonObject =
        buildJsonObject { p.forEach { (k, v) -> put(k, v) } }

    private fun run(name: String, args: JsonObject): String = runBlocking {
        tools.first { it.spec.name == name }.execute(args)
    }

    @Test
    fun `write tools are flagged write, read tools are not`() {
        fun isWrite(n: String) = tools.first { it.spec.name == n }.write
        assertTrue(listOf("send_message", "reply", "react", "mark_read").all { isWrite(it) })
        assertTrue(listOf("search_history", "read_conversation").none { isWrite(it) })
    }

    @Test
    fun `missing required args return an Error without poking the repo`() {
        // If validation didn't short-circuit, repo.send/reply/etc. would
        // try to hit the network and hang — a fast assert proves it didn't.
        assertTrue(run("send_message", argsOf("text" to "hi")).startsWith("Error"))
        assertTrue(run("send_message", argsOf("whom" to "~bus")).startsWith("Error"))
        assertTrue(run("reply", argsOf("whom" to "~bus", "text" to "hi")).startsWith("Error"))
        assertTrue(run("react", argsOf("whom" to "~bus", "post" to "1")).startsWith("Error"))
        assertTrue(run("mark_read", argsOf()).startsWith("Error"))
    }

    @Test
    fun `blank content args are rejected, not sent as empty messages`() {
        assertTrue(run("send_message", argsOf("whom" to "~bus", "text" to "   ")).startsWith("Error"))
        assertTrue(run("reply", argsOf("whom" to "~bus", "parentPost" to "1", "text" to "")).startsWith("Error"))
        assertTrue(run("react", argsOf("whom" to "~bus", "post" to "1", "emoji" to "")).startsWith("Error"))
    }

    @Test
    fun `search_history rejects a blank query`() {
        assertTrue(run("search_history", argsOf("query" to "  ")).startsWith("Error"))
    }

    @Test
    fun `search_history with no hits reports no messages and a negative k does not throw`() {
        assertEquals("No messages found.", run("search_history", argsOf("query" to "anything")))
        // k = -1 would throw in List.take without the coerceIn clamp.
        assertEquals(
            "No messages found.",
            run("search_history", buildJsonObject { put("query", "x"); put("k", -1) }),
        )
    }

    @Test
    fun `read_conversation gives the model a link's whole address`() {
        // B6-6: the model reads links whole, not as the cut preview.
        val url = "https://example.com/articles/2026/10/a-very-long-slug"
        runBlocking {
            db.messages().upsert(io.nisfeb.talon.data.MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["see ",{"link":{"href":"$url","content":"$url"}}]}]""", "/chat"))
        }
        val read = run("read_conversation", argsOf("whom" to "~bus"))
        assertTrue("see $url" in read, read)
    }

    @Test
    fun `read_conversation with an empty db reports no messages`() {
        assertEquals("No messages found.", run("read_conversation", argsOf("whom" to "~bus")))
    }

    @Test
    fun `search_history falls back to keyword-only when there is no embedder`() {
        // Null embedder (a desktop host the probe failed): search_history
        // must route to keyword search via the db, not NPE on hybridSearch.
        val noEmbedder = ToolCatalog.default(repo, db, null) { it }
        val out = runBlocking {
            noEmbedder.first { it.spec.name == "search_history" }
                .execute(argsOf("query" to "anything"))
        }
        assertEquals("No messages found.", out)
    }

    // A send behind a slow ship returns with the message in the queue:
    // the tool said "Sent." of one still waiting, and dictation said it aloud.
    @Test
    fun `a message the ship has not taken is said to be queued`() = runBlocking<Unit> {
        val ship = io.nisfeb.talon.urbit.FakeShip("~zod")
        val live = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val events = ship.channel.events().launchIn(this)
        try {
            val send = ToolCatalog.default(live, db, embedder, conversationLabel = { if (it == "~bus") "Bus" else it }) { it }
                .first { it.spec.name == "send_message" }
            assertEquals("Sent to Bus.", send.execute(argsOf("whom" to "~bus", "text" to "hi")), "named as the chat list names it")
            ship.lose = { if (it.app == "chat") java.io.IOException("The network connection was lost.") else null }
            assertEquals("Queued for Bus: your ship is slow; it goes when it answers.", send.execute(argsOf("whom" to "~bus", "text" to "again")))
            assertEquals(1, db.messages().queuedIn("~bus"))
        } finally {
            events.cancel()
            live.stopAndJoinForTest()
        }
    }
}
