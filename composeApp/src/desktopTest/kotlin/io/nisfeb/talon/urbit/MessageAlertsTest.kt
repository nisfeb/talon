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
