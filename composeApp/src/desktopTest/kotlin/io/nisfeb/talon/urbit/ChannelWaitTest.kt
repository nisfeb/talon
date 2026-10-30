package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A write while the session is still opening its channel waits for it.
 * Right after launch the screens are up before the channel: a contact
 * added then failed at once as "slow or out of reach", with the ship
 * fine and nothing sent (AppShellTest on CI, 2026-10-30), and so did a
 * call's recording notice.
 */
class ChannelWaitTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-chwait-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db)

    @AfterTest
    fun close() {
        runBlocking { repo.stopAndJoinForTest() }
        db.close()
    }

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private fun failure(block: suspend () -> Unit): Throwable? = runBlocking { runCatching { block() }.exceptionOrNull() }

    @Test
    fun `a contact added while the channel opens waits for it, and goes`() = live {
        repo.startedForTest()
        val add = async { runCatching { repo.addContact("~bus") } }
        delay(300)
        assertTrue(ship.pokesTo("contacts").isEmpty(), "nothing to send on yet")
        assertTrue(add.isActive, "still waiting, not failed")
        repo.attachForTest(ship.channel, "~zod")
        val done = withTimeout(10_000) { add.await() }
        assertTrue(done.isSuccess, "it went once the channel was there: ${done.exceptionOrNull()}")
        assertTrue(ship.pokesTo("contacts").isNotEmpty())
    }

    @Test
    fun `a running repo whose channel never comes says so after the wait`() {
        repo.startedForTest()
        repo.channelWaitMs = 400
        val started = System.currentTimeMillis()
        val e = failure { repo.addContact("~bus") }
        assertEquals("not connected", e?.message)
        assertTrue(System.currentTimeMillis() - started >= 400, "it waited first")
        assertTrue(ship.pokesTo("contacts").isEmpty())
    }

    @Test
    fun `a repo that is not running says so at once`() {
        val started = System.currentTimeMillis()
        val e = failure { repo.addContact("~bus") }
        assertEquals("not connected", e?.message)
        assertTrue(System.currentTimeMillis() - started < 2_000, "no wait on a repo that is not running")
    }
}
