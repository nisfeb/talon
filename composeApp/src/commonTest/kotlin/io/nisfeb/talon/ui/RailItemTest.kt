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
}
