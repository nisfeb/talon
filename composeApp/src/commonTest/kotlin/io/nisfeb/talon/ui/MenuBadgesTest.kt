package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pin the rail-item → freshness flag mapping. The DesktopShell
 * reads `menuBadges.forItem(item)` for every rail icon to decide
 * whether to render the dot — silently dropping a flag here means
 * the user never sees the freshness signal for that surface.
 */
class MenuBadgesTest {

    @Test
    fun `default constructor reports nothing fresh`() {
        val b = MenuBadges()
        for (item in RailItem.entries) {
            assertFalse(b.forItem(item), "$item should be quiet on a default MenuBadges")
        }
    }

    @Test
    fun `statusesFresh maps only to RailItem Statuses`() {
        val b = MenuBadges(statusesFresh = true)
        assertTrue(b.forItem(RailItem.Statuses))
        for (item in RailItem.entries.filter { it != RailItem.Statuses }) {
            assertFalse(b.forItem(item), "$item should not be fresh when only statuses is")
        }
    }

    @Test
    fun `digestFresh maps only to RailItem TodaysBrief`() {
        val b = MenuBadges(digestFresh = true)
        assertTrue(b.forItem(RailItem.TodaysBrief))
        for (item in RailItem.entries.filter { it != RailItem.TodaysBrief }) {
            assertFalse(b.forItem(item), "$item should not be fresh when only digest is")
        }
    }

    @Test
    fun `invitesPending maps only to RailItem Invites`() {
        val b = MenuBadges(invitesPending = true)
        assertTrue(b.forItem(RailItem.Invites))
        for (item in RailItem.entries.filter { it != RailItem.Invites }) {
            assertFalse(b.forItem(item), "$item should not be fresh when only invites is")
        }
    }

    @Test
    fun `all three flags compose without crosstalk`() {
        val b = MenuBadges(
            statusesFresh = true,
            digestFresh = true,
            invitesPending = true,
        )
        assertTrue(b.forItem(RailItem.Statuses))
        assertTrue(b.forItem(RailItem.TodaysBrief))
        assertTrue(b.forItem(RailItem.Invites))
        // Items without a freshness concept stay quiet
        for (item in listOf(
            RailItem.Chats, RailItem.Bookmarks, RailItem.Activity,
            RailItem.Profile, RailItem.Watchwords, RailItem.Administration,
            RailItem.Settings,
        )) {
            assertFalse(b.forItem(item), "$item has no freshness — should be false")
        }
    }

    @Test
    fun `every RailItem has a deterministic forItem mapping`() {
        // No matter the flag combo, forItem returns a stable Boolean
        // for every enum entry — guards against an `else throw` regression.
        val combos = listOf(
            MenuBadges(),
            MenuBadges(statusesFresh = true),
            MenuBadges(digestFresh = true),
            MenuBadges(invitesPending = true),
            MenuBadges(true, true, true),
        )
        for (b in combos) {
            for (item in RailItem.entries) {
                // Just calling it; the assertion is "doesn't crash"
                val ignored = b.forItem(item)
                assertEquals(ignored, b.forItem(item), "forItem must be deterministic")
            }
        }
    }
}
