package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.FollowedThreadEntity
import io.nisfeb.talon.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
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
 * Following a thread, kept as Tlon keeps it: %activity's volume for the
 * thread source, its replies unread and notifying, or neither. Read from
 * the ship, changed by facts from other clients, written by Talon when
 * the owner follows, unfollows, replies or reacts; and a thread counts
 * (notifies) only when followed, or when it is a DM's, or the owner's own.
 */
class FollowedThreadsTest {
    private val dir = createTempDirectory(prefix = "talon-follow-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
    private val nest = "chat/~bus/general"
    private val parent = "170141184506"
    private val heard = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
        repo.messageListener = { m, _ -> heard += m.id }
    }

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

    private fun essay(author: String, text: String, sent: Long) =
        """{"content":[{"inline":["$text"]}],"author":"$author","sent":$sent,"kind":"/chat","blob":null,"meta":null}"""

    private suspend fun seed(parentAuthor: String = "~bus") {
        db.groups().upsertChannelGroups(listOf(ChannelGroupEntity(nest, "~bus/garden")))
        db.messages().upsert(MessageEntity(nest, parent, parentAuthor, 1_000, """[{"inline":["the post"]}]""", "/chat"))
    }

    private suspend fun fact(json: String) =
        repo.applyEvent(Json.parseToJsonElement("""{"id":1,"response":"diff","json":$json}"""))

    /** A reply from [author] in the channel thread, as %channels sends it. */
    private suspend fun reply(id: String, author: String, text: String) = fact(
        """{"nest":"$nest","response":{"post":{"id":"$parent","r-post":{"reply":{"id":"$id","meta":null,
            "r-reply":{"set":{"seal":{"id":"$id","parent-id":"$parent","reacts":{}},"reply-essay":${essay(author, text, 2_000)}}}}}}}}""",
    )

    private val threadSource = """{"thread":{"key":{"id":"~bus/170.141.184.506","time":"170.141.184.506"},"channel":"$nest","group":"~bus/garden"}}"""

    private fun row() = runBlocking { db.followedThreads().get(nest, parent) }

    // ─── wire ─────────────────────────────────────────────────────

    @Test
    fun `following tells the ship in Tlon's own words`() = live {
        seed()
        repo.setFollow(nest, parent, true)
        val poke = ship.pokesTo("activity").single()
        assertEquals("activity-action-2", poke.mark)
        assertEquals("""{"adjust":{"source":$threadSource,"volume":{"reply":{"unreads":true,"notify":true}}}}""", poke.json.toString())
        assertEquals(FollowedThreadEntity(nest, parent, follow = true, sent = true, atMs = row()!!.atMs), row())
    }

    @Test
    fun `unfollowing makes its replies neither unread nor notifying, and reads it`() = live {
        seed()
        repo.setFollow(nest, parent, false)
        val pokes = ship.pokesTo("activity")
        assertEquals("""{"adjust":{"source":$threadSource,"volume":{"reply":{"unreads":false,"notify":false}}}}""", pokes.first().json.toString())
        assertTrue(pokes.drop(1).any { "\"read\"" in it.json.toString() }, "and read, or its count stays in Tlon's apps: $pokes")
        assertEquals(false, row()?.follow)
    }

