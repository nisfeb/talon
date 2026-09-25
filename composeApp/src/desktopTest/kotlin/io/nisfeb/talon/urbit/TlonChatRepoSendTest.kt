package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.data.ThreadUnreadEntity
import io.nisfeb.talon.data.UnreadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * What each action sends to the ship and what it shows before the ship
 * answers. Every poke here is acked by a [FakeShip] unless the test has
 * it refuse, which is how a refusal is shown to leave the right rows.
 */
class TlonChatRepoSendTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-send-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }

    private val nest = "chat/~bus/general"
    private val flag = "~bus/general"

    @AfterTest
    fun close() = db.close()

    /** Runs [body] with the event stream open, so pokes get their acks. */
    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private fun JsonElement.at(vararg keys: String): JsonElement =
        keys.fold(this) { e, k -> e.jsonObject[k] ?: error("no $k in $e") }

    private fun refuse(app: String) { ship.refuse = { if (it.app == app) "nope" else null } }

    private suspend fun seedChannel() {
        db.groups().upsertGroups(listOf(GroupEntity(flag, "Bus", null)))
        db.groups().upsertChannelGroups(listOf(ChannelGroupEntity(nest, flag, title = "general")))
    }

    // ─── sending ──────────────────────────────────────────────────

    @Test
    fun `a channel post shows at once as sending and goes to the channel`() = live {
        val id = repo.send(nest, "hello channel")
        assertTrue(id.startsWith("local_"), "the ship mints the real id; ours is a stand-in")
        assertEquals("pending", db.messages().getOne(nest, id)?.status)
        val poke = ship.pokesTo("channels").single()
        assertEquals("channel-action-2", poke.mark)
        assertEquals(nest, poke.json.at("channel", "nest").jsonPrimitive.content)
        val essay = poke.json.at("channel", "action", "post", "add")
        assertEquals("~zod", essay.at("author").jsonPrimitive.content)
        assertTrue("hello channel" in essay.at("content").toString())
    }

    @Test
    fun `a DM goes to chat under our own id with no sending state`() = live {
        val id = repo.send("~bus", "hi")
        assertTrue(id.startsWith("~zod/"), id)
        assertNull(db.messages().getOne("~bus", id)?.status)
        val poke = ship.pokesTo("chat").single()
        assertEquals("chat-dm-action-2", poke.mark)
        assertEquals("~bus", poke.json.at("ship").jsonPrimitive.content)
        assertTrue("hi" in poke.json.at("diff", "delta", "add", "essay", "content").toString())
    }

    @Test
    fun `a message to a group DM goes to the club`() = live {
        repo.send("0v4.abcde", "all of you")
        val poke = ship.pokesTo("chat").single()
        assertEquals("chat-club-action-2", poke.mark)
        assertEquals("0v4.abcde", poke.json.at("id").jsonPrimitive.content)
    }

    @Test
    fun `a refused channel post is marked failed rather than left sending`() = live {
        refuse("channels")
        assertFailsWith<PokeNacked> { repo.send(nest, "no") }
        val row = db.messages().latestAnyFor(nest, 10).single()
        assertEquals("failed", row.status)
    }

    @Test
    fun `a refused DM is marked failed rather than looking sent`() = live {
        refuse("chat")
        assertFailsWith<PokeNacked> { repo.send("~bus", "no") }
        assertEquals("failed", db.messages().latestAnyFor("~bus", 10).single().status)
    }

    @Test
    fun `a refused channel reply is marked failed`() = live {
        refuse("channels")
        assertFailsWith<PokeNacked> { repo.reply(nest, "170141184506", "no") }
        val reply = db.messages().latestAnyFor(nest, 10).single()
        assertEquals("170141184506", reply.parentId)
        assertEquals("failed", reply.status)
    }

    @Test
    fun `a refused DM reply is marked failed, though its id is dotted on the wire`() = live {
        refuse("chat")
        assertFailsWith<PokeNacked> { repo.reply("~bus", "~bus/170141184506", "no") }
        assertEquals("failed", db.messages().latestAnyFor("~bus", 10).single().status)
    }

    // ─── groups ───────────────────────────────────────────────────

    @Test
    fun `leaving a group the ship confirms drops it from the list at once`() = live {
        seedChannel()
        repo.leaveGroup(flag)
        assertEquals("group-leave", ship.pokesTo("groups").single().mark)
        assertNull(db.groups().getGroup(flag), "the leaver gets no delete fact, so it goes here")
        assertNull(db.groups().channelGroupFor(nest))
    }

    @Test
    fun `a refused leave keeps the group, which is still ours`() = live {
        seedChannel()
        refuse("groups")
        assertFailsWith<PokeNacked> { repo.leaveGroup(flag) }
        assertNotNull(db.groups().getGroup(flag))
        assertNotNull(db.groups().channelGroupFor(nest))
    }

    // ─── reactions, deletes, pins ─────────────────────────────────

    @Test
    fun `taking a reaction back is shown at once, and a refusal puts it back`() = live {
        val mine = ReactionEntity("~bus", "~bus/170141184506", "~zod", "👍")
        db.reactions().upsert(mine)
        repo.unreact("~bus", "~bus/170141184506")
        assertNull(db.reactions().get("~bus", "~bus/170141184506", "~zod"))
        assertEquals("~zod", ship.pokesTo("chat").single().json.at("diff", "delta", "del-react").jsonPrimitive.content)

        db.reactions().upsert(mine)
        refuse("chat")
        assertFailsWith<PokeNacked> { repo.unreact("~bus", "~bus/170141184506") }
        assertNotNull(db.reactions().get("~bus", "~bus/170141184506", "~zod"))
    }

    @Test
    fun `a reaction is shown at once and goes to each kind of chat in its own shape`() = live {
        repo.react("~bus", "~bus/170141184506", ":+1:")
        repo.react("0v4.club", "~nec/170141184507", "👍")
        repo.react(nest, "170141184508", "👍")
        assertEquals("👍", db.reactions().get("~bus", "~bus/170141184506", "~zod")?.emoji?.let(io.nisfeb.talon.ui.ReactionPalette::normalize))
        val dm = ship.pokesTo("chat").first { it.mark == "chat-dm-action-2" }.json
        assertEquals("~zod", dm.at("diff", "delta", "add-react", "author").jsonPrimitive.content)
        assertTrue(ship.pokesTo("chat").any { it.mark == "chat-club-action-2" })
        // %channels wants `ship`, not `author`, and the id dotted.
        val chan = ship.pokesTo("channels").single().json.at("channel", "action", "post", "add-react")
        assertEquals("~zod" to "170.141.184.508", chan.at("ship").jsonPrimitive.content to chan.at("id").jsonPrimitive.content)
    }

    @Test
    fun `a changed reaction the ship refuses goes back to the one before`() = live {
        db.reactions().upsert(ReactionEntity("~bus", "~bus/170141184506", "~zod", "❤"))
        refuse("chat")
        assertFailsWith<PokeNacked> { repo.react("~bus", "~bus/170141184506", "👍") }
        assertEquals("❤", db.reactions().get("~bus", "~bus/170141184506", "~zod")?.emoji)
        db.reactions().delete("~bus", "~bus/170141184506", "~zod")
        assertFailsWith<PokeNacked> { repo.react("~bus", "~bus/170141184506", "👍") }
        assertNull(db.reactions().get("~bus", "~bus/170141184506", "~zod"), "a first reaction refused leaves none")
    }

    @Test
    fun `a reply is deleted through its parent, in a DM, a group DM and a channel`() = live {
        repo.delete("~bus", "~zod/170141184507", parentId = "~bus/170141184506")
        repo.delete("0v4.club", "~zod/170141184507", parentId = "~nec/170141184506")
        repo.delete(nest, "170141184507", parentId = "170141184506")
        val dm = ship.pokesTo("chat").first { it.mark == "chat-dm-action-2" }.json.at("diff", "delta", "reply")
        assertEquals("~zod/170.141.184.507", dm.at("id").jsonPrimitive.content)
        assertTrue(ship.pokesTo("chat").any { it.mark == "chat-club-action-2" && "\"reply\"" in it.json.toString() })
        val chan = ship.pokesTo("channels").single().json.at("channel", "action", "post", "reply")
        assertEquals("170.141.184.506" to "170.141.184.507", chan.at("id").jsonPrimitive.content to chan.at("action", "del").jsonPrimitive.content)
    }

    @Test
    fun `deleting a DM hides it at once, a channel post waits for the ship`() = live {
        db.messages().upsert(MessageEntity("~bus", "~zod/170141184506", "~zod", 1, "[]", "/chat"))
        db.messages().upsert(MessageEntity(nest, "170141184507", "~zod", 2, "[]", "/chat"))
        repo.delete("~bus", "~zod/170141184506")
        repo.delete(nest, "170141184507")
        assertTrue(db.messages().getOne("~bus", "~zod/170141184506")!!.isDeleted)
        assertTrue(!db.messages().getOne(nest, "170141184507")!!.isDeleted, "the channel's own echo deletes it")
        assertEquals(JsonNull, ship.pokesTo("chat").single().json.at("diff", "delta", "del"))
        assertEquals(
            "170.141.184.507",
            ship.pokesTo("channels").single().json.at("channel", "action", "post", "del").jsonPrimitive.content,
        )
    }

    @Test
    fun `pinning writes the order the ship reads, and a refusal restores the old pin`() = live {
        seedChannel()
        repo.pinPost(nest, "170141184506")
        assertEquals("170141184506", db.groups().pinnedPostIdFor(nest))
        val order = ship.pokesTo("channels").single().json.at("channel", "action", "order").jsonArray
        assertEquals(listOf("170.141.184.506"), order.map { it.jsonPrimitive.content })

        refuse("channels")
        assertFailsWith<PokeNacked> { repo.pinPost(nest, "170141184507") }
        assertEquals("170141184506", db.groups().pinnedPostIdFor(nest), "the refused pin is undone")
        assertFailsWith<PokeNacked> { repo.unpinPost(nest) }
        assertEquals("170141184506", db.groups().pinnedPostIdFor(nest), "and so is a refused unpin")
    }

    @Test
    fun `pinning what is already pinned sends nothing`() = live {
        seedChannel()
        db.groups().setPinnedPostId(nest, "170141184506")
        repo.pinPost(nest, "170141184506")
        assertTrue(ship.pokes.isEmpty())
    }

    // ─── reading ──────────────────────────────────────────────────

    @Test
    fun `opening a conversation clears its badge at once and tells activity`() = live {
        db.unreads().upsert(UnreadEntity("~bus", count = 3, notifyCount = 1, recencyMs = 1))
        repo.markRead("~bus")
        val row = db.unreads().getOne("~bus")!!
        assertEquals(0 to 0, row.count to row.notifyCount)
        assertEquals("activity-action", ship.pokesTo("activity").single().mark)
    }

    @Test
    fun `a channel with no known group is cleared here but not poked`() = live {
        db.unreads().upsert(UnreadEntity(nest, count = 2, notifyCount = 0, recencyMs = 1))
        repo.markRead(nest)
        assertEquals(0, db.unreads().getOne(nest)!!.count)
        assertTrue(ship.pokesTo("activity").isEmpty(), "a read with no group would be refused")
    }

    @Test
    fun `opening a thread clears its own unread and tells activity`() = live {
        db.threadUnreads().upsert(ThreadUnreadEntity("~bus", "~bus/170141184506", 2, 0, 1))
        repo.markThreadRead("~bus", "~bus/170141184506")
        assertNull(db.threadUnreads().getOne("~bus", "~bus/170141184506"))
        assertEquals(1, ship.pokesTo("activity").size)
    }
}
