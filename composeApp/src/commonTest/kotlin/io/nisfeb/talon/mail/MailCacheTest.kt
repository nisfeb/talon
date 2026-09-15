package io.nisfeb.talon.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MailCacheTest {
    private val page = InboxPage(total = 2, threads = listOf(InboxEntry(id = "a", unread = true), InboxEntry(id = "b")))

    @Test fun `archiving leaves the inbox at once and only marks the thread elsewhere`() {
        val inbox = page.archived(MailView.INBOX, "a", archived = true)
        assertEquals(listOf("b"), inbox.threads.map { it.id })
        assertEquals(1, inbox.total)
        assertEquals(true, page.archived(MailView.ALL, "a", archived = true).threads.first().archived)
        assertEquals(listOf("b"), page.archived(MailView.ARCHIVED, "a", archived = false).threads.map { it.id })
        assertSame(page, page.archived(MailView.INBOX, "not-listed", archived = true), "a thread that is not listed leaves the page alone")
    }

    @Test fun `a row is edited in place and removed by id`() {
        assertEquals(false, page.editRow("a") { it.copy(unread = false) }.threads.first().unread)
        assertEquals(listOf("a"), page.without("b").threads.map { it.id })
    }
}
