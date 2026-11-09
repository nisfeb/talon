package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.DmInviteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Blocking a ship, at the repo (review of #99): the block goes before
 * the decline of a waiting request, so a decline the ship refuses does
 * not leave them unblocked; and a blocked list not yet read is read, not
 * made up of the one ship just blocked.
 */
class BlockShipTest {
    private val dir = createTempDirectory(prefix = "talon-block-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val ship = FakeShip("~zod")
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }

    @AfterTest
    fun close() {
        runBlocking { repo.stopAndJoinForTest() }
        closeAfter(db)
        dir.deleteRecursively()
    }

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        try { body() } finally { events.cancel() }
    }

    private fun sent() = ship.pokes.map { "${it.app} ${it.mark} ${it.json}" }

    @Test
    fun `a refused decline leaves them blocked all the same, and says it failed`() = live {
        db.dmInvites().upsertAll(listOf(DmInviteEntity("~bus", 1)))
        ship.refuse = { if (it.mark == "chat-dm-rsvp") "no" else null }
        assertFailsWith<PokeNacked> { repo.blockShip("~bus") }
        assertEquals(
            listOf("""chat chat-block-ship {"ship":"~bus"}""", """trunk trunk-action {"block":"~bus"}""", """chat chat-dm-rsvp {"ship":"~bus","ok":false}"""),
            sent(),
        )
    }

    @Test
    fun `a blocked list not yet read is read from the ship, never one ship standing for all of it`() = live {
        // The ship's list once it has taken the block: another was there already.
        ship.scries["chat/blocked"] = """["~nec","~bus"]"""
        assertNull(repo.blockedShips.value)
        repo.blockShip("~bus")
        assertEquals(setOf("~nec", "~bus"), repo.blockedShips.value)
    }

    @Test
    fun `a list that still cannot be read stays unknown`() = live {
        ship.failScry = { "blocked" in it }
        repo.blockShip("~bus")
        assertNull(repo.blockedShips.value, "not {~bus}: Settings would show it as everyone blocked")
        assertEquals("""chat chat-block-ship {"ship":"~bus"}""", sent().first())
    }
}
