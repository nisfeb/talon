package io.nisfeb.talon.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Inbox row's unread count, read off the inbox's first page. */
class InboxUnreadLabelTest {
    private fun page(total: Int, vararg unread: Boolean) = InboxPage(
        total = total,
        threads = unread.mapIndexed { i, u -> InboxEntry(id = "0v$i", unread = u) },
    )

    @Test
    fun `none unread, or no listing yet, is no count`() {
        assertNull(inboxUnreadLabel(null))
        assertNull(inboxUnreadLabel(page(2, false, false)))
        assertNull(inboxUnreadLabel(page(0)))
    }

    @Test
    fun `the unread threads on the page are counted`() {
        assertEquals("2", inboxUnreadLabel(page(5, true, false, true, false, false)))
        assertEquals("3", inboxUnreadLabel(page(3, true, true, true)), "the whole inbox, all unread")
    }

    @Test
    fun `a page all unread with more past it says so`() {
        assertEquals("3+", inboxUnreadLabel(page(120, true, true, true)))
        assertEquals("2", inboxUnreadLabel(page(120, true, true, false)), "a read one on the page: the count is the page's")
    }
}
