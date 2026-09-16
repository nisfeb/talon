package io.nisfeb.talon.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailNotifyTest {

    private fun row(
        id: String,
        unread: Boolean = true,
        from: String = "~zod",
        subject: String = "Hello",
        snippet: String = "a line",
        forged: Boolean = false,
    ) = InboxEntry(
        id = id,
        from = from,
        subject = subject,
        snippet = snippet,
        unread = unread,
        forged = forged,
        verdict = if (forged) Verdict.FORGED else Verdict.VERIFIED,
    )

    private val plainName: (String) -> String = { it }

    @Test
    fun `launching does not replay a backlog`() {
        val rows = listOf(row("a"), row("b"))
        val baseline = seedMailBaseline(rows)
        val (fired, _) = diffMailNotifications(rows, baseline, plainName)
        assertTrue(fired.isEmpty(), "mail already unread at startup has been seen")
    }

    @Test
    fun `only a thread that was not unread before is announced`() {
        val (fired, seen) = diffMailNotifications(
            listOf(row("old"), row("new")),
            lastSeen = setOf("old"),
            nameFor = plainName,
        )
        assertEquals(listOf("new"), fired.map { it.threadId })
        assertEquals(setOf("old", "new"), seen)
    }

    @Test
    fun `a thread read since the last look stops counting as unread`() {
        val (fired, seen) = diffMailNotifications(
            listOf(row("a", unread = false)),
            lastSeen = setOf("a"),
            nameFor = plainName,
        )
        assertTrue(fired.isEmpty())
        assertTrue(seen.isEmpty(), "reading it is what makes it announceable again later")
    }

    @Test
    fun `an already-announced thread is not announced on every tick`() {
        val rows = listOf(row("a"))
        val (first, seen) = diffMailNotifications(rows, emptySet(), plainName)
        assertEquals(1, first.size)
        val (second, _) = diffMailNotifications(rows, seen, plainName)
        assertTrue(second.isEmpty(), "a poll must not become a nuisance")
    }

    @Test
    fun `a forgery stays loud where the user is not looking`() {
        val (fired, _) = diffMailNotifications(
            listOf(row("f", from = "~zod", subject = "Invoice", forged = true)),
            emptySet(),
            plainName,
        )
        assertEquals("FORGED · ~zod · Invoice", fired.single().title)
    }

    @Test
    fun `a flood is capped and says how much it left out`() {
        val rows = (1..7).map { row("t$it") }
        val (fired, _) = diffMailNotifications(rows, emptySet(), plainName, cap = 3)
        assertEquals(4, fired.size)
        assertEquals("and 4 more messages", fired.last().title)
        assertEquals("", fired.last().threadId, "the summary opens nothing")
    }

    @Test
    fun `one left over is singular`() {
        val rows = (1..4).map { row("t$it") }
        val (fired, _) = diffMailNotifications(rows, emptySet(), plainName, cap = 3)
        assertEquals("and 1 more message", fired.last().title)
    }

    @Test
    fun `an empty subject and an empty snippet still read as something`() {
        val (fired, _) = diffMailNotifications(
            listOf(row("a", subject = "", snippet = "")),
            emptySet(),
            plainName,
        )
        assertEquals("~zod · (no subject)", fired.single().title)
        assertEquals("(no preview)", fired.single().body)
    }

    @Test
    fun `the display name is used, not the raw ship`() {
        val (fired, _) = diffMailNotifications(
            listOf(row("a", from = "~sampel-palnet")),
            emptySet(),
            nameFor = { if (it == "~sampel-palnet") "Sam" else it },
        )
        assertTrue(fired.single().title.startsWith("Sam · "))
    }
}
