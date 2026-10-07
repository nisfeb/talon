package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.DmInviteEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.UnreadEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * %chat 12.3.0 announces each DM entering, changing or leaving its list
 * with a chat-dm-status fact on /v4 (desk/app/chat.hoon +give-dm-status;
 * lib/chat-json.hoon +dm-status: `{"ship":…,"net":…}`, net null when gone).
 * Talon dropped it: a DM declined or left on another device stayed here.
 */
class DmStatusTest {
    private val db: AppDatabase = createTempDirectory(prefix = "talon-dmstatus-").toFile().let { dir ->
        Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    private val ship = FakeShip()
    private val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }

    @AfterTest
    fun close() = db.close()

    private suspend fun fact(json: String) =
        repo.applyEvent(Json.parseToJsonElement("""{"id":1,"response":"diff","json":$json}"""))

    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `the fact is read in the shape the desk sends it, and nothing else is`() {
        assertEquals("~bus" to null, dmStatusOf(obj("""{"ship":"~bus","net":null}""")))
        for (net in listOf("inviting", "invited", "archive", "done")) {
            assertEquals("~bus" to net, dmStatusOf(obj("""{"ship":"~bus","net":"$net"}""")))
        }
        assertNull(dmStatusOf(obj("""{"ship":"~bus","net":"elsewhere"}""")))
        assertNull(dmStatusOf(obj("""{"ship":"~bus","net":null,"whom":"~bus"}""")), "another fact with these keys and more")
        assertNull(dmStatusOf(obj("""{"ship":"bus","net":"done"}""")))
    }

    @Test
    fun `a DM left on another device goes from here, messages and unread with it`() = runBlocking<Unit> {
        db.messages().upsert(MessageEntity("~bus", "~bus/1", "~bus", 1_000, "[]", "/chat"))
        db.messages().upsert(MessageEntity("~nec", "~nec/1", "~nec", 1_000, "[]", "/chat"))
        db.unreads().upsertAll(listOf(UnreadEntity(whom = "~bus", count = 2, notifyCount = 0, recencyMs = 1_000)))
        fact("""{"ship":"~bus","net":null}""")
        assertFalse(db.messages().hasConversation("~bus"))
        assertTrue(db.messages().hasConversation("~nec"), "only that one")
        assertTrue(db.unreads().stream().first().none { it.whom == "~bus" })
    }

    @Test
    fun `a request declined on another device leaves the requests`() = runBlocking<Unit> {
        db.dmInvites().upsertAll(listOf(DmInviteEntity(ship = "~bus", receivedMs = 1)))
        fact("""{"ship":"~bus","net":null}""")
        assertTrue(db.dmInvites().allShips().isEmpty())
    }

    @Test
    fun `a DM begun on another device is read now, not at the next bootstrap`() = runBlocking<Unit> {
        fact("""{"ship":"~bus","net":"inviting"}""")
        withTimeout(5_000) { while (ship.scried.none { "chat" in it && "~bus" in it }) delay(20) }
    }

    @Test
    fun `one already here is not read again`() = runBlocking<Unit> {
        db.messages().upsert(MessageEntity("~bus", "~bus/1", "~bus", 1_000, "[]", "/chat"))
        fact("""{"ship":"~bus","net":"done"}""")
        delay(300)
        assertTrue(ship.scried.none { "~bus" in it }, "${ship.scried}")
    }
}
