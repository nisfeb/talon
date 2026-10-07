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
            withTimeout(60_000) { block(repo) }
        } finally {
            repo.stop()
        }
    }

    private suspend fun until(what: String, check: suspend () -> Boolean) {
        runCatching { withTimeout(30_000) { while (!check()) delay(50) } }
            .onFailure { error("never saw: $what") }
    }

    private fun essay(author: String, text: String, sent: Long) =
        """{"content":[{"inline":["$text"]}],"author":"$author","sent":$sent,"kind":"/chat","blob":null,"meta":null}"""

    private fun post(id: String, author: String, text: String, sent: Long) =
        """"$id":{"seal":{"id":"$id","reacts":{},"replies":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}},"essay":${essay(author, text, sent)}}"""

    private val changes = """{"activity":{},"groups":{},"contacts":{},"chat":{},
        "channels":{"chat/~bus/general":{${post("170141184511", "~nec", "while away", 3_000)}}}}"""

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

    /** Signed in: the first read done and the stream open. The progress bar is clear before the session starts, too. */
    private suspend fun connected(repo: TlonChatRepo) {
        until("the first read") { ship.scried.any { "init-posts/10" in it } }
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("the stream") { streams() >= 1 }
    }
    private fun channelsStreamed() = ship.streamsOpened.map { it.first }.distinct()
    private fun channelsPut() = ship.requests.filter { it.startsWith("PUT /~/channel/") }.map { it.removePrefix("PUT /~/channel/") }.distinct()
    private val RECIPES = "notes/v0/notes/~bus/recipes/stream"
    private val NOTEBOOKS = """[{"flagName":"recipes","host":"~bus","notebook":{"title":"Recipes","id":7,"rootFolderId":8,
            "createdBy":"~bus","createdAt":1784592399,"updatedAt":1784592399,"updatedBy":"~bus"},"visibility":"private"}]"""

    private fun dmFact(id: String, text: String, sent: Long) =
        """{"id":1,"response":"diff","json":{"whom":"~bus","id":"$id","response":{"add":{"essay":${essay("~bus", text, sent)},"time":null}}}}"""

    // "the time to load new messages when they open the app is very long.
    // over 5 seconds" (iOS). Coming back, the old channel's delete hung on
    // a dead socket and the reconnect waited out its 5s. The channel is
    // resumed now: nothing to delete, and what came while away is the
    // ship's replay on it.
    @Test
    fun `back in the app, what came while away loads at once`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("history") { db.messages().getOne("~bus", "~bus/170141184506") != null }
        until("the stream") { streams() >= 1 }
        ship.holdDelete = 10_000
        val before = streams()
        val t0 = System.currentTimeMillis()
        repo.forceReconnect()
        until("the stream again") { streams() > before }
        ship.emit(dmFact("~bus/170141184508", "while you were away", 3_000))
        until("the message that came while away") { db.messages().getOne("~bus", "~bus/170141184508") != null }
        val took = System.currentTimeMillis() - t0
        assertTrue(took < 2_000, "took ${took}ms")
        assertEquals(1, channelsStreamed().size, "the same channel, resumed")
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
        refuseWatch = { w -> if (w == "activity/v6" || w == "activity/v5" || w == "groups/v3/groups" || w == "groups/v1/foreigns") "no such path" else null }
    }) {
        until("activity on /v4") { "activity/v4" in ship.subscribed }
        until("groups on /v1") { "groups/v1/groups" in ship.subscribed }
        until("invites on /gangs/updates") { "groups/gangs/updates" in ship.subscribed }
        val activity = ship.subscribed.filter { it.startsWith("activity/") }
        assertEquals(listOf("activity/v6", "activity/v5", "activity/v4"), activity, "in turn")
    }

    @Test
    fun `a current ship is watched on the paths Tlon's client uses`() = started(prepare = {}) {
        until("subscriptions") { "groups/v3/groups" in ship.subscribed && "activity/v6" in ship.subscribed && "groups/v1/foreigns" in ship.subscribed }
        assertTrue(ship.subscribed.none { it == "activity/v5" || it == "groups/v1/groups" || it == "groups/gangs/updates" })
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

    // A current ship is read where Tlon's client reads it (12.2.0's /v3
    // groups, /v10 init, the directory for our own card); the older paths,
    // which its N-1 policy lets it drop, are not asked. The tests above
    // give only the older ones, and pass: an older ship still loads.
    @Test
    fun `a current ship is read on the paths Tlon's client uses`() = started(prepare = {
        scries["groups-ui/v6/init-posts/10/10"] = initPosts
        scries["groups/v3/groups"] = """{"~bus/garden":{"meta":{"title":"The Garden"},"blob":null,
            "channels":{"chat/~bus/general":{"meta":{"title":"general"}}}}}"""
        scries["groups-ui/v10/init"] = """{"groups":{},"foreigns":{}}"""
        scries["contacts/v1/directory"] = """{"~zod":{"isContact":true,"contact":{"nickname":{"type":"text","value":"Zed"}},"mod":{}}}"""
    }) {
        until("the group") { db.groups().allGroups().any { it.flag == "~bus/garden" } }
        until("our own card") { db.contacts().get("~zod")?.nickname == "Zed" }
        assertTrue("groups/v2/groups" !in ship.scried, "${ship.scried}")
        assertTrue("contacts/v1/self" !in ship.scried, "ours came with the directory: ${ship.scried}")
    }

    // A foreigns-1 fact (desk/app/groups.hoon gives one on /v1/foreigns
    // where it gives the gang on /gangs/updates): someone invited us.
    @Test
    fun `an invite heard on v1 foreigns is shown and announced`() = started(prepare = {
        scries["groups-ui/v10/init"] = """{"groups":{},"foreigns":{}}"""
    }) { repo ->
        val announced = java.util.concurrent.CopyOnWriteArrayList<String>()
        repo.groupInviteListener = { announced += it.flag }
        until("a first read of invites") { repo.invitesFlow.value != null }
        val foreign = """{"invites":[{"flag":"~bus/garden","time":1,"from":"~bus","token":null,"note":null,"preview":null,"valid":true}],
            "lookup":null,"preview":{"meta":{"title":"The Garden","description":"","image":"","cover":""},"member-count":3,"privacy":"secret"},
            "progress":null,"token":null}"""
        ship.scries["groups-ui/v10/init"] = """{"groups":{},"foreigns":{"~bus/garden":$foreign}}"""
        repo.applyEvent(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":9,"response":"diff","json":{"~bus/garden":$foreign}}"""))
        until("the invite") { repo.invitesFlow.value?.any { it.flag == "~bus/garden" && it.title == "The Garden" } == true }
        until("its announcement") { "~bus/garden" in announced }
    }

    // ─── what the newer paths may cost (rc9 felt slower) ─────────────

    @Test
    fun `a newer path that times out is not followed by the older one`() = started(prepare = {
        loseScry = { p -> if (p == "groups/v3/groups") kotlinx.io.IOException("Request timeout has expired") else null }
        scries["groups/v2/groups"] = """{"~bus/garden":{"meta":{"title":"The Garden"},"channels":{}}}"""
    }) {
        until("the groups read was tried") { "groups/v3/groups" in ship.scried }
        delay(500)
        assertTrue("groups/v2/groups" !in ship.scried, "a busy ship asked twice: ${ship.scried}")
    }

    @Test
    fun `an older ship's missing path is asked once a session, not every time`() = started(prepare = {
        scries["groups/v2/groups"] = """{"~bus/garden":{"meta":{"title":"The Garden"},"channels":{}}}"""
        scries["groups/v2/groups/~bus/garden"] = """{"meta":{"title":"The Garden"},"channels":{},"seats":{},"roles":{},"admins":[],
            "admissions":{"privacy":"public","banned":{"ships":[],"ranks":[]},"pending":{},"requests":{},"tokens":{},"referrals":{},"invited":{}}}"""
    }) { repo ->
        until("the groups") { db.groups().allGroups().any { it.flag == "~bus/garden" } }
        runCatching { repo.fetchAdminGroupsLive() }
        runCatching { repo.fetchAdminGroupsLive() }
        val newer = ship.scried.count { it.startsWith("groups/v3/groups") }
        assertEquals(1, newer, "the family asked once, the per-group reads included: ${ship.scried}")
    }

    // groups /v1/foreigns: the invites alone. The groups-ui init it was read
    // from is the ship's largest scry, read whole at each start for them.
    @Test
    fun `invites are read from the foreigns alone, not the whole init`() = started(prepare = {
        scries["groups/v1/foreigns"] = """{"~bus/garden":{"invites":[{"flag":"~bus/garden","time":1,"from":"~bus","token":null,"note":null,"preview":null,"valid":true}],
            "lookup":null,"preview":{"meta":{"title":"The Garden","description":"","image":"","cover":""},"member-count":3,"privacy":"secret"},
            "progress":null,"token":null}}"""
    }) { repo ->
        until("the invite") { repo.invitesFlow.value?.any { it.flag == "~bus/garden" } == true }
        assertTrue(ship.scried.none { it.startsWith("groups-ui/") && it.endsWith("/init") }, "${ship.scried}")
    }

    // One PUT per event made each fact cost the ship a second event.
    @Test
    fun `a run of facts is acked in one request, not one each`() = started(prepare = {}) {
        until("subscriptions") { ship.subscribed.isNotEmpty() }
        delay(300)
        val before = ship.acked.size
        repeat(30) { ship.emit("""{"nothing":$it}""") }
        until("the run acked") { ship.acked.size > before }
        delay(500)
        assertTrue(ship.acked.size - before <= 2, "${ship.acked.size - before} acks for 30 facts")
    }

    // One PUT per subscription was an event on the ship for each, on
    // every connect.
    @Test
    fun `a connect's subscriptions go to the ship in one request`() = started(prepare = {}) {
        // chat, channels, activity, contacts, groups, presence, DM requests, group invites
        until("subscriptions") { ship.subscribed.size >= 8 }
        assertEquals(8, ship.subscribePuts.first(), "the first carried them all: ${ship.subscribePuts}")
    }

    // Fifty posts from every channel, on every launch, a full store or not.
    @Test
    fun `a launch with recent history kept reads ten a channel, not fifty`() = runBlocking<Unit> {
        db.messages().upsertWithMedia(db.messageMedia(), io.nisfeb.talon.data.MessageEntity("~bus", "~bus/170.141.184", "~bus", io.nisfeb.talon.util.nowMs() - 60_000, "hi", "/chat"))
        ship.scries[init] = initPosts
        started(prepare = {}) { repo ->
            until("the progress bar clears") { !repo.bootstrapping.value }
            delay(500)
            assertTrue(ship.scried.none { "init-posts/50" in it }, "${ship.scried}")
        }
    }

    @Test
    fun `a first launch reads fifty a channel`() = started(prepare = { scries[init] = initPosts }) { repo ->
        until("the deep pass") { ship.scried.any { "init-posts/50" in it } }
    }

    // A dropped stream read the notebook list again, and each loop of a
    // reconnect storm with it; then every reconnect was a new channel that
    // watched each notebook again. Eyre keeps the channel and its watches.
    @Test
    fun `a dropped stream resumes its channel, watching and reading nothing again`() = started(prepare = {
        scries[init] = initPosts
        scries["notes/v0/notebooks"] = """[{"flagName":"recipes","host":"~bus","notebook":{"title":"Recipes","id":7,"rootFolderId":8,
            "createdBy":"~bus","createdAt":1784592399,"updatedAt":1784592399,"updatedBy":"~bus"},"visibility":"private"}]"""
    }) { repo ->
        until("the notebook watched") { "notes/v0/notes/~bus/recipes/stream" in ship.subscribed }
        until("the progress bar clears") { !repo.bootstrapping.value }
        val lists = ship.scried.count { it == "notes/v0/notebooks" }
        val subs = ship.subscribed.size
        val before = streams()
        ship.endStreams()
        until("the stream again") { streams() > before }
        kotlinx.coroutines.delay(500)
        assertEquals(subs, ship.subscribed.size, "nothing watched again")
        assertEquals(lists, ship.scried.count { it == "notes/v0/notebooks" }, "the list not read again")
        assertEquals(1, channelsStreamed().size, "the same channel")
    }

    // ~ricsul, too busy to send its keepalives, cut every stream at 45 s.
    // Each reconnect was a new channel, and on a ship that slow the outage
    // always looked long, so the whole pass ran too: forty requests, the
    // unread scry among them, until the ship could do nothing else.
    @Test
    fun `a stream broken minutes after the last pass resumes without the whole pass`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("the unread scry") { ship.scried.any { "activity/full" in it } }
        val unreadReads = ship.scried.count { "activity/full" in it }
        val before = streams()
        repo.ageForTest(byMs = 5 * 60_000L, heardMs = 10 * 60_000L)
        ship.endStreams()
        until("the stream again") { streams() > before }
        kotlinx.coroutines.delay(500)
        assertEquals(unreadReads, ship.scried.count { "activity/full" in it }, "the unread scry not run again")
        assertEquals(1, channelsStreamed().size, "the same channel")
    }

    // The resumed stream asks eyre to start after the last event applied:
    // eyre acks everything up to it and replays the rest.
    @Test
    fun `a resumed stream starts after the last event applied`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("the stream") { streams() >= 1 }
        ship.emit(dmFact("~bus/170141184509", "the last one heard", 4_000))
        until("it applied") { db.messages().getOne("~bus", "~bus/170141184509") != null }
        val last = ship.lastEventId
        val before = streams()
        ship.endStreams()
        until("the stream again") { streams() > before }
        assertEquals(last.toString(), ship.streamsOpened.last().second)
    }

    // Eyre reaps a channel twelve hours after its stream: a stream asked
    // for on it is a 404. That is a long outage, read whole.
    @Test
    fun `a channel the ship reaped is replaced, every watch and the whole read`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("the unread scry") { ship.scried.any { "activity/full" in it } }
        val unreadReads = ship.scried.count { "activity/full" in it }
        val subs = ship.subscribed.size
        repo.ageForTest(byMs = 5 * 60_000L, heardMs = 10 * 60_000L)
        ship.reap()
        until("the unread scry again") { ship.scried.count { "activity/full" in it } > unreadReads }
        until("watched again") { ship.subscribed.size >= 2 * subs }
        until("a new channel") { channelsStreamed().size == 2 }
    }

    // A clog while the stream was down, or an agent's upgrade, drops a
    // subscription. Resumed, the channel would go on without it for good;
    // a new channel for it watched everything again and read everything.
    // Tlon's client watches that one path again, on the same channel.
    @Test
    fun `a subscription the ship drops is watched again on the same channel, and what it carried is read`() = started(prepare = {
        scries[init] = initPosts
        answerScry = { p -> if (p.startsWith("groups-ui/v11/changes/")) changes else null }
    }) { repo ->
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("the stream") { streams() >= 1 }
        val subs = ship.subscribed.size
        ship.quit("channels/v4")
        until("watched again") { ship.subscribed.count { it == "channels/v4" } == 2 }
        until("what it carried read") { ship.scried.any { it.startsWith("groups-ui/v11/changes/") } }
        until("its post") { db.messages().getOne("chat/~bus/general", "170141184511") != null }
        kotlinx.coroutines.delay(500)
        assertEquals(subs + 1, ship.subscribed.size, "that watch alone")
        assertEquals(1, channelsStreamed().size, "the same channel")
        assertEquals(1, ship.scried.count { "init-posts/10" in it }, "not the whole read")
    }

    // Dropped again the moment it is watched again: a loop, not an upgrade.
    @Test
    fun `a subscription dropped again at once brings a new channel and the whole read`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("the unread scry") { ship.scried.any { "activity/full" in it } }
        until("the stream") { streams() >= 1 }
        val unreadReads = ship.scried.count { "activity/full" in it }
        val first = ship.subIds.getValue("activity/v6")
        ship.quit("activity/v6")
        until("watched again") { ship.subIds.getValue("activity/v6") != first }
        until("its unreads read") { ship.scried.count { "activity/full" in it } > unreadReads }
        ship.quit("activity/v6")
        until("a new channel") { channelsStreamed().size == 2 }
    }

    // A watch lost on the way, not refused: a resumed channel watches
    // nothing again, so kept, it went without that watch (live posts,
    // unreads) on every later resume until the app restarted.
    @Test
    fun `a dropped subscription whose watch again is lost brings a new channel that has it`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("the stream") { streams() >= 1 }
        val once = java.util.concurrent.atomic.AtomicBoolean(true)
        ship.loseWatch = { w -> if (w == "channels/v4" && once.getAndSet(false)) java.io.IOException("lost") else null }
        ship.quit("channels/v4")
        until("a new channel") { channelsStreamed().size == 2 }
        until("watched on it") { ship.subscribed.count { it == "channels/v4" } == 2 }
        assertTrue(!once.get(), "the watch again was tried, and lost")
    }

    @Test
    fun `a notebook watch lost on a new channel gives that channel up for one with it`() = started(prepare = {
        scries[init] = initPosts
        scries["notes/v0/notebooks"] = NOTEBOOKS
        val once = java.util.concurrent.atomic.AtomicBoolean(true)
        loseWatch = { w -> if (w == RECIPES && once.getAndSet(false)) java.io.IOException("lost") else null }
    }) { _ ->
        until("the notebook watched") { RECIPES in ship.subscribed }
        until("a stream on the new channel") { channelsStreamed().isNotEmpty() }
        assertEquals(2, channelsPut().size, "a second channel: ${channelsPut()}")
        assertEquals(channelsPut().last(), channelsStreamed().single(), "the stream is the one with every watch")
    }

    // Its watch goes out before the stream opens, so a channel given up
    // here is replaced at once, as the notebook's above.
    @Test
    fun `a settings watch lost gives the channel up`() = runBlocking {
        val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
        ship.loseWatch = { java.io.IOException("lost") }
        sync.resubscribe()
        assertTrue(ship.channel.gone, "resumed, this channel would never watch settings again")
    }

    // A watch that is not the session's own (settings) still brings a new channel.
    @Test
    fun `a dropped watch the session did not make brings a new channel`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        until("the progress bar clears") { !repo.bootstrapping.value }
        until("the stream") { streams() >= 1 }
        ship.emit("""{"id":9999,"response":"quit"}""")
        until("a new channel") { channelsStreamed().size == 2 }
    }

    // A reconnect read the newest ten of every chat again, all of them,
    // and a busy chat with more than ten while away kept a gap. /changes
    // has everything since the last read, and nothing else.
    @Test
    fun `a reconnect reads what changed since the last read, not ten of every chat`() = started(prepare = {
        scries[init] = initPosts
        answerScry = { p -> if (p.startsWith("groups-ui/v11/changes/")) changes else null }
    }) { repo ->
        val began = System.currentTimeMillis()
        connected(repo)
        repo.ageForTest(byMs = 5 * 60_000L, heardMs = 10 * 60_000L)
        ship.reap()
        until("changes read") { ship.scried.any { it.startsWith("groups-ui/v11/changes/") } }
        until("its post") { db.messages().getOne("chat/~bus/general", "170141184511") != null }
        assertEquals(1, ship.scried.count { "init-posts/10" in it }, "init-posts once, at sign-in")
        // Since the first read began, less the overlap for clocks that differ.
        val asked = ship.scried.first { it.startsWith("groups-ui/v11/changes/") }.removePrefix("groups-ui/v11/changes/")
        val m = Regex("""~(\d{4})\.(\d{1,2})\.(\d{1,2})\.\.(\d{2})\.(\d{2})\.(\d{2})""").matchEntire(asked)
        assertNotNull(m, asked)
        val (y, mo, d, h, mi, se) = m.destructured
        val sinceMs = java.time.ZonedDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt(), 0, java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertTrue(sinceMs in (began - 5 * 60_000L - 2_000L)..(began - 5 * 60_000L + 30_000L), "since $asked against $began")
    }

    // An older ship has no /changes: init-posts, as before, and the newer
    // paths are not asked again on every reconnect.
    @Test
    fun `a ship without changes reads ten a chat, and is asked for changes once`() = started(prepare = {
        scries[init] = initPosts
    }) { repo ->
        connected(repo)
        repeat(2) { n ->
            repo.ageForTest(byMs = 5 * 60_000L, heardMs = 10 * 60_000L)
            ship.reap()
            until("init-posts again") { ship.scried.count { "init-posts/10" in it } == n + 2 }
        }
        assertEquals(1, ship.scried.count { it.startsWith("groups-ui/v11/changes/") }, "${ship.scried}")
        assertEquals(1, ship.scried.count { it.startsWith("groups-ui/v8/changes/") })
    }

    // A /changes read that does not come back is a busy ship: init-posts
    // on top of it would only add to the load.
    @Test
    fun `a changes read that times out is not followed by init-posts`() = started(prepare = {
        scries[init] = initPosts
        loseScry = { p -> if (p.startsWith("groups-ui/v11/changes/")) java.io.IOException("timed out") else null }
    }) { repo ->
        connected(repo)
        repo.ageForTest(byMs = 5 * 60_000L, heardMs = 10 * 60_000L)
        ship.reap()
        until("changes asked") { ship.scried.any { it.startsWith("groups-ui/v11/changes/") } }
        until("the unread scry again") { ship.scried.count { "activity/full" in it } >= 2 }
        kotlinx.coroutines.delay(500)
        assertEquals(1, ship.scried.count { "init-posts/10" in it }, "${ship.scried}")
        assertTrue(ship.scried.none { it.startsWith("groups-ui/v10/changes/") }, "nor an older path")
    }

    // Eyre ties a channel to the login that made it, and refuses it (403)
    // to any other: retried, it is refused for good. A new one, after the
    // backoff a lapsed login needs.
    @Test
    fun `a channel refused to this login is replaced`() = runBlocking<Unit> {
        ship.scries[init] = initPosts
        val repo = TlonChatRepo(db)
        repo.start(UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") })
        try {
            withTimeout(25_000) {
                connected(repo)
                ship.forbid()
                runCatching { withTimeout(20_000) { while (channelsStreamed().size < 2) delay(50) } }
                    .onFailure { error("never saw a new channel: ${ship.streamsOpened}") }
                val refused = ship.streamsOpened.count { it.first == channelsStreamed().first() }
                kotlinx.coroutines.delay(1_000)
                assertEquals(refused, ship.streamsOpened.count { it.first == channelsStreamed().first() }, "not asked again")
            }
        } finally {
            repo.stop()
        }
    }

    // Each invite heard read the whole foreigns list again. The fact is
    // the one group that moved, in the shape the scry gives.
    @Test
    fun `an invite heard on v1 foreigns is applied as it came, not read again`() = started(prepare = {
        scries["groups/v1/foreigns"] = "{}"
    }) { repo ->
        val announced = java.util.concurrent.CopyOnWriteArrayList<String>()
        repo.groupInviteListener = { announced += it.flag }
        until("a first read of invites") { repo.invitesFlow.value != null }
        val reads = ship.scried.count { it == "groups/v1/foreigns" }
        val foreign = """{"invites":[{"flag":"~bus/garden","time":1,"from":"~bus","token":null,"note":null,"preview":null,"valid":true}],
            "lookup":null,"preview":{"meta":{"title":"The Garden","description":"","image":"","cover":""},"member-count":3,"privacy":"secret"},
            "progress":null,"token":null}"""
        repo.applyEvent(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":9,"response":"diff","json":{"~bus/garden":$foreign}}"""))
        until("the invite") { repo.invitesFlow.value?.any { it.flag == "~bus/garden" && it.title == "The Garden" } == true }
        until("its announcement") { "~bus/garden" in announced }
        assertEquals(reads, ship.scried.count { it == "groups/v1/foreigns" }, "not read again")

        // Joined: the host's answer, then done, and the invite goes.
        val done = foreign.replace("\"progress\":null", "\"progress\":\"done\"")
        repo.applyEvent(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":10,"response":"diff","json":{"~bus/garden":$done}}"""))
        until("the invite gone") { repo.invitesFlow.value?.none { it.flag == "~bus/garden" } == true }
        assertEquals(1, announced.size, "announced once")
    }
}

