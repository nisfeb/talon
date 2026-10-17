package io.nisfeb.talon.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.ai.Briefing
import io.nisfeb.talon.ai.VoiceConversation
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the dictation card keeps in Assistant history, and what Archie's
 * opener counts, against a real database.
 */
class VoiceHistoryTest {
    private val tmp = createTempDirectory(prefix = "talon-voice-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()

    @AfterTest
    fun close() {
        db.close()
        tmp.deleteRecursively()
    }

    @Test
    fun `each exchange goes in as it happens, in one conversation, and to the other devices`() = runBlocking {
        val pushed = mutableListOf<Pair<String, String>>()
        val push: suspend (AssistantConversationEntity, AssistantHistoryEntity) -> Unit = { c, t -> pushed += c.gid to t.question }
        val first = VoiceHistory.record(db, null, "Archie, Saturday 17 October", VoiceConversation.OPENED, "You have 2 new emails.", 1_000, push)
        assertEquals(listOf("Archie, Saturday 17 October", "1", "0"), listOf(first.title, first.turnCount.toString(), first.dim.toString()))
        val second = VoiceHistory.record(db, first, "not used", "Read me the brief", "Here's today's brief.\n\nSaturday", 2_000, push)
        assertEquals(first.gid, second.gid)
        assertEquals(2, second.turnCount)
        val turns = db.assistantHistory().forConversation(second.id).sortedBy { it.createdAt }
        assertEquals(listOf(VoiceConversation.OPENED, "Read me the brief"), turns.map { it.question })
        assertEquals("Here's today's brief.\n\nSaturday", turns[1].answer, "the answer whole, as shown")
        assertTrue(turns.all { it.mode == VoiceHistory.MODE && it.convGid == first.gid })
        val conv = db.assistantConversations().recent(10).first().single()
        assertEquals(2, conv.turnCount)
        assertEquals(2_000L, conv.updatedAt)
        assertEquals(listOf(first.gid to VoiceConversation.OPENED, first.gid to "Read me the brief"), pushed)
    }

    @Test
    fun `a conversation trimmed away comes back under its own id, and a failed push keeps the turn`() = runBlocking {
        val first = VoiceHistory.record(db, null, "What do I have left to do", "What do I have left to do", "Three things.", 1_000)
        db.assistantConversations().deleteByGid(first.gid)
        val again = VoiceHistory.record(db, first, "x", "And before Friday?", "Two.", 2_000) { _, _ -> error("the ship is down") }
        assertEquals(first.gid, again.gid)
        assertNotEquals(0L, again.id)
        assertEquals(2, again.turnCount)
        assertEquals(listOf("And before Friday?"), db.assistantHistory().forConversation(again.id).map { it.question })
    }

    private fun msg(whom: String, author: String, text: String, sent: Long) =
        MessageEntity(whom, "$author/$sent", author, sent, """[{"inline":["$text"]}]""", "/chat")

    @Test
    fun `the opener counts what the chat list counts, and judges only what could ask the owner`() = runBlocking {
        db.messages().upsert(msg("~bus", "~bus", "can you grab milk?", 3_000))
        db.messages().upsert(msg("~bus", "~zod", "sure", 3_500))
        db.messages().upsert(msg("chat/~nec/general", "~nec", "~zod did the deploy go out?", 4_000))
        db.messages().upsert(msg("chat/~nec/general", "~nec", "lunch anyone?", 2_500))
        db.messages().upsert(msg("chat/~nec/general", "~nec", "~zodiac is not you", 2_600))
        // A message with no words in it: nothing to judge.
        db.messages().upsert(MessageEntity("~bus", "~bus/2900", "~bus", 2_900, "[]", "/chat"))
        db.unreads().upsert(UnreadEntity("~bus", count = 3, notifyCount = 0, recencyMs = 3_500))
        db.unreads().upsert(UnreadEntity("chat/~nec/general", count = 3, notifyCount = 1, recencyMs = 4_000))
        // Unread with nothing kept to show: the chat list does not count it.
        db.unreads().upsert(UnreadEntity("~wep", count = 7, notifyCount = 0, recencyMs = 1_000))
        assertEquals(6, Briefing.chatUnread(db))
        assertEquals(
            listOf(
                Briefing.Said("~nec", "chat/~nec/general", "~zod did the deploy go out?"),
                Briefing.Said("~bus", "~bus", "can you grab milk?"),
            ),
            Briefing.unreadSaid(db, "zod", StoryCache::textFor),
            "newest first; the owner's own and a channel's chatter left out",
        )
    }
}
