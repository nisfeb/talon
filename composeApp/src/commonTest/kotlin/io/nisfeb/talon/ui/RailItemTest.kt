package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RailItemTest {

    @Test
    fun `pane-tab items map to a RailTab and round-trip`() {
        for (item in RailItem.entries.filter { it.isPaneTab }) {
            val tab = item.toRailTab()
            assertNotNull(tab, "pane-tab item $item should produce a RailTab")
            assertEquals(item.name, tab!!.name)
        }
    }

    @Test
    fun `modal items return null toRailTab`() {
        for (item in RailItem.entries.filter { !it.isPaneTab }) {
            assertNull(item.toRailTab(), "modal item $item should not have a RailTab")
        }
    }

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

    @Test
    fun `railItemOrNull round-trips every value`() {
        for (item in RailItem.entries) {
            assertEquals(item, railItemOrNull(item.name))
        }
    }

    @Test
    fun `isVisible defaults to true for absent items`() {
        val empty: Map<RailItem, Boolean> = emptyMap()
        for (item in RailItem.entries) {
            assertTrue(empty.isVisible(item), "absent $item should be visible by default")
        }
    }

    @Test
    fun `isVisible respects explicit false`() {
        val map: Map<RailItem, Boolean> = mapOf(RailItem.Settings to false)
        assertEquals(false, map.isVisible(RailItem.Settings))
        assertEquals(true, map.isVisible(RailItem.Chats))
    }

    // ---- sanitizeRailItemOrder ---------------------------------------

    @Test
    fun `sanitizeRailItemOrder on empty input returns full enum in declaration order`() {
        assertEquals(RailItem.entries.toList(), sanitizeRailItemOrder(emptyList()))
    }

    @Test
    fun `sanitizeRailItemOrder is identity for an already-correct input`() {
        val input = RailItem.entries.toList()
        assertEquals(input, sanitizeRailItemOrder(input))
    }

    @Test
    fun `sanitizeRailItemOrder preserves a custom user order`() {
        val custom = listOf(
            RailItem.Settings,
            RailItem.Chats,
            RailItem.Profile,
            RailItem.Statuses,
            RailItem.Bookmarks,
            RailItem.Activity,
            RailItem.Watchwords,
            RailItem.TodaysBrief,
            RailItem.Administration,
            RailItem.Invites,
        )
        assertEquals(custom, sanitizeRailItemOrder(custom))
    }

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
    fun `sanitizeRailItemOrder always returns the full enum universe`() {
        // Even bizarre inputs (empty, partial, dupes) result in every
        // RailItem appearing exactly once in the output.
        val cases = listOf(
            emptyList(),
            listOf(RailItem.Chats),
            listOf(RailItem.Settings, RailItem.Settings, RailItem.Statuses),
            listOf(RailItem.Profile, RailItem.Watchwords),
        )
        for (input in cases) {
            val out = sanitizeRailItemOrder(input)
            assertEquals(
                RailItem.entries.toSet(),
                out.toSet(),
                "input $input should produce the full universe",
            )
            assertEquals(RailItem.entries.size, out.size,
                "input $input has duplicates or missing items in result $out")
        }
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
