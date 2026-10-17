package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.UnreadEntity
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * list_unread against a real database: "Read me my unread messages" and
 * "What did Andrea say?" (sneagan, 2026-10-16). Unread starts where the
 * ship says it does, the owner's own are left out, and the conversations
 * are the ones the opener counted.
 */
class ListUnreadTest {
    private val tmp = createTempDirectory(prefix = "talon-unread-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val repo = TlonChatRepo(db).apply { attachForTest(FakeShip("~zod").channel, "~zod") }

    private fun msg(whom: String, author: String, text: String, sent: Long) =
        MessageEntity(whom, "$author/$sent", author, sent, """[{"inline":["$text"]}]""", "/chat")

    @BeforeTest
    fun fill() = runBlocking {
        // Read before the ship's first unread, then unread, our own reply among it.
        db.messages().upsert(msg("~bus", "~bus", "old news", 1_000))
        db.messages().upsert(msg("~bus", "~bus", "can you grab milk?", 3_000))
        db.messages().upsert(msg("~bus", "~zod", "sure", 3_500))
        db.messages().upsert(msg("~bus", "~bus", "and eggs", 3_600))
        // Five: two reactions count too, which is why the count is not the boundary.
        db.unreads().upsert(UnreadEntity("~bus", count = 5, notifyCount = 0, recencyMs = 3_600, firstUnreadId = "~bus/3000"))
        // No first unread kept: the count is all there is.
        db.messages().upsert(msg("chat/~nec/general", "~nec", "lunch anyone", 2_500))
        db.messages().upsert(msg("chat/~nec/general", "~nec", "did the deploy go out?", 4_000))
        db.messages().upsert(msg("chat/~nec/general", "~bus", "yes!", 4_100))
        db.unreads().upsert(UnreadEntity("chat/~nec/general", count = 2, notifyCount = 0, recencyMs = 4_100))
        // Unread with nothing kept to show: the chat list does not count it.
        db.unreads().upsert(UnreadEntity("~wep", count = 7, notifyCount = 0, recencyMs = 5_000))
    }

    @AfterTest
    fun close() {
        db.close()
        tmp.deleteRecursively()
    }

    private fun texts(found: List<Pair<String, List<MessageEntity>>>) = found.map { (w, ms) -> w to ms.map { it.sentMs } }

    @Test
    fun `unread starts at the ship's first unread, not the count, and leaves the owner's own out`() = runBlocking {
        assertEquals(
            listOf("chat/~nec/general" to listOf(4_000L, 4_100L), "~bus" to listOf(3_000L, 3_600L)),
            texts(Briefing.unread(db, "zod", per = 10)),
            "newest conversation first, oldest message first",
        )
        assertEquals(listOf("chat/~nec/general" to listOf(4_100L), "~bus" to listOf(3_600L)), texts(Briefing.unread(db, "~zod", per = 1)), "the newest kept")
        assertEquals(listOf("chat/~nec/general" to listOf(4_100L), "~bus" to listOf(3_000L, 3_600L)), texts(Briefing.unread(db, "~zod", 10, from = "~bus")))
        db.unreads().upsert(UnreadEntity("~bus", count = 2, notifyCount = 0, recencyMs = 3_600, firstUnreadId = "~bus/999"))
        assertEquals(listOf(3_600L), texts(Briefing.unread(db, "~zod", 10)).last().second, "a first unread not kept here falls back to the count")
    }

    private val seen = mutableListOf<MessageEntity>()
    private fun listUnread(vararg args: Pair<String, String>): String = runBlocking {
        ToolCatalog.default(
            repo, db, null,
            onMessages = { seen += it },
            conversationLabel = { if (it == "chat/~nec/general") "Family · General" else it },
        ) { mapOf("~bus" to "Andrea", "~nec" to "Nec")[it] ?: it }
            .single { it.spec.name == "list_unread" }
            .execute(buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
    }

    @Test
    fun `the answer reads as it is said, each message a sentence, and gives the ids last`() {
        assertEquals(
            "In Family · General:\nNec: did the deploy go out?\nAndrea: yes!\n\n" +
                "Andrea:\ncan you grab milk?\nand eggs.\n\n" +
                "${Briefing.UNREAD_IDS} Family · General = chat/~nec/general; ~bus = ~bus",
            listUnread(),
        )
        assertEquals(4, seen.size, "the card draws what was read")
        assertTrue("sure" !in listUnread(), "our own reply is not news")
    }

    @Test
    fun `one person's, and nothing unread said so`() {
        assertEquals("In Family · General:\nNec: did the deploy go out?\n\n${Briefing.UNREAD_IDS} Family · General = chat/~nec/general", listUnread("from" to "~nec", "count" to "1"))
        assertTrue(listUnread("from" to "bus").startsWith("In Family · General:\nAndrea: yes!\n\nAndrea:\ncan you grab milk?"), "a ship without its sig too")
        assertEquals("Nothing unread from Wes.", ToolCatalog.default(repo, db, null) { if (it == "~wes") "Wes" else it }
            .single { it.spec.name == "list_unread" }.let { runBlocking { it.execute(buildJsonObject { put("from", "~wes") }) } })
        runBlocking { db.unreads().upsert(UnreadEntity("~bus", count = 0, notifyCount = 0, recencyMs = 3_600)) }
        runBlocking { db.unreads().upsert(UnreadEntity("chat/~nec/general", count = 0, notifyCount = 0, recencyMs = 4_100)) }
        assertEquals("Nothing unread.", listUnread())
    }

    @Test
    fun `a dictation is offered it, locked or not, and asked questions may use it`() {
        val tools = ToolCatalog.default(repo, db, null) { it }
        assertTrue(DictationAgent.toolsFor(tools, locked = true).any { it.spec.name == "list_unread" })
        assertTrue(DictationAgent.readsFor(tools).any { it.spec.name == "list_unread" })
    }
}
