package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A post in a channel this ship is not in is asked of its host through a
 * watch that answers once and is kicked. The kick's quit is the watch's
 * own: taken for the ship dropping one of the repo's watches, it ended
 * the channel.
 */
class TlonChatRepoOutsideTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-outside-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
    private val nest = OutsideReferenceTest.NEST
    private val post = "170.141.184.508.237.593.667.035.039.476.012.285.952"
    private val said = OutsideReferenceTest.SAID_WATCH

    @AfterTest
    fun close() {
        runBlocking { repo.stopAndJoinForTest() }
        io.nisfeb.talon.urbit.closeAfter(db)
    }

    /** The channel's events applied by the repo, as its session does. */
    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().onEach { repo.applyEvent(it.body) }.launchIn(this)
        try { body() } finally { events.cancel() }
    }

    @Test
    fun `a post the host shows comes back, is not kept, and its quit leaves the channel be`() = live {
        ship.answerWatch = { if (it == said) listOf(OutsideReferenceTest.SAID_POST) else null }
        val got = assertIs<Said.Post>(repo.fetchSaid(nest, post, null)).message
        assertEquals("~rud" to "170141184508237593667035039476012285952", got.author to got.id)
        assertEquals(listOf(said), ship.subscribed.filter { "said" in it })
        assertNull(db.messages().getOne(nest, got.id), "not one of ours: no conversation made of it")
        delay(300)
        assertTrue(ship.deleted.isEmpty(), "the said watch's quit did not end the channel: ${ship.deleted}")
        assertTrue(ship.unsubscribed.isEmpty(), "kicked by the ship, not dropped by us")
    }

    @Test
    fun `a host that refuses is denied`() = live {
        ship.answerWatch = { if (it == said) listOf("null") else null }
        assertEquals(Said.Denied, repo.fetchSaid(nest, post, null))
    }

    @Test
    fun `no answer in time is no answer, and the watch is dropped`() = live {
        assertEquals(Said.NoAnswer, repo.fetchSaid(nest, post, null, timeoutMs = 300))
        val id = ship.subIds.getValue(said)
        delay(300)
        assertEquals(listOf(id), ship.unsubscribed)
        // Its quit, if the ship sends one after all, is still the watch's own.
        ship.quit(said)
        delay(300)
        assertTrue(ship.deleted.isEmpty(), "${ship.deleted}")
    }

    @Test
    fun `a refused watch is no answer, at once`() = live {
        ship.refuseWatch = { if (it == said) "bad-watch-path" else null }
        assertEquals(Said.NoAnswer, repo.fetchSaid(nest, post, null))
    }

    @Test
    fun `the channel's preview names it and its group, and none where the host gave none`() = live {
        val preview = OutsideReferenceTest.PREVIEW_WATCH
        ship.answerWatch = { if (it == preview) listOf(OutsideReferenceTest.PREVIEW) else null }
        assertEquals(ChannelPreview("Public chat", "~rud/pubref", "Public Ref"), repo.channelPreview(nest))
        // A private group's: the subscribe is acked, and then nothing comes
        // at all (the host's nack is only logged by this ship's %groups).
        ship.answerWatch = { null }
        assertNull(repo.channelPreview(nest, timeoutMs = 300))
        delay(300)
        assertEquals(1, ship.unsubscribed.size, "the open watch is dropped")
        delay(300)
        assertTrue(ship.deleted.isEmpty(), "${ship.deleted}")
    }
}
