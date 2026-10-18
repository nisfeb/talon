package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.notify.messageAlerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Android's message alerts, end to end on real time: a repo signed in to a
 * [FakeShip], its listener deciding as TalonApplication's does, with the
 * common [messageAlerts]. Without push, what came while the stream was
 * down reaches the phone only when the stream is back, with its old time
 * (B6-3); what this device had already is not news again (A5-11).
 */
class MessageAlertsTest {
    private val dir = createTempDirectory(prefix = "talon-alerts-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val ship = FakeShip("~zod")
    private val alerting = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The ids that alerted, in order. */
    private val alerted = CopyOnWriteArrayList<String>()
    /** Every message the repo handed the listener, alerted or not. */
    private val decided = CopyOnWriteArrayList<String>()

    @AfterTest
    fun close() {
        alerting.cancel()
        db.close()
        dir.deleteRecursively()
    }

    private fun started(prepare: FakeShip.() -> Unit, block: suspend (TlonChatRepo) -> Unit) = runBlocking<Unit> {
        ship.prepare()
        val repo = TlonChatRepo(db)
        repo.messageListener = { m, toUs, heard ->
            alerting.launch {
                if (db.messageAlerts(m, toUs, heard, "~zod", onScreen = false)) alerted += m.id
                decided += m.id
            }
        }
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

    /** A DM from ~bus as eyre frames it on the stream. */
    private fun dmFact(id: String, text: String, sent: Long) =
        """{"id":1,"response":"diff","json":{"whom":"~bus","id":"$id","response":{"add":{"essay":${essay("~bus", text, sent)},"time":null}}}}"""

    private val streams get() = ship.requests.count { it.startsWith("GET /~/channel/") }

    @Test
    fun `a message that came while the stream was down alerts however old and only once`() = started(prepare = {
        scries["groups-ui/v6/init-posts/10/10"] = """{"chat":{"~bus":{${post("~bus/170141184506", "~bus", "before", 1_000)}}},"channels":{}}"""
        // Read up to the one before it, an hour and more ago.
        scries["activity/v4/activity/full"] = """{"ship/~bus":{"count":0,"notify-count":0,"recency":1000,"notify":false,"unread":null}}"""
    }) { repo ->
        until("the first read") { db.messages().getOne("~bus", "~bus/170141184506") != null && !repo.bootstrapping.value }
        until("the unreads") { db.unreads().getOne("~bus") != null }
        until("the stream") { streams >= 1 }
        val before = streams
        ship.endStreams()
        // Sent long ago by the clock: the phone was in a tunnel, or dozing.
        ship.emit(dmFact("~bus/170141184508", "while you were away", 3_000))
        repo.forceReconnect()
        until("the stream again") { streams > before }
        until("the message from the outage") { "~bus/170141184508" in decided }
        assertEquals(listOf("~bus/170141184508"), alerted, "the bootstrap's message is not news")
        // Eyre sends it again (a new event): heard here before, it is not news.
        ship.emit(dmFact("~bus/170141184508", "while you were away", 3_000))
        ship.emit(dmFact("~bus/170141184509", "and this", 4_000))
        until("both decided") { decided.count { it == "~bus/170141184508" } == 2 && "~bus/170141184509" in decided }
        assertEquals(listOf("~bus/170141184508", "~bus/170141184509"), alerted)
    }

    // A reply's read state is its thread's; the chat's level and a mention
    // still decide the rest. The pieces messageAlerts reads, one by one.
    @Test
    fun `a reply is read by its thread and a channel needs a mention or its level`() = runBlocking<Unit> {
        val nest = "chat/~bus/general"
        db.unreads().upsert(io.nisfeb.talon.data.UnreadEntity(nest, count = 3, notifyCount = 0, recencyMs = 9_000))
        db.threadUnreads().upsert(io.nisfeb.talon.data.ThreadUnreadEntity(nest, "100", count = 0, notifyCount = 0, recencyMs = 9_000))
        fun msg(sent: Long, parent: String? = null, text: String = "[]") =
            io.nisfeb.talon.data.MessageEntity(nest, "m$sent", "~bus", sent, text, "/chat", parentId = parent)
        suspend fun alerts(m: io.nisfeb.talon.data.MessageEntity, toUs: Boolean = false, heard: Boolean = false, onScreen: Boolean = false) =
            db.messageAlerts(m, toUs, heard, "~zod", onScreen)
        assertEquals(false, alerts(msg(8_000, parent = "100"), toUs = true), "read in its thread")
        assertEquals(true, alerts(msg(10_000, parent = "100"), toUs = true), "after the thread's read")
        assertEquals(true, alerts(msg(8_000, parent = "200"), toUs = true), "a thread with no row is unread")
        assertEquals(false, alerts(msg(8_000)), "a channel at the default level, no mention")
        assertEquals(true, alerts(msg(8_000, text = """[{"inline":[{"ship":"~zod"}]}]""")), "a mention, unread in the chat")
        db.notifyPrefs().upsert(io.nisfeb.talon.data.NotifyPreferenceEntity(nest, io.nisfeb.talon.data.NotifyLevel.ALL))
        assertEquals(true, alerts(msg(8_000)), "every message at all")
        assertEquals(false, alerts(msg(8_000), heard = true))
        assertEquals(false, alerts(msg(8_000), onScreen = true))
        db.unreads().upsert(io.nisfeb.talon.data.UnreadEntity(nest, count = 0, notifyCount = 0, recencyMs = 20_000))
        assertEquals(false, alerts(msg(8_000)), "read in the chat")
        assertEquals(true, alerts(msg(10_000, parent = "100"), toUs = true), "a chat's read is not its threads'")
    }

    // ─── a catch-up's read ─────────────────────────────────────────

    private val now = System.currentTimeMillis()
    private val nest = "chat/~bus/general"
    private val mention = """{"content":[{"inline":[{"ship":"~zod"}," look"]}],"author":"~nec","sent":${now - 170_000},"kind":"/chat","blob":null,"meta":null}"""

    /**
     * What the ship says changed: posts from three DMs and a channel, in
     * the last few minutes but for one an hour old. Old enough by the
     * clock that the age rule silenced them all.
     */
    private val changes = """{"activity":{},"groups":{},"contacts":{},
        "chat":{
          "~bus":{${post("~bus/170141184601", "~bus", "first", now - 180_000)},${post("~bus/170141184602", "~bus", "second", now - 120_000)}},
          "~nec":{${post("~nec/170141184603", "~nec", "before the last read", now - 60 * 60_000)}},
          "~wex":{${post("~wex/170141184604", "~wex", "read on the laptop", now - 150_000)}}},
        "channels":{"$nest":{
          "170141184605":{"seal":{"id":"170141184605","reacts":{},"replies":{},"meta":{"replyCount":0,"lastReply":null,"lastRepliers":[]}},"essay":$mention},
          ${post("170141184606", "~bus", "chatter after it", now - 60_000)}}}}"""

    /** ~bus and the channel unread; ~wex read on another client since its message. */
    private val activity = """{
        "ship/~bus":{"count":2,"notify-count":2,"recency":${now - 120_000},"notify":true,"unread":{"id":"~bus/170.141.184.601","time":"170.141.184.601","count":2,"notify":true}},
        "ship/~wex":{"count":0,"notify-count":0,"recency":$now,"notify":false,"unread":null},
        "channel/$nest":{"count":2,"notify-count":1,"recency":${now - 60_000},"notify":true,"unread":{"id":"~nec/170.141.184.605","time":"170.141.184.605","count":2,"notify":true}}}"""

    // A phone with no push whose process was gone: the catch-up worker
    // starts it, and the first read is everything since the last one. The
    // alerts wait for what is read, then go up one a chat.
    @Test
    fun `a catch-up alerts once a chat for what came since the last read and after the read state`() = runBlocking<Unit> {
        db.marks().put(io.nisfeb.talon.data.MarkEntity(io.nisfeb.talon.data.MarkDao.POSTS_READ, now - 30 * 60_000))
        started(prepare = {
            answerScry = { p -> if (p.startsWith("groups-ui/v11/changes/")) changes else null }
            scries["activity/v4/activity/full"] = activity
            holdScry = { p -> if (p.startsWith("activity/")) 2_000 else 0 }
        }) { _ ->
            until("the posts") { db.messages().getOne(nest, "170141184606") != null }
            delay(500)
            assertEquals(emptyList(), decided.toList(), "nothing before what is read is known")
            until("the alerts") { decided.size >= 3 }
            delay(500)
            assertEquals(setOf("~bus/170141184602", "170141184605", "~wex/170141184604"), decided.toSet(), "one a chat, none from before the last read")
            assertEquals(3, decided.size)
            assertEquals(setOf("~bus/170141184602", "170141184605"), alerted.toSet(), "not the one read on the laptop")
        }
    }

    // A new install, or a database wiped: nothing it reads is news.
    @Test
    fun `a first sign-in alerts nothing it reads`() = started(prepare = {
        scries["groups-ui/v6/init-posts/10/10"] = changes
        scries["activity/v4/activity/full"] = activity
    }) { repo ->
        until("the first read") { db.messages().getOne(nest, "170141184606") != null && !repo.bootstrapping.value }
        until("the unreads") { db.unreads().getOne("~bus") != null }
        until("the stream") { streams >= 1 }
        ship.emit(dmFact("~bus/170141184607", "live", now))
        until("the live one") { "~bus/170141184607" in decided }
        delay(500)
        assertEquals(listOf("~bus/170141184607"), decided.toList())
        assertEquals(listOf("~bus/170141184607"), alerted.toList())
    }

    // The stream dropped and the channel went with it (eyre reaped it): a
    // new channel reads what changed since the last read, and that alerts.
    @Test
    fun `a new channel after an outage alerts what its read brought`() = started(prepare = {
        scries["groups-ui/v6/init-posts/10/10"] = """{"chat":{},"channels":{}}"""
        scries["activity/v4/activity/full"] = activity
    }) { repo ->
        until("the first read") { !repo.bootstrapping.value && db.unreads().getOne("~bus") != null }
        until("the stream") { streams >= 1 }
        ship.answerScry = { p -> if (p.startsWith("groups-ui/v11/changes/")) changes else null }
        repo.ageForTest(byMs = 5 * 60_000L, heardMs = 10 * 60_000L)
        ship.reap()
        until("the alerts") { decided.size >= 3 }
        delay(500)
        assertEquals(setOf("~bus/170141184602", "170141184605"), alerted.toSet(), "decided: $decided")
        assertEquals(3, decided.size)
    }

    @Test
    fun `a message read already does not alert`() = started(prepare = {
        scries["groups-ui/v6/init-posts/10/10"] = """{"chat":{},"channels":{}}"""
        // Read on another client at 9 000, after the message was sent.
        scries["activity/v4/activity/full"] = """{"ship/~bus":{"count":0,"notify-count":0,"recency":9000,"notify":false,"unread":null}}"""
    }) { repo ->
        until("the unreads") { db.unreads().getOne("~bus")?.recencyMs == 9_000L && !repo.bootstrapping.value }
        until("the stream") { streams >= 1 }
        ship.emit(dmFact("~bus/170141184508", "read elsewhere", 8_000))
        ship.emit(dmFact("~bus/170141184510", "after the read", 10_000))
        until("both decided") { decided.containsAll(listOf("~bus/170141184508", "~bus/170141184510")) }
        assertEquals(listOf("~bus/170141184510"), alerted)
    }
}