    @Test
    fun `a DM's thread is followed with its own event`() = live {
        db.messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["q"]}]""", "/chat"))
        repo.setFollow("~bus", "~bus/170141184506", false)
        assertEquals(
            """{"adjust":{"source":{"dm-thread":{"key":{"id":"~bus/170.141.184.506","time":"170.141.184.506"},"whom":{"ship":"~bus"}}},"volume":{"dm-reply":{"unreads":false,"notify":false}}}}""",
            ship.pokesTo("activity").first().json.toString(),
        )
    }

    // ─── from the ship ───────────────────────────────────────────

    @Test
    fun `the ship's settings are read, and a row it no longer names goes unless it is still on its way`() = runBlocking<Unit> {
        ship.scries["activity/v6/volume-settings"] = """{
            "base":{"reply":{"unreads":true,"notify":false}},
            "thread/$nest/170.141.184.506":{"reply":{"unreads":true,"notify":true}},
            "thread/$nest/170.141.184.999":{"reply":{"unreads":false,"notify":false}},
            "thread/$nest/170.141.184.777":{"reply":{"unreads":true,"notify":false}},
            "dm-thread/~bus/~bus/170.141.184.506":{"dm-reply":{"unreads":false,"notify":false}}}"""
        db.followedThreads().upsert(FollowedThreadEntity(nest, "1", follow = true, sent = true, atMs = 0))
        db.followedThreads().upsert(FollowedThreadEntity(nest, "2", follow = true, sent = false, atMs = 0))
        repo.bootstrapFollowedThreadsForTest()
        val rows = db.followedThreads().all().associate { it.parentPostId to it.follow }
        assertEquals(mapOf(parent to true, "170141184999" to false, "~bus/170141184506" to false, "2" to true), rows)
    }

    @Test
    fun `a follow from another client lands, and its clearing takes it away`() = runBlocking<Unit> {
        fact("""{"adjust":{"source":$threadSource,"volume":{"reply":{"unreads":true,"notify":true}}}}""")
        assertEquals(true, row()?.follow)
        fact("""{"adjust":{"source":$threadSource,"volume":{"reply":{"unreads":false,"notify":false}}}}""")
        assertEquals(false, row()?.follow)
        fact("""{"adjust":{"source":$threadSource,"volume":null}}""")
        assertNull(row())
    }

    // ─── failure ─────────────────────────────────────────────────

    @Test
    fun `a follow the ship refuses is taken back and says why`() = live {
        seed()
        ship.refuse = { if (it.app == "activity") "bad source" else null }
        assertFailsWith<PokeNacked> { repo.setFollow(nest, parent, true) }
        assertNull(row(), "nothing was there before")
    }

    @Test
    fun `a follow made while the ship is slow is kept and goes with the queued writes`() = live {
        seed()
        ship.lose = { IOException("The network connection was lost.") }
        repo.setFollow(nest, parent, true)
        assertEquals(FollowedThreadEntity(nest, parent, true, sent = false, atMs = row()!!.atMs), row())
        ship.lose = { null }
        repo.drainQueue()
        assertEquals(true, row()?.sent)
        assertTrue(ship.pokesTo("activity").any { "adjust" in it.json.toString() })
    }

    // Leaving the screen mid-write: the follow is carried on the repo's
    // scope and lands anyway. Real time, not a virtual clock.
    @Test
    fun `a follow finishes after the screen that asked for it is gone`() = live {
        seed()
        ship.holdPoke = 500
        val screen = CoroutineScope(SupervisorJob())
        screen.launch { runCatching { repo.setFollow(nest, parent, true) } }
        delay(50)
        screen.cancel()
        withTimeout(5_000) { while (row()?.sent != true) delay(20) }
        assertEquals(1, ship.pokesTo("activity").size)
    }

    // ─── joining a thread ────────────────────────────────────────

    @Test
    fun `a reply or a reaction in a channel thread follows it once, and an unfollow stands`() = live {
        seed()
        repo.react(nest, parent, "👍")
        withTimeout(5_000) { while (row()?.sent != true) delay(20) }
        repo.reply(nest, parent, "and me")
        delay(300)
        assertEquals(1, ship.pokesTo("activity").count { "adjust" in it.json.toString() }, "followed once")
        repo.setFollow(nest, parent, false)
        repo.react(nest, parent, "🔥")
        delay(300)
        assertEquals(false, row()?.follow, "the owner's unfollow stands")
    }

    // ─── what counts ─────────────────────────────────────────────

    @Test
    fun `a reply in a thread the owner is not in does not notify`() = runBlocking<Unit> {
        seed()
        reply("170.141.184.507", "~nec", "chatter")
        assertTrue(heard.isEmpty(), "$heard")
    }

    @Test
    fun `a reply notifies in a followed thread, the owner's own, or one naming them, and not once unfollowed`() = runBlocking<Unit> {
        seed(parentAuthor = "~zod")
        reply("170.141.184.507", "~nec", "to your post")
        assertEquals(listOf("170141184507"), heard.toList(), "the owner's own post")
        db.followedThreads().upsert(FollowedThreadEntity(nest, parent, follow = false, sent = true, atMs = 1))
        reply("170.141.184.508", "~nec", "after unfollowing")
        assertEquals(1, heard.size, "unfollowed: not even the owner's own")
        db.followedThreads().delete(nest, parent)
        db.messages().upsert(MessageEntity(nest, parent, "~bus", 1_000, """[{"inline":["the post"]}]""", "/chat"))
        reply("170.141.184.509", "~nec", "hey \", {\"ship\":\"~zod\"}, \"look")
        assertEquals("170141184509", heard.last(), "a mention")
        db.followedThreads().upsert(FollowedThreadEntity(nest, parent, follow = true, sent = true, atMs = 1))
        reply("170.141.184.510", "~nec", "followed")
        assertEquals("170141184510", heard.last())
    }

    @Test
    fun `a DM's thread notifies unless unfollowed`() = runBlocking<Unit> {
        val p = "~bus/170141184506"
        fact("""{"whom":"~bus","id":"$p","response":{"add":{"essay":${essay("~bus", "q", 1_000)},"time":null}}}""")
        heard.clear()
        fact("""{"whom":"~bus","id":"$p","response":{"reply":{"id":"~bus/170.141.184.507","meta":null,"delta":{"add":{"reply-essay":${essay("~bus", "a", 2_000)},"time":null}}}}}""")
        assertEquals(listOf("~bus/170141184507"), heard.toList())
        db.followedThreads().upsert(FollowedThreadEntity("~bus", p, follow = false, sent = true, atMs = 1))
        fact("""{"whom":"~bus","id":"$p","response":{"reply":{"id":"~bus/170.141.184.508","meta":null,"delta":{"add":{"reply-essay":${essay("~bus", "b", 3_000)},"time":null}}}}}""")
        assertEquals(1, heard.size)
    }
}
