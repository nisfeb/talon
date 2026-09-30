package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A ship that is slow or out of reach is not one that refused: what was
 * written waits and goes when it answers again, once, and only a
 * refusal fails. During an app release the ship timed out writes that
 * often landed, and the app said "react failed: Request timeout has
 * expired [url=…]" and marked messages failed that never went again.
 */
class TlonChatRepoQueueTest {
    private val dir = createTempDirectory(prefix = "talon-queue-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
    private val nest = "chat/~bus/general"
    private val lost = IOException("The network connection was lost.")

    @AfterTest
    fun close() {
        runBlocking { repo.stopAndJoinForTest() }
        db.close()
        dir.deleteRecursively()
    }

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    /** A DM's id as the database keeps it: undotted. */
    private fun stored(id: String) = id.replace(".", "")

    private fun seal(id: String) = """{"id":"$id","reacts":{},"replies":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}}"""
    private fun essay(text: String, sent: Long) =
        """{"content":[{"inline":["$text"]}],"author":"~zod","sent":$sent,"kind":"/chat","blob":null,"meta":null}"""

    @Test
    fun `a DM sent while the connection drops is queued, not failed, and goes once when the ship is back`() = live {
        ship.lose = { lost }
        val id = repo.send("~bus", "are you there")
        assertEquals("queued", db.messages().getOne("~bus", stored(id))?.status)
        val slow = repo.shipSlow.first { it != null }!!
        assertEquals(1, slow.queued)
        assertTrue("The network connection was lost." in slow.details, slow.details)

        ship.lose = { null }
        repo.drainQueue()
        assertNull(db.messages().getOne("~bus", stored(id))?.status, "sent: no longer waiting")
        val sent = ship.pokesTo("chat").single()
        assertEquals(ship.attempted.first().json, sent.json, "the resend is the send, byte for byte")
        assertNull(repo.shipSlow.first(), "nothing waits")
    }

    @Test
    fun `a reply, a club message and a channel post go again exactly as first sent`() = live {
        ship.lose = { lost }
        repo.send("0v4.abcde", "all of you")
        repo.reply("~bus", "~bus/170141184507933044937549665940933705728", "in the thread")
        repo.send(nest, "in the channel")
        val first = ship.attempted.map { it.json }
        ship.lose = { null }
        // The channel's newest posts, asked for before its post goes again: not there.
        ship.scries["channels/v4/$nest/posts/newest/30/post"] = """{"posts":{}}"""
        repo.drainQueue()
        assertEquals(first.map { it.toString() }.joinToString("\n"), ship.pokes.map { it.json.toString() }.joinToString("\n"), "oldest first, and as they were")
    }

    @Test
    fun `a channel post that landed although its write timed out is not posted twice`() = live {
        ship.landThenFail = { lost }
        val id = repo.send(nest, "landed anyway")
        val row = db.messages().getOne(nest, id)!!
        assertEquals("queued", row.status)
        ship.landThenFail = { null }
        // The ship has it: its newest posts carry ours, sent at that moment.
        ship.scries["channels/v4/$nest/posts/newest/30/post"] =
            """{"posts":{"170141184506":{"seal":${seal("170141184506")},"essay":${essay("landed anyway", row.sentMs)}}}}"""
        repo.drainQueue()
        assertEquals(1, ship.pokesTo("channels").size, "posted once")
        assertNull(db.messages().getOne(nest, id), "the stand-in went, as an echo takes it")
        assertNotNull(db.messages().getOne(nest, "170141184506"))
    }

    @Test
    fun `a channel post that did not land goes again, and waits for its echo`() = live {
        ship.lose = { lost }
        val id = repo.send(nest, "second try")
        ship.lose = { null }
        ship.scries["channels/v4/$nest/posts/newest/30/post"] = """{"posts":{}}"""
        repo.drainQueue()
        assertEquals(1, ship.pokesTo("channels").size)
        assertEquals("pending", db.messages().getOne(nest, id)?.status)
    }

    // Unable to ask the ship whether it landed is not a no: posted twice
    // is worse than posted late.
    @Test
    fun `a channel post is not sent again while the ship cannot say whether it has it`() = live {
        ship.lose = { lost }
        val id = repo.send(nest, "wait and see")
        ship.lose = { null }
        repo.drainQueue()
        assertTrue(ship.pokesTo("channels").isEmpty())
        assertEquals("queued", db.messages().getOne(nest, id)?.status)
    }

    @Test
    fun `a ship that still does not answer keeps it queued, and a refusal still fails`() = live {
        ship.lose = { lost }
        val id = repo.send("~bus", "one")
        repo.drainQueue()
        assertEquals("queued", db.messages().getOne("~bus", stored(id))?.status)
        ship.lose = { null }
        ship.refuse = { "nope" }
        assertFailsWith<PokeNacked> { repo.send("~bus", "refused") }
        repo.drainQueue()
        assertEquals("failed", db.messages().getOne("~bus", stored(id))?.status, "refused once it reached the ship")
    }

    @Test
    fun `a queued message outlasts the app, and the next one sends it`() = live {
        ship.lose = { lost }
        val id = repo.send("~bus", "before a restart")
        ship.lose = { null }
        val again = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        try {
            again.drainQueue()
            assertNull(db.messages().getOne("~bus", stored(id))?.status)
            assertEquals(1, ship.pokesTo("chat").size)
        } finally {
            again.stopAndJoinForTest()
        }
    }

    @Test
    fun `opening the channel does not throw away a message waiting for the ship`() = live {
        db.messages().upsert(MessageEntity(nest, "local_9", "~zod", 9_000, "[]", "/chat", status = "queued"))
        ship.scries["channels/v4/$nest/posts/newest/100/post"] = """{"posts":{}}"""
        repo.refreshConversation(nest)
        assertEquals("queued", db.messages().getOne(nest, "local_9")?.status)
    }

    @Test
    fun `a reaction made while the ship is out of reach stays shown, and goes when it is back`() = live {
        db.messages().upsert(MessageEntity("~bus", "~bus/170141184507933044937549665940933705728", "~bus", 1_000, "[]", "/chat"))
        ship.lose = { lost }
        repo.react("~bus", "~bus/170141184507933044937549665940933705728", "👍")
        assertNotNull(db.reactions().get("~bus", "~bus/170141184507933044937549665940933705728", "~zod"), "not rolled back")
        assertEquals(1, repo.shipSlow.first { it != null }!!.queued)
        ship.lose = { null }
        repo.drainQueue()
        assertTrue(ship.pokesTo("chat").single().json.toString().contains("add-react"))
        assertNull(repo.shipSlow.first())
    }

    @Test
    fun `a reaction taken off again while queued sends only the taking off`() = live {
        val post = "~bus/170141184507933044937549665940933705728"
        ship.lose = { lost }
        repo.react("~bus", post, "👍")
        repo.unreact("~bus", post)
        ship.lose = { null }
        repo.drainQueue()
        val sent = ship.pokesTo("chat").map { it.json.toString() }
        assertEquals(1, sent.size, "$sent")
        assertTrue("del-react" in sent.single())
    }
}
