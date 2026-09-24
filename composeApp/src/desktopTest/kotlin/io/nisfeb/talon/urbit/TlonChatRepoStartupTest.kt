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
}
