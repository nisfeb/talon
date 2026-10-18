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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.io.IOException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
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

    // A call is recorded only once its peer has been told in the chat: a
    // notice the ship refused, or one waiting in the queue, is no notice.
    @Test
    fun `a confirmed send says whether the ship took it`() = live {
        assertTrue(repo.sendConfirmed("~bus", "Recording this call."))
        assertTrue("Recording this call." in ship.pokesTo("chat").single().json.toString())
        ship.refuse = { if (it.app == "chat") "no" else null }
        assertFalse(repo.sendConfirmed("~bus", "Recording this call."), "refused")
        ship.refuse = { null }
        ship.lose = { lost }
        assertFalse(repo.sendConfirmed("~bus", "Recording this call."), "out of reach, so queued")
        ship.lose = { null }
        assertFalse(repo.sendConfirmed("~bus", "Recording this call."), "behind one still queued, it waits too")
    }

    // "Slow" covered a ship mid-event, one down behind its proxy and one
    // out of reach. Vere's healthz tells them apart for nothing: the
    // runtime answers it, so the ship works no event.
    @Test
    fun `a write that waits says whether the ship is busy, down or out of reach`() = live {
        ship.lose = { lost }
        ship.health = 429
        repo.send("~bus", "are you there")
        val busy = kotlinx.coroutines.withTimeout(5_000) { repo.shipSlow.first { it?.health != null }!! }
        assertEquals(ShipHealth.BUSY, busy.health)
        assertEquals("Your ship is busy. 1 queued for when it catches up.", io.nisfeb.talon.util.shipSlowLine(busy.queued, busy.health))
        assertTrue("healthz: BUSY" in busy.details, busy.details)
        assertTrue(ship.requests.any { it == "GET /~_~/healthz" }, "${ship.requests}")

        ship.health = 502
        repo.drainQueue()
        assertEquals(ShipHealth.DOWN, kotlinx.coroutines.withTimeout(5_000) { repo.shipSlow.first { it?.health == ShipHealth.DOWN } }!!.health)

        ship.lose = { null }
        repo.drainQueue()
        assertNull(repo.shipSlow.first(), "nothing waits")
    }

    @Test
    fun `a reply, a club message and a channel post go again exactly as first sent`() = live {
        ship.lose = { lost }
        repo.send("0v4.abcde", "all of you")
        repo.reply("~bus", "~bus/170141184507933044937549665940933705728", "in the thread")
        repo.send(nest, "in the channel")
        // The messages alone: replying in the thread also follows it, a
        // poke of its own to %activity, which FollowedThreadsTest covers.
        val first = ship.attempted.filter { it.app != "activity" }.map { it.json }
        ship.lose = { null }
        // The channel's newest posts, asked for before its post goes again: not there.
        ship.scries["channels/v4/$nest/posts/newest/30/post"] = """{"posts":{}}"""
        repo.drainQueue()
        assertEquals(
            first.map { it.toString() }.joinToString("\n"),
            ship.pokes.filter { it.app != "activity" }.map { it.json.toString() }.joinToString("\n"),
            "oldest first, and as they were",
        )
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

    // The guard read the channel's newest thirty: a post that landed
    // further back than that, the ship slow and the channel busy, went
    // twice (review M17). It reads from the post's own time now.
    @Test
    fun `a queued channel post that landed thirty posts back is not posted twice`() = live {
        ship.landThenFail = { lost }
        val id = repo.send(nest, "landed long ago")
        val row = db.messages().getOne(nest, id)!!
        ship.landThenFail = { null }
        val later = (1..30).joinToString(",") { n ->
            val pid = "${170141184600L + n}"
            """"$pid":{"seal":${seal(pid)},"essay":${essay("later $n", row.sentMs + n * 1_000L).replace("~zod", "~bus")}}"""
        }
        ship.scries["channels/v4/$nest/posts/newest/30/post"] = """{"posts":{$later}}"""
        // Wire: %channels' newer/<start>/<count>/<mode>, start a dotted @ud
        // of the time a minute before ours.
        val from = UrbitTime.daToUd(UrbitTime.unixMsToDa(row.sentMs - 60_000L))
        ship.scries["channels/v4/$nest/posts/newer/$from/30/post"] =
            """{"posts":{"170141184506":{"seal":${seal("170141184506")},"essay":${essay("landed long ago", row.sentMs)}}}}"""
        repo.drainQueue()
        assertEquals(1, ship.pokesTo("channels").size, "posted once")
        assertNull(db.messages().getOne(nest, id), "the stand-in went, as an echo takes it")
        assertEquals("channels/v4/$nest/posts/newer/$from/30/post", ship.scried.first { "/posts/" in it })
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
        // A conversation with nothing waiting hears the refusal at once.
        assertFailsWith<PokeNacked> { repo.send("~nec", "refused") }
        // One behind the queued message waits with it, and hears it there.
        // Held, so the drain it wakes has not yet heard back.
        ship.holdPoke = 300
        val behind = repo.send("~bus", "behind")
        assertEquals("queued", db.messages().getOne("~bus", stored(behind))?.status)
        repo.drainQueue()
        assertEquals("failed", db.messages().getOne("~bus", stored(id))?.status, "refused once it reached the ship")
        assertEquals("failed", db.messages().getOne("~bus", stored(behind))?.status)
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

    // Nor one refused ("Not sent") or still in flight: the purge kept only
    // queued rows, so a refusal vanished on the next refresh (review M1).
    @Test
    fun `opening the channel does not throw away a message waiting for the ship`() = live {
        db.messages().upsert(MessageEntity(nest, "local_9", "~zod", 9_000, "[]", "/chat", status = "queued"))
        db.messages().upsert(MessageEntity(nest, "local_10", "~zod", 10_000, "[]", "/chat", status = "failed"))
        db.messages().upsert(MessageEntity(nest, "local_11", "~zod", 11_000, "[]", "/chat", status = "pending"))
        ship.scries["channels/v4/$nest/posts/newest/100/post"] = """{"posts":{}}"""
        repo.refreshConversation(nest)
        assertEquals(listOf("queued", "failed", "pending"), listOf("local_9", "local_10", "local_11").map { db.messages().getOne(nest, it)?.status })
    }

    @Test
    fun `a post refused by the ship still says so after the channel is read again`() = live {
        ship.refuse = { if (it.app == "channels") "no" else null }
        assertFailsWith<PokeNacked> { repo.send(nest, "refused") }
        val id = db.messages().before(nest, Long.MAX_VALUE, 5).single().id
        assertEquals("failed", db.messages().getOne(nest, id)?.status)
        ship.scries["channels/v4/$nest/posts/newest/100/post"] = """{"posts":{}}"""
        repo.refreshConversation(nest)
        assertEquals("failed", db.messages().getOne(nest, id)?.status, "still shown as not sent")
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

    // "the UI said one message was queued. I sent another. the ui never
    // updated to say 2 messages were queued even though the second message
    // remained greyed out."
    @Test
    fun `one sent while another of its conversation waits is queued behind it, counted, and goes after it`() = live {
        ship.lose = { lost }
        val first = repo.send("~bus", "first")
        // Still out of reach: the drain the second wakes does not empty the queue.
        val second = repo.send("~bus", "second")
        assertEquals("queued", db.messages().getOne("~bus", stored(second))?.status)
        assertEquals(2, repo.shipSlow.first { (it?.queued ?: 0) == 2 }!!.queued)
        ship.lose = { null }
        repo.drainQueue()
        assertEquals(listOf(null, null), listOf(first, second).map { db.messages().getOne("~bus", stored(it))?.status })
        val sent = ship.pokesTo("chat").map { it.json.toString() }
        assertEquals(2, sent.size, sent.toString())
        assertTrue("first" in sent[0] && "second" in sent[1], "in the order written: $sent")
    }

    // "the first message lost its queued text but remained gray for over a
    // minute before turning white."
    @Test
    fun `a queued message keeps its label while it goes again, until the ship takes it`() = live {
        ship.lose = { lost }
        val id = repo.send("~bus", "are you there")
        ship.lose = { null }
        ship.holdPoke = 800
        val draining = launch(kotlinx.coroutines.Dispatchers.Default) { repo.drainQueue() }
        delay(300)
        assertEquals("queued", db.messages().getOne("~bus", stored(id))?.status, "still waiting on the ship")
        draining.join()
        assertNull(db.messages().getOne("~bus", stored(id))?.status)
    }

    @Test
    fun `one queued while the queue goes out goes with it, not at the next try`() = live {
        ship.lose = { lost }
        val first = repo.send("~bus", "first")
        ship.lose = { null }
        ship.holdPoke = 600
        val draining = launch(kotlinx.coroutines.Dispatchers.Default) { repo.drainQueue() }
        delay(200)
        val second = repo.send("~bus", "second") // behind the first, still going
        draining.join()
        assertEquals(listOf(null, null), listOf(first, second).map { db.messages().getOne("~bus", stored(it))?.status })
    }

    // A DM in flight had no state: it looked sent, and one cut off by the
    // app being killed was never sent again (review A1-6).
    @Test
    fun `a DM or a DM reply is sending until the ship takes it`() = live {
        ship.holdPoke = 600
        val sending = async(kotlinx.coroutines.Dispatchers.Default) { repo.send("~bus", "on its way") to repo.reply("0v4.abcde", "~bus/170141184506", "in the thread") }
        delay(250)
        val first = db.messages().latestAnyFor("~bus", 5).single()
        assertEquals("pending", first.status, "on the wire")
        val (dm, reply) = sending.await()
        assertNull(db.messages().getOne("~bus", stored(dm))?.status, "taken")
        assertNull(db.messages().getOne("0v4.abcde", stored(reply))!!.status)
        assertEquals(2, ship.pokesTo("chat").size)
    }

    // What the last run left queued said "Your ship is slow. 1 queued"
    // from launch, before anyone had asked the ship (review A1-9).
    @Test
    fun `what the last run left queued is not called slow until the ship is asked`() = live {
        db.messages().upsert(MessageEntity("~bus", "~zod/170141184506", "~zod", 1_000, """[{"inline":["left over"]}]""", "/chat", status = "queued"))
        assertNull(repo.shipSlow.first(), "nobody has asked the ship yet")
        ship.lose = { lost }
        repo.drainQueue()
        assertEquals(1, repo.shipSlow.first { it != null }!!.queued, "asked, and it did not answer")
        ship.lose = { null }
        repo.drainQueue()
        assertNull(repo.shipSlow.first())
        assertEquals(1, ship.pokesTo("chat").size)
    }

    // ─── deleting what the ship never took (review A4-1) ─────────────
    // A queued DM deleted here was hidden, and the drain sent it anyway
    // when the ship came back; with the ship out of reach the delete
    // threw, so it could not be withdrawn at all.

    @Test
    fun `a queued DM deleted while the ship is away is never sent`() = live {
        ship.lose = { lost }
        val id = repo.send("~bus", "take this back")
        assertEquals("queued", db.messages().getOne("~bus", stored(id))?.status)
        repo.delete("~bus", stored(id))
        assertNull(db.messages().getOne("~bus", stored(id)), "gone here")
        assertEquals(0, db.messages().queuedCount().first())
        ship.lose = { null }
        repo.drainQueue()
        delay(300)
        assertTrue(ship.pokesTo("chat").isEmpty(), "nothing sent: ${ship.pokesTo("chat")}")
        assertEquals(1, ship.attempted.count { it.app == "chat" }, "the first try only")
        assertNull(repo.shipSlow.first())
    }

    @Test
    fun `a queued message is deleted with no connection to the ship at all`() = runBlocking<Unit> {
        val offline = TlonChatRepo(db)
        try {
            db.messages().upsert(MessageEntity("~bus", "~zod/170141184506", "~zod", 1_000, "[]", "/chat", status = "queued"))
            offline.delete("~bus", "~zod/170141184506")
            assertNull(db.messages().getOne("~bus", "~zod/170141184506"))
        } finally {
            offline.stopAndJoinForTest()
        }
    }

    // A refused post said "Not sent" for good: its delete sent the ship
    // its stand-in id, which the ship refused in turn (review A4-3, A5-13).
    @Test
    fun `a refused channel post or notebook entry is deleted here and nothing goes to the ship`() = live {
        ship.refuse = { if (it.app == "channels") "no" else null }
        assertFailsWith<PokeNacked> { repo.send(nest, "refused") }
        assertFailsWith<PokeNacked> { repo.sendNotebookPost("diary/~bus/notes", "Title", "", "body") }
        val refused = db.messages().latestAnyFor(nest, 5).single() to db.messages().latestAnyFor("diary/~bus/notes", 5).single()
        ship.refuse = { null }
        repo.delete(nest, refused.first.id)
        repo.delete("diary/~bus/notes", refused.second.id)
        assertNull(db.messages().getOne(nest, refused.first.id))
        assertNull(db.messages().getOne("diary/~bus/notes", refused.second.id))
        assertEquals(2, ship.pokesTo("channels").size, "the two refused posts, and no delete")
    }

    @Test
    fun `a channel post still sending is deleted here and its stand-in id never reaches the ship`() = live {
        db.messages().upsert(MessageEntity(nest, "local_170141184506", "~zod", 1_000, "[]", "/chat", status = "pending"))
        repo.delete(nest, "local_170141184506")
        assertNull(db.messages().getOne(nest, "local_170141184506"))
        assertTrue(ship.attempted.isEmpty(), "${ship.attempted}")
    }

    // The drain re-reads it as it goes: one deleted while its send was on
    // the wire is taken back, a DM naming itself.
    @Test
    fun `a queued DM deleted while it goes out is taken back on the ship`() = live {
        ship.lose = { lost }
        val id = repo.send("~bus", "too late")
        ship.lose = { null }
        ship.holdPoke = 600
        val draining = launch(kotlinx.coroutines.Dispatchers.Default) { repo.drainQueue() }
        delay(200)
        repo.delete("~bus", stored(id))
        draining.join()
        val sent = ship.pokesTo("chat").map { it.json.toString() }
        assertEquals(2, sent.size, "$sent")
        assertTrue("too late" in sent[0])
        assertTrue("\"del\":null" in sent[1] && stored(id).substringAfter('/') in sent[1].replace(".", ""), sent[1])
    }

    // ─── what the queue may cost a slow ship (rc9 felt slower) ───────

    @Test
    fun `a channel post queued behind another was never sent, so the channel is not read to ask`() = live {
        ship.lose = { lost }
        repo.send(nest, "first")
        ship.lose = { null }
        ship.scries["channels/v4/$nest/posts/newest/30/post"] = """{"posts":{}}"""
        repo.send(nest, "second")
        repo.drainQueue()
        assertEquals(2, ship.pokesTo("channels").size)
        assertEquals(1, ship.scried.count { it == "channels/v4/$nest/posts/newest/30/post" }, "asked for the first only: ${ship.scried}")
    }

    // Queued behind the first, it never tried the ship, and the drain
    // asleep in its backoff did not wake for it: back, the ship sat
    // unasked for the rest of a backoff of up to ninety seconds.
    @Test
    fun `one written behind a queued one asks the ship at once`() = live {
        ship.lose = { lost }
        val first = repo.send("~bus", "first")
        ship.lose = { null }
        val wroteAt = System.currentTimeMillis()
        val second = repo.send("~bus", "second")
        kotlinx.coroutines.withTimeout(5_000) {
            while (listOf(first, second).any { db.messages().getOne("~bus", stored(it))?.status != null }) delay(10)
        }
        val tookMs = System.currentTimeMillis() - wroteAt
        assertTrue(tookMs < 800, "went in $tookMs ms, not after the backoff (1 to 3 s)")
        val sent = ship.pokesTo("chat").map { it.json.toString() }
        assertTrue("first" in sent[0] && "second" in sent[1], "in the order written: $sent")
    }

    @Test
    fun `a drain asleep in its backoff goes as soon as the ship answers`() = live {
        ship.lose = { lost }
        val id = repo.send("~bus", "waiting")
        assertEquals("queued", db.messages().getOne("~bus", stored(id))?.status)
        // The ship is back: a send elsewhere goes through.
        ship.lose = { null }
        val answeredAt = System.currentTimeMillis()
        repo.send("~nec", "hello")
        kotlinx.coroutines.withTimeout(5_000) { while (db.messages().getOne("~bus", stored(id))?.status != null) delay(10) }
        val tookMs = System.currentTimeMillis() - answeredAt
        assertTrue(tookMs < 800, "went in $tookMs ms, not after the backoff (1 to 3 s)")
    }
}

