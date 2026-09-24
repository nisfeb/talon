package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.ReactionPalette
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the event stream does to the database: each fact the ship sends,
 * in the shape %chat and %channels send it, lands as the rows the
 * screens read. The parsers underneath have their own tests; these are
 * the writes.
 */
class TlonChatRepoIngestTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-ingest-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val repo = TlonChatRepo(db).apply { attachForTest(FakeShip().channel, "~zod") }
    private val heard = mutableListOf<Pair<MessageEntity, Boolean>>()

    init {
        repo.messageListener = { m, toUs -> heard += m to toUs }
    }

    @AfterTest
    fun close() = db.close()

    private suspend fun fact(json: String) =
        repo.applyEvent(Json.parseToJsonElement("""{"id":1,"response":"diff","json":$json}"""))

    private fun essay(author: String, text: String, sent: Long) =
        """{"content":[{"inline":["$text"]}],"author":"$author","sent":$sent,"kind":"/chat","blob":null,"meta":null}"""

    private fun dm(id: String, response: String) = """{"whom":"~bus","id":"$id","response":$response}"""

    private fun channel(nest: String, id: String, rPost: String) =
        """{"nest":"$nest","response":{"post":{"id":"$id","r-post":$rPost}}}"""

    private val nest = "chat/~bus/general"

    // ─── DMs ──────────────────────────────────────────────────────

    @Test
    fun `a DM from somebody lands under its undotted id and is announced`() = runBlocking<Unit> {
        fact(dm("~bus/170.141.184.506", """{"add":{"essay":${essay("~bus", "hi there", 1_000)},"time":null}}"""))
        val row = assertNotNull(db.messages().getOne("~bus", "~bus/170141184506"), "stored undotted")
        assertEquals("~bus", row.author)
        assertEquals(1_000L, row.sentMs)
        assertTrue("hi there" in row.contentJson)
        assertNull(db.messages().getOne("~bus", "~bus/170.141.184.506"), "no dotted twin")
        assertEquals(listOf("~bus/170141184506" to false), heard.map { it.first.id to it.second })
    }

    @Test
    fun `our own DM is stored but not announced to us`() = runBlocking<Unit> {
        fact(dm("~zod/170141184507", """{"add":{"essay":${essay("~zod", "mine", 2_000)},"time":null}}"""))
        assertNotNull(db.messages().getOne("~bus", "~zod/170141184507"))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a deleted DM is kept as deleted and loses its reactions`() = runBlocking<Unit> {
        val id = "~bus/170141184506"
        fact(dm(id, """{"add":{"essay":${essay("~bus", "oops", 1_000)},"time":null}}"""))
        fact(dm(id, """{"add-react":{"author":"~zod","react":"👍"}}"""))
        fact(dm(id, """{"del":null}"""))
        assertTrue(db.messages().getOne("~bus", id)!!.isDeleted)
        assertNull(db.reactions().get("~bus", id, "~zod"))
    }

    @Test
    fun `a DM reaction is stored normalised and taken away by its author`() = runBlocking<Unit> {
        val id = "~bus/170141184506"
        fact(dm(id, """{"add-react":{"author":"~nec","react":":+1:"}}"""))
        assertEquals(ReactionPalette.normalize("👍"), db.reactions().get("~bus", id, "~nec")?.emoji)
        fact(dm(id, """{"del-react":"~nec"}"""))
        assertNull(db.reactions().get("~bus", id, "~nec"))
    }

    @Test
    fun `a reply to our DM is threaded under it and announced as a reply to us`() = runBlocking<Unit> {
        val parent = "~zod/170141184506"
        fact(dm(parent, """{"add":{"essay":${essay("~zod", "question", 1_000)},"time":null}}"""))
        fact(dm(parent, """{"reply":{"id":"~bus/170.141.184.507","meta":null,"delta":{"add":{"reply-essay":${essay("~bus", "answer", 2_000)},"time":null}}}}"""))
        val reply = assertNotNull(db.messages().getOne("~bus", "~bus/170141184507"))
        assertEquals(parent, reply.parentId)
        assertEquals(listOf(true), heard.map { it.second }, "a reply to our message is flagged as one")
    }

    @Test
    fun `a deleted DM reply is kept as deleted`() = runBlocking<Unit> {
        val parent = "~bus/170141184506"
        fact(dm(parent, """{"reply":{"id":"~bus/170141184507","meta":null,"delta":{"add":{"reply-essay":${essay("~bus", "r", 2_000)},"time":null}}}}"""))
        fact(dm(parent, """{"reply":{"id":"~bus/170141184507","meta":null,"delta":{"del":null}}}"""))
        assertTrue(db.messages().getOne("~bus", "~bus/170141184507")!!.isDeleted)
    }

    // ─── channels ─────────────────────────────────────────────────

    @Test
    fun `a channel post lands under its undotted id and is announced`() = runBlocking<Unit> {
        fact(channel(nest, "170.141.184.506", """{"set":{"seal":{"id":"170.141.184.506","reacts":{"~nec":":fire:"},"replies":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}},"essay":${essay("~bus", "hello channel", 1_000)}}}"""))
        val row = assertNotNull(db.messages().getOne(nest, "170141184506"))
        assertTrue("hello channel" in row.contentJson)
        assertEquals(ReactionPalette.normalize(":fire:"), db.reactions().get(nest, "170141184506", "~nec")?.emoji)
        assertEquals(listOf("170141184506"), heard.map { it.first.id })
    }

    @Test
    fun `our own post's echo replaces the grey copy we showed while sending`() = runBlocking<Unit> {
        val local = MessageEntity(nest, "local_1", "~zod", 5_000, """[{"inline":["mine"]}]""", "/chat", status = "pending")
        db.messages().upsert(local)
        fact(channel(nest, "170141184506", """{"set":{"seal":{"id":"170141184506","reacts":{},"replies":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}},"essay":${essay("~zod", "mine", 5_000)}}}"""))
        assertNull(db.messages().getOne(nest, "local_1"), "grey twin reaped")
        assertNotNull(db.messages().getOne(nest, "170141184506"))
        assertTrue(heard.isEmpty(), "our own post is not announced")
    }

    @Test
    fun `a batch after a reconnect stores every post and reaps our grey copies`() = runBlocking<Unit> {
        db.messages().upsert(MessageEntity(nest, "local_9", "~zod", 7_000, "[]", "/chat", status = "pending"))
        val seal = { id: String -> """{"id":"$id","reacts":{},"replies":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}}""" }
        fact("""{"nest":"$nest","response":{"posts":{
            "170141184506":{"seal":${seal("170141184506")},"essay":${essay("~bus", "one", 6_000)}},
            "170141184507":{"seal":${seal("170141184507")},"essay":${essay("~zod", "two", 7_000)}}}}}""")
        assertNotNull(db.messages().getOne(nest, "170141184506"))
        assertNotNull(db.messages().getOne(nest, "170141184507"))
        assertNull(db.messages().getOne(nest, "local_9"))
    }

    @Test
    fun `a channel post deleted outright or tombstoned is kept as deleted`() = runBlocking<Unit> {
        for ((id, rPost) in listOf("170141184506" to """{"set":null}""", "170141184507" to """{"set":{"type":"tombstone"}}""")) {
            db.messages().upsert(MessageEntity(nest, id, "~bus", 1_000, "[]", "/chat"))
            fact(channel(nest, id, rPost))
            assertTrue(db.messages().getOne(nest, id)!!.isDeleted, rPost)
        }
    }

    @Test
    fun `a channel's reactions are replaced wholesale, not added to`() = runBlocking<Unit> {
        val id = "170141184506"
        fact(channel(nest, id, """{"reacts":{"~nec":":fire:","~bus":"👍"}}"""))
        fact(channel(nest, id, """{"reacts":{"~bus":"👍"}}"""))
        assertNull(db.reactions().get(nest, id, "~nec"), "a withdrawn reaction goes")
        assertNotNull(db.reactions().get(nest, id, "~bus"))
    }

    @Test
    fun `an edited post takes its new text quietly`() = runBlocking<Unit> {
        val id = "170141184506"
        db.messages().upsert(MessageEntity(nest, id, "~bus", 1_000, """[{"inline":["old"]}]""", "/chat"))
        fact(channel(nest, id, """{"essay":${essay("~bus", "new words", 1_000)}}"""))
        val row = db.messages().getOne(nest, id)!!
        assertTrue("new words" in row.contentJson && "old" !in row.contentJson)
        assertTrue(heard.isEmpty(), "an edit is not a new message")
    }

    @Test
    fun `a channel reply is threaded, and replaced or deleted in place`() = runBlocking<Unit> {
        val parent = "170141184506"
        val reply = { rReply: String -> channel(nest, parent, """{"reply":{"id":"170.141.184.507","meta":null,"r-reply":$rReply}}""") }
        fact(reply("""{"set":{"seal":{"id":"170141184507","parent-id":"$parent","reacts":{}},"reply-essay":${essay("~bus", "a reply", 2_000)}}}"""))
        assertEquals(parent, db.messages().getOne(nest, "170141184507")?.parentId)
        fact(reply("""{"reacts":{"~nec":"👍"}}"""))
        assertNotNull(db.reactions().get(nest, "170141184507", "~nec"))
        fact(reply("""{"set":null}"""))
        assertTrue(db.messages().getOne(nest, "170141184507")!!.isDeleted)
        assertNull(db.reactions().get(nest, "170141184507", "~nec"))
    }

    @Test
    fun `pinning is the first id in the order, and an empty order unpins`() = runBlocking<Unit> {
        fact("""{"nest":"$nest","response":{"order":["170.141.184.506","170141184507"]}}""")
        assertEquals("170141184506", db.groups().pinnedPostIdFor(nest))
        fact("""{"nest":"$nest","response":{"order":[]}}""")
        assertNull(db.groups().pinnedPostIdFor(nest))
    }

    @Test
    fun `a fact nobody recognises changes nothing`() = runBlocking<Unit> {
        fact("""{"nest":"$nest","response":{"some-new-thing":{}}}""")
        fact("""{"something":"else"}""")
        repo.applyEvent(Json.parseToJsonElement("""{"id":3,"response":"poke","ok":"ok"}"""))
        assertFalse(db.messages().getOne(nest, "170141184506") != null)
        assertTrue(heard.isEmpty())
    }
}
