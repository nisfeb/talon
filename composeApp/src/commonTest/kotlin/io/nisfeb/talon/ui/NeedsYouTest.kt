package io.nisfeb.talon.ui

import io.nisfeb.talon.data.UnreadEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What replaced the daily digest, and why it is not one.
 *
 * The digest recapped a fixed day on an alarm. This is the list of
 * things actually owed, worked out from what is already in the
 * database, true at the moment it is read.
 */
class NeedsYouTest {

    private val us = "~wex"

    private fun msg(whom: String, author: String, at: Long) =
        io.nisfeb.talon.data.MessageEntity(
            id = "$whom-$at", whom = whom, author = author, sentMs = at,
            contentJson = "", kind = "chat",
        )

    private fun unread(count: Int, notify: Int = 0) =
        UnreadEntity(whom = "", count = count, notifyCount = notify, recencyMs = 0)

    private fun run(
        latest: List<io.nisfeb.talon.data.MessageEntity>,
        unreads: Map<String, UnreadEntity>,
        mail: List<MailNeed> = emptyList(),
        invites: List<String> = emptyList(),
        limit: Int = 10,
    ) = needsYou(latest, unreads, mail, invites, us, limit, { it }, { "said something" })

    @Test
    fun `our own last word is not something waiting on us`() {
        // The whole distinction between a to-do and an inbox.
        val rows = run(
            listOf(msg("~dalsyd", us, 100)),
            mapOf("~dalsyd" to unread(3)),
        )
        assertTrue(rows.isEmpty(), "it listed a conversation we spoke last in")
    }

    @Test
    fun `somebody else's last word, unread, is`() {
        val rows = run(
            listOf(msg("~dalsyd", "~dalsyd", 100)),
            mapOf("~dalsyd" to unread(3)),
        )
        assertEquals(listOf(NeedKind.REPLY), rows.map { it.kind })
        assertEquals("~dalsyd", rows.single().target)
    }

    @Test
    fun `a conversation we have read is not owed`() {
        val rows = run(
            listOf(msg("~dalsyd", "~dalsyd", 100)),
            mapOf("~dalsyd" to unread(0)),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a conversation with no unread row at all is not owed`() {
        assertTrue(run(listOf(msg("~dalsyd", "~dalsyd", 100)), emptyMap()).isEmpty())
    }

    @Test
    fun `a mention outranks a newer plain unread`() {
        // Ordered by kind before recency on purpose: a list that
        // reshuffled by the clock would bury this morning's mention
        // under a group message from a minute ago.
        val rows = run(
            listOf(msg("~mentions", "~a", 100), msg("~chatter", "~b", 900)),
            mapOf(
                "~mentions" to unread(1, notify = 1),
                "~chatter" to unread(9),
            ),
        )
        assertEquals(listOf(NeedKind.MENTION, NeedKind.REPLY), rows.map { it.kind })
        assertEquals("~mentions", rows.first().target)
    }

    @Test
    fun `within a kind it is newest first`() {
        val rows = run(
            listOf(msg("~old", "~a", 100), msg("~new", "~b", 900), msg("~mid", "~c", 500)),
            mapOf("~old" to unread(1), "~new" to unread(1), "~mid" to unread(1)),
        )
        assertEquals(listOf("~new", "~mid", "~old"), rows.map { it.target })
    }

    @Test
    fun `mail and invitations come after the conversations`() {
        val rows = run(
            latest = listOf(msg("~dalsyd", "~dalsyd", 100)),
            unreads = mapOf("~dalsyd" to unread(1)),
            mail = listOf(MailNeed("m1", "~ricsul", "Re: release", 50)),
            invites = listOf("~host/group"),
        )
        assertEquals(
            listOf(NeedKind.REPLY, NeedKind.MAIL, NeedKind.INVITE),
            rows.map { it.kind },
        )
    }

    @Test
    fun `an empty mail subject still reads as something`() {
        val rows = run(
            emptyList(), emptyMap(),
            mail = listOf(MailNeed("m1", "~ricsul", "   ", 50)),
        )
        assertEquals("(no subject)", rows.single().line)
    }

    @Test
    fun `the limit bites, and bites the least pressing`() {
        val rows = run(
            latest = listOf(msg("~m", "~a", 100), msg("~r", "~b", 100)),
            unreads = mapOf("~m" to unread(1, notify = 1), "~r" to unread(1)),
            mail = listOf(MailNeed("m1", "~x", "s", 10)),
            limit = 2,
        )
        assertEquals(listOf(NeedKind.MENTION, NeedKind.REPLY), rows.map { it.kind })
    }

    @Test
    fun `nothing owed is an empty list, not a crash`() {
        assertTrue(run(emptyList(), emptyMap()).isEmpty())
        assertTrue(run(emptyList(), emptyMap(), limit = 0).isEmpty())
    }
}
