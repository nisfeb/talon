package io.nisfeb.talon.ai

import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DigestItem
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.WatchwordHitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestSelectorTest {

    private fun msg(
        whom: String = "~h/s",
        id: String = "0v1.aaa",
        author: String = "~sampel",
        sentMs: Long = 1_000,
        contentJson: String = """[{"inline":[{"text":"hi"}]}]""",
    ) = MessageEntity(
        whom = whom,
        id = id,
        author = author,
        sentMs = sentMs,
        contentJson = contentJson,
        kind = "/chat",
        parentId = null,
    )

    private fun hit(
        term: String = "mars",
        whom: String = "~h/s",
        postId: String = "0v1.aaa",
        sentMs: Long = 1_000,
        snippet: String = "Mars rocks",
    ) = WatchwordHitEntity(
        term = term, whom = whom, postId = postId, sentMs = sentMs, snippet = snippet,
    )

    @Test fun `mentions bucket detected via patp-boundary matcher`() {
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = listOf(
                msg(id = "0v1.aaa", contentJson = "hi ~mister-foo see this"),
                msg(id = "0v1.bbb", contentJson = "hi ~mister-foo-bar see this"),
            ),
            mentionPlainText = mapOf(
                "0v1.aaa" to "hi ~mister-foo see this",
                "0v1.bbb" to "hi ~mister-foo-bar see this",
            ),
            watchwordHits = emptyList(),
            unreadCandidates = emptyMap(),
            unreadCounts = emptyMap(),
        )
        assertEquals(1, items.size)
        assertEquals("0v1.aaa", items[0].postId)
        assertEquals(Bucket.MENTION, items[0].bucket)
    }

    @Test fun `watchword hits dedupe within bucket by whom postId`() {
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = listOf(
                hit(term = "mars", whom = "~h/s", postId = "0v1.aaa"),
                hit(term = "rocket", whom = "~h/s", postId = "0v1.aaa"),
                hit(term = "mars", whom = "~h/s", postId = "0v1.bbb"),
            ),
            unreadCandidates = emptyMap(),
            unreadCounts = emptyMap(),
        )
        // Two unique (whom, postId): aaa kept once, bbb once.
        assertEquals(2, items.size)
        assertTrue(items.all { it.bucket == Bucket.WATCHWORD })
        // First hit's term wins for the de-duped entry.
        assertEquals("mars", items.first { it.postId == "0v1.aaa" }.matchedTerm)
    }

    @Test fun `mention takes precedence over watchword and unread`() {
        val msg1 = msg(id = "0v1.shared", contentJson = "hi ~mister-foo")
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = listOf(msg1),
            mentionPlainText = mapOf("0v1.shared" to "hi ~mister-foo"),
            watchwordHits = listOf(hit(postId = "0v1.shared")),
            unreadCandidates = mapOf("~h/s" to listOf(msg1)),
            unreadCounts = mapOf("~h/s" to 1),
        )
        assertEquals(1, items.size)
        assertEquals(Bucket.MENTION, items[0].bucket)
    }

    @Test fun `watchword takes precedence over unread`() {
        val msg1 = msg(id = "0v1.shared")
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = listOf(hit(postId = "0v1.shared")),
            unreadCandidates = mapOf("~h/s" to listOf(msg1)),
            unreadCounts = mapOf("~h/s" to 1),
        )
        assertEquals(1, items.size)
        assertEquals(Bucket.WATCHWORD, items[0].bucket)
    }

    @Test fun `unread bucket caps to min of count and returned`() {
        val msgs = (1..10).map { msg(id = "0v1.${it}aaa", sentMs = it.toLong()) }
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = emptyList(),
            unreadCandidates = mapOf("~h/s" to msgs),
            unreadCounts = mapOf("~h/s" to 3),
        )
        // Only the newest 3 messages from the chat (count=3).
        assertEquals(3, items.size)
        assertTrue(items.all { it.bucket == Bucket.UNREAD })
    }

    @Test fun `unread cap of 100 across chats`() {
        val chats = (1..5).associate { c ->
            "~chat$c" to (1..30).map { i ->
                msg(whom = "~chat$c", id = "0v1.$c-$i", sentMs = (c * 100 + i).toLong())
            }
        }
        val counts = chats.keys.associateWith { 30 }
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = emptyList(),
            unreadCandidates = chats,
            unreadCounts = counts,
        )
        // 5 chats × 30 unread = 150 candidates; cap is 100.
        assertEquals(100, items.size)
    }

    @Test fun `empty input returns empty list`() {
        val items = DailyDigestSelector.assemble(
            ourPatp = "mister-foo",
            mentionCandidates = emptyList(),
            mentionPlainText = emptyMap(),
            watchwordHits = emptyList(),
            unreadCandidates = emptyMap(),
            unreadCounts = emptyMap(),
        )
        assertTrue(items.isEmpty())
    }
}
