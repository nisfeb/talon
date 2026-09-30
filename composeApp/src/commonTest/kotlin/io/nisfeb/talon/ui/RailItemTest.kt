package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RailItemTest {

    @Test
    fun `every RailTab has a RailItem of the same name`() {
        // Pinned the contract so adding a RailTab without a RailItem
        // would surface here, not as a runtime crash in the rail code.
        for (tab in RailTab.entries) {
            val item = railItemOrNull(tab.name)
            assertNotNull(item, "RailTab $tab has no matching RailItem")
            assertTrue(item!!.isPaneTab, "RailItem ${tab.name} should be a pane tab")
        }
    }

    @Test
    fun `railItemOrNull returns null for null, blank, unknown`() {
        assertNull(railItemOrNull(null))
        assertNull(railItemOrNull(""))
        assertNull(railItemOrNull("   "))
        assertNull(railItemOrNull("NotAValue"))
    }

    // ---- sanitizeRailItemOrder ---------------------------------------

    @Test
    fun `sanitizeRailItemOrder de-duplicates with first-occurrence-wins`() {
        val input = listOf(
            RailItem.Statuses,
            RailItem.Chats,
            RailItem.Statuses,  // duplicate; should be ignored
            RailItem.Bookmarks,
        )
        val out = sanitizeRailItemOrder(input)
        // Statuses appears once, at its first position
        assertEquals(1, out.count { it == RailItem.Statuses })
        assertTrue(out.indexOf(RailItem.Statuses) < out.indexOf(RailItem.Bookmarks))
    }

    @Test
    fun `sanitizeRailItemOrder appends missing enum values at declaration position`() {
        // User reordered some items; the rest haven't been touched yet
        // (e.g. a future enum addition the user's saved config
        // doesn't know about). Missing items should land at their
        // declaration index — not always at the end.
        //
        // Use a partial input where the user has put Settings + Chats
        // first, leaving the others to fall in. The trailing fill is
        // RailItem.entries minus the listed prefix, in declaration
        // order.
        val partial = listOf(RailItem.Settings, RailItem.Chats)
        val out = sanitizeRailItemOrder(partial)
        assertEquals(RailItem.Settings, out[0])
        assertEquals(RailItem.Chats, out[1])
        // Fill order matches enum declaration for the items not in `partial`
        val remaining = RailItem.entries.filter { it !in partial }
        assertEquals(remaining, out.drop(2))
    }
}
