package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A reaction shows the moment it is made. Since pokes began waiting for
 * the ship's ack (26ccc4e7, 1.7.0) the local row was written after that
 * wait, so a busy ship, or a stream reconnecting, held the reaction back
 * for up to fifteen seconds. A refusal still takes it back.
 */
class OptimisticReactTest {
    private fun db(): AppDatabase {
        val dir = createTempDirectory(prefix = "talon-react-test-").toFile()
        return Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    private class OneSession : SessionStore {
        private val s = SavedSession("https://ship.test", "~zod", "test-session", "0v1", "ship.test")
        override fun all() = listOf(s)
        override fun active() = s
        override fun activeShip() = s.ship
        override fun save(entry: SavedSession, makeActive: Boolean) {}
        override fun setActive(ship: String) {}
        override fun remove(ship: String) {}
        override fun clearAll() {}
    }

    /**
     * A channel whose PUTs land and whose stream says [sse], or nothing
     * at all. The stream answers only once the poke has gone out, as the
     * ship's would: an answer ahead of its poke has nothing to settle.
     */
    private fun channel(sse: String?): UrbitChannel {
        val poked = kotlinx.coroutines.CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { req ->
            when {
                req.method.value == "PUT" -> { poked.complete(Unit); respond("", HttpStatusCode.NoContent) }
                sse == null -> { delay(60_000); respond("") }
                else -> { poked.await(); respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }
            }
        })
        return UrbitSession(http, OneSession()).apply { tryRestore("~zod") }.openChannel()
    }

    @Test
    fun `the reaction is there before the ship answers`() = runBlocking {
        val db = db()
        val repo = TlonChatRepo(db).apply { attachForTest(channel(sse = null), "~zod") }
        val sending = async { repo.react("~bus", "170141184506", "👍") }
        withTimeout(5_000) {
            while (db.reactions().get("~bus", "170141184506", "~zod") == null) delay(20)
        }
        assertFalse(sending.isCompleted, "shown while the poke still waits for its ack")
        sending.cancelAndJoin()
        db.close()
    }

    @Test
    fun `a refused reaction is taken back`() = runBlocking {
        val db = db()
        val ch = channel("id: 1\ndata: {\"id\":1,\"response\":\"poke\",\"err\":\"bad-key\"}\n\n")
        val events = ch.events().onEach { }.launchIn(this)
        val repo = TlonChatRepo(db).apply { attachForTest(ch, "~zod") }
        // Dotted, as a channel post id can come: the row is keyed undotted, and still taken back.
        assertFailsWith<PokeNacked> { withTimeout(10_000) { repo.react("~bus", "170.141.184.506", "👍") } }
        assertNull(db.reactions().get("~bus", "170141184506", "~zod"))
        events.cancel()
        db.close()
    }
}
