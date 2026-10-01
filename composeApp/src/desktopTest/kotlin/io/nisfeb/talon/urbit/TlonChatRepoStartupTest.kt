package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signing in, for real: the repo starts its session against a
 * [FakeShip], subscribes, and fills the database from the ship's scries.
 * Each scry feeds its own tables, and a ship that lacks one still has to
 * load the rest.
 */
class TlonChatRepoStartupTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-start-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")

    @AfterTest
    fun close() = db.close()

    private fun started(prepare: FakeShip.() -> Unit, block: suspend (TlonChatRepo) -> Unit) = runBlocking<Unit> {
        ship.prepare()
        val repo = TlonChatRepo(db)
        repo.start(UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") })
        try {
            withTimeout(15_000) { block(repo) }
        } finally {
            repo.stop()
        }
    }

    private suspend fun until(what: String, check: suspend () -> Boolean) {
        runCatching { withTimeout(10_000) { while (!check()) delay(50) } }
            .onFailure { error("never saw: $what") }
    }

    private fun essay(author: String, text: String, sent: Long) =
        """{"content":[{"inline":["$text"]}],"author":"$author","sent":$sent,"kind":"/chat","blob":null,"meta":null}"""

    private fun post(id: String, author: String, text: String, sent: Long) =
        """"$id":{"seal":{"id":"$id","reacts":{},"replies":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}},"essay":${essay(author, text, sent)}}"""

    private val initPosts = """{
        "chat":{"~bus":{${post("~bus/170141184506", "~bus", "a DM", 1_000)}}},
        "channels":{"chat/~bus/general":{${post("170141184507", "~nec", "a channel post", 2_000)}}}}"""

    @Test
    fun `signing in fills history, groups, unreads, contacts and requests`() = started(prepare = {
        scries["groups-ui/v6/init-posts/10/10"] = initPosts
        scries["groups/v2/groups"] = """{"~bus/garden":{"meta":{"title":"The Garden"},
            "channels":{"chat/~bus/general":{"meta":{"title":"general"}}}}}"""
        scries["activity/v4/activity/full"] = """{"ship/~bus":{"count":7,"notify-count":1,"recency":1000,"notify":true,
            "unread":{"id":"~bus/170.141.184.506","time":"170.141.184.506","count":7,"notify":true}}}"""
        scries["contacts/v1/directory"] = """{"~bus":{"isContact":true,"contact":{"nickname":{"type":"text","value":"Bus"}},"mod":{}}}"""
        scries["chat/dm/invited"] = """["~nec"]"""
    }) { repo ->
        until("history") { db.messages().getOne("chat/~bus/general", "170141184507") != null }
        assertNotNull(db.messages().getOne("~bus", "~bus/170141184506"))
        until("groups") { db.groups().getGroup("~bus/garden") != null }
        assertEquals("~bus/garden", db.groups().channelGroupFor("chat/~bus/general")?.groupFlag)
        until("unreads") { db.unreads().getOne("~bus") != null }
        assertEquals(7, db.unreads().getOne("~bus")?.count)
        until("contacts") { db.contacts().get("~bus") != null }
        assertEquals("Bus", db.contacts().get("~bus")?.nickname)
        until("requests") { db.dmInvites().allShips().isNotEmpty() }
        assertEquals(listOf("~nec"), db.dmInvites().allShips())
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("subscriptions") { ship.subscribed.isNotEmpty() }
    }

    @Test
    fun `a ship missing most scries still loads what it has`() = started(prepare = {
        scries["groups-ui/v6/init-posts/10/10"] = initPosts
    }) { repo ->
        until("history") { db.messages().getOne("~bus", "~bus/170141184506") != null }
        until("the progress bar clears") { !repo.bootstrapping.value }
        assertNull(db.groups().getGroup("~bus/garden"))
    }

    private val init = "groups-ui/v6/init-posts/10/10"
    private fun streams() = ship.requests.count { it.startsWith("GET /~/channel/") }

    // "the time to load new messages when they open the app is very long.
    // over 5 seconds" (iOS). Coming back, the old channel's delete hangs on
    // a dead socket; the reconnect waited out its 5s, then a 1-3s jitter,
    // and a return inside a minute of the last load read nothing at all.
    @Test
    fun `back in the app, what came while away loads at once`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("history") { db.messages().getOne("~bus", "~bus/170141184506") != null }
        until("the stream") { streams() >= 1 }
        ship.holdDelete = 10_000
        ship.scries[init] = """{"chat":{"~bus":{${post("~bus/170141184506", "~bus", "a DM", 1_000)},
            ${post("~bus/170141184508", "~bus", "while you were away", 3_000)}}}}"""
        val t0 = System.currentTimeMillis()
        repo.forceReconnect()
        until("the message that came while away") { db.messages().getOne("~bus", "~bus/170141184508") != null }
        val took = System.currentTimeMillis() - t0
        assertTrue(took < 2_000, "took ${took}ms")
    }

    // The stream often died while the app was suspended, and the loop sat
    // in a backoff the return could not cut short.
    @Test
    fun `back in the app, a backoff does not keep the stream waiting`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("the stream") { streams() >= 1 }
        ship.refuseStream = true
        ship.endStreams()
        until("a refused reconnect") { streams() >= 2 }
        ship.refuseStream = false
        val before = streams()
        val t0 = System.currentTimeMillis()
        repo.forceReconnect()
        until("the stream again") { streams() > before }
        val took = System.currentTimeMillis() - t0
        assertTrue(took < 1_500, "took ${took}ms")
    }

    // On every connect the directory is read whole. A nickname a peer
    // removed went from the ship but stayed here for good: the merge
    // kept it, and an empty book row written after the directory's
    // could stand over the directory's own.
    @Test
    fun `a nickname removed while away is gone after the connect, and a book name still wins`() = runBlocking<Unit> {
        db.contacts().upsertAll(listOf(
            io.nisfeb.talon.data.ContactEntity("~bus", nickname = "Bus", bio = "old", avatarUrl = null),
            io.nisfeb.talon.data.ContactEntity("~nec", nickname = "Nec", bio = null, avatarUrl = null),
            io.nisfeb.talon.data.ContactEntity("~wex", nickname = "Wex", bio = null, avatarUrl = null),
        ))
        started(prepare = {
            scries["groups-ui/v6/init-posts/10/10"] = initPosts
            scries["contacts/v1/directory"] = """{"~bus":{"status":{"type":"text","value":"here"}},"~nec":{}}"""
            // ~bus in the book with an empty page; ~nec with our own name for
            // them; ~wex only in the book, with nothing known of them.
            scries["contacts/v1/book"] = """{"~bus":[{},{}],"~nec":[{},{"nickname":{"type":"text","value":"Necky"}}],"~wex":[{},{}]}"""
        }) { _ ->
            until("the directory read") { db.contacts().get("~bus")?.status == "here" }
            val bus = db.contacts().get("~bus")!!
            assertNull(bus.nickname, "removed by them")
            assertNull(bus.bio)
            assertEquals("Necky", db.contacts().get("~nec")?.nickname, "our own name for them")
            assertEquals("Wex", db.contacts().get("~wex")?.nickname, "nothing known of them, so nothing changes")
        }
    }

    // ─── Tlon 12.3.0's N-1 policy: newer paths first, older ones behind ───

    @Test
    fun `an older ship's activity and groups subscriptions walk back to what it has`() = started(prepare = {
        refuseWatch = { w -> if (w == "activity/v6" || w == "activity/v5" || w == "groups/v3/groups") "no such path" else null }
    }) {
        until("activity on /v4") { "activity/v4" in ship.subscribed }
        until("groups on /v1") { "groups/v1/groups" in ship.subscribed }
        val activity = ship.subscribed.filter { it.startsWith("activity/") }
        assertEquals(listOf("activity/v6", "activity/v5", "activity/v4"), activity, "in turn")
    }

    @Test
    fun `a current ship is watched on the paths Tlon's client uses`() = started(prepare = {}) {
        until("subscriptions") { "groups/v3/groups" in ship.subscribed && "activity/v6" in ship.subscribed }
        assertTrue(ship.subscribed.none { it == "activity/v5" || it == "groups/v1/groups" })
    }

    // "Notebook unread indicators": activity /v6 (12.1.0) has notebook
    // sources, whose unread is always ~ and whose count is their notes'.
    @Test
    fun `a notebook's unread notes are counted from activity v6`() = started(prepare = {
        scries["activity/v6/activity/full"] = """{"notebook/~bus/recipes":{"count":3,"notify-count":0,"recency":1000,"notify":false,"unread":null},
            "note/~bus/recipes/12":{"count":3,"notify-count":0,"recency":1000,"notify":false,"unread":null}}"""
    }) { repo ->
        until("the notebook's unread") { db.unreads().getOne("notes/~bus/recipes")?.count == 3 }
    }
}

