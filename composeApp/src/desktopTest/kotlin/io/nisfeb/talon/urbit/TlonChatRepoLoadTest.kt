package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What opening a conversation, scrolling back and opening a thread load
 * from the ship's scries, answered here by a [FakeShip]. Ships differ in
 * which versions of a path they answer, so each load tries several; a
 * ship answering only an older one still has to load.
 */
class TlonChatRepoLoadTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-load-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
    private val nest = "chat/~bus/general"

    @AfterTest
    fun close() = db.close()

    /** A real channel post with a reply and reactions, as a ship sent it. */
    private val post = javaClass.getResource("/fixtures/channels/post-with-reply-and-reacts.json")!!.readText()
    private val postId = "170141184507933044937549665940933705728"
    private val replyId = "170141184507933045432608980879542321152"

    private fun essay(author: String, text: String, sent: Long) =
        """{"content":[{"inline":["$text"]}],"author":"$author","sent":$sent,"kind":"/chat","blob":null,"meta":null}"""

    private fun seal(id: String) = """{"id":"$id","reacts":{},"replies":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}}"""

    @Test
    fun `opening a channel loads its newest page, replies and reactions included`() = runBlocking<Unit> {
        ship.scries["channels/v4/$nest/posts/newest/100/post"] = """{"posts":{"$postId":$post},"newer":null,"older":null}"""
        repo.refreshConversation(nest)
        assertTrue("hello channel" in db.messages().getOne(nest, postId)!!.contentJson)
        assertEquals(postId, db.messages().getOne(nest, replyId)?.parentId)
        assertNotNull(db.reactions().get(nest, postId, "~sampel-palnet"))
        assertNotNull(db.reactions().get(nest, replyId, "~sampel-palnet"))
    }

    @Test
    fun `a ship that only answers an older path still loads`() = runBlocking<Unit> {
        ship.scries["channels/v2/$nest/posts/newest/100/outline"] = """{"posts":{"$postId":$post}}"""
        repo.refreshConversation(nest)
        assertNotNull(db.messages().getOne(nest, postId))
        assertTrue(ship.scried.first().startsWith("channels/v4/"), "the newest version is asked first")
    }

    @Test
    fun `a DM page loads under writs, with ids stored undotted`() = runBlocking<Unit> {
        val id = "~bus/170.141.184.506"
        ship.scries["chat/v4/dm/~bus/writs/newest/100/heavy"] =
            """{"writs":{"$id":{"seal":${seal(id)},"essay":${essay("~bus", "from bus", 1_000)}}}}"""
        repo.refreshConversation("~bus")
        assertTrue("from bus" in db.messages().getOne("~bus", "~bus/170141184506")!!.contentJson)
    }

    @Test
    fun `a page holding our own post clears the grey copy we showed`() = runBlocking<Unit> {
        db.messages().upsert(MessageEntity(nest, "local_1", "~zod", 5_000, "[]", "/chat", status = "pending"))
        ship.scries["channels/v4/$nest/posts/newest/100/post"] =
            """{"posts":{"170141184506":{"seal":${seal("170141184506")},"essay":${essay("~zod", "mine", 5_000)}}}}"""
        repo.refreshConversation(nest)
        assertNull(db.messages().getOne(nest, "local_1"))
        assertNotNull(db.messages().getOne(nest, "170141184506"))
    }

    @Test
    fun `a ship that answers nothing leaves the conversation as it was, and says so`() = runBlocking<Unit> {
        db.messages().upsert(MessageEntity(nest, "170141184506", "~bus", 1_000, "[]", "/chat"))
        assertFailsWith<IllegalStateException>("not loaded is not empty") { repo.refreshConversation(nest) }
        assertEquals(listOf("170141184506"), db.messages().latestAnyFor(nest, 10).map { it.id })
    }

    @Test
    fun `a page the ship did not send is not the bottom`() = runBlocking<Unit> {
        db.messages().upsert(MessageEntity(nest, "170141184506", "~bus", 2_000, "[]", "/chat"))
        assertFailsWith<IllegalStateException> { repo.loadOlder(nest) }
        ship.scries["channels/v4/$nest/posts/older/170.141.184.506/30/post"] =
            """{"posts":{"170141184505":{"seal":${seal("170141184505")},"essay":${essay("~bus", "earlier", 1_000)}}},"older":null}"""
        assertFalse(repo.loadOlder(nest), "asked again, and this time the bottom")
        assertNotNull(db.messages().getOne(nest, "170141184505"))
    }

    @Test
    fun `scrolling back asks from the oldest id, dotted, and stops at the bottom`() = runBlocking<Unit> {
        db.messages().upsert(MessageEntity(nest, "170141184506", "~bus", 2_000, "[]", "/chat"))
        val older = "channels/v4/$nest/posts/older/170.141.184.506/30/post"
        ship.scries[older] =
            """{"posts":{"170141184505":{"seal":${seal("170141184505")},"essay":${essay("~bus", "earlier", 1_000)}}},"older":"170.141.184.504"}"""
        assertTrue(repo.loadOlder(nest), "the ship says there is more")
        assertNotNull(db.messages().getOne(nest, "170141184505"))

        ship.scries["channels/v4/$nest/posts/older/170.141.184.505/30/post"] = """{"posts":{},"older":null}"""
        assertFalse(repo.loadOlder(nest), "the bottom")
        val asked = ship.scried.size
        assertFalse(repo.loadOlder(nest))
        assertEquals(asked, ship.scried.size, "at the bottom it stops asking")
    }

    @Test
    fun `opening a thread fetches the post with its replies`() = runBlocking<Unit> {
        ship.scries["channels/v5/$nest/posts/post/${dotAtom(postId)}"] = post
        repo.fetchThread(nest, postId)
        assertNotNull(db.messages().getOne(nest, postId))
        assertEquals(postId, db.messages().getOne(nest, replyId)?.parentId)
    }

    @Test
    fun `a DM thread falls back to the older path`() = runBlocking<Unit> {
        val id = "~bus/170.141.184.506"
        ship.scries["chat/v3/dm/~bus/writs/writ/id/$id"] = """{"seal":${seal(id)},"essay":${essay("~bus", "parent", 1_000)}}"""
        repo.fetchThread("~bus", id)
        assertNotNull(db.messages().getOne("~bus", "~bus/170141184506"))
    }
}
