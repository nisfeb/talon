package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pin the rail-item → freshness flag mapping. The DesktopShell
 * reads `menuBadges.forItem(item)` for every rail icon to decide
 * whether to render the dot — silently dropping a flag here means
 * the user never sees the freshness signal for that surface.
 */
class MenuBadgesTest {

    @Test
    fun `each flag lights only its own item`() {
        for ((b, own) in listOf(
            MenuBadges(statusesFresh = true) to RailItem.Statuses,
            MenuBadges(invitesPending = true) to RailItem.Invites,
            MenuBadges(assistantNews = true) to RailItem.Assistant,
            MenuBadges(noticesUnread = true) to RailItem.Activity,
        )) {
            assertEquals(setOf(own), RailItem.entries.filter(b::forItem).toSet(), "$b")
        }
    }

    @Test
    fun `the Chats icon never shows a dot`() {
        val all = MenuBadges(
            statusesFresh = true, invitesPending = true, calendarOffers = true, actionsWaiting = true,
            mailUnread = true, assistantNews = true, threadsUnread = true, noticesUnread = true,
        )
        assertEquals(false, all.forItem(RailItem.Chats))
        assertEquals(false, all.byItem()[RailItem.Chats])
    }

    @Test
    fun `unread mail maps only to RailItem Mail, and the drawer gets it too`() {
        val b = MenuBadges(mailUnread = true)
        assertEquals(setOf(RailItem.Mail), RailItem.entries.filter(b::forItem).toSet())
        assertEquals(true, b.byItem()[RailItem.Mail])
    }

}
