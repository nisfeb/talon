package io.nisfeb.talon.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which form a tab row takes, and how the desktop panes share a window,
 * at each width. "As the window narrows: denser packing, then a dropdown
 * instead of panes, then a minimum width column."
 */
class NarrowLayoutTest {

    // Widest label 60dp in Full and Tight, 50dp in Small. Three tabs need,
    // per tab, label + both paddings + 1dp of rounding slack:
    // Full 93, Tight 73, Small 63.
    private val widths: (TabForm) -> Dp = { if (it == TabForm.Small) 50.dp else 60.dp }

    @Test
    fun `a fixed row is full while every tab's share holds its label, then tight, then small, then a dropdown`() {
        assertEquals(TabForm.Full, tabForm(279.dp, 3, scrollable = false, widths))
        assertEquals(TabForm.Tight, tabForm(278.dp, 3, scrollable = false, widths))
        assertEquals(TabForm.Tight, tabForm(219.dp, 3, scrollable = false, widths))
        assertEquals(TabForm.Small, tabForm(218.dp, 3, scrollable = false, widths))
        assertEquals(TabForm.Small, tabForm(189.dp, 3, scrollable = false, widths))
        assertEquals(TabForm.Dropdown, tabForm(188.dp, 3, scrollable = false, widths))
        assertEquals(TabForm.Dropdown, tabForm(0.dp, 3, scrollable = false, widths))
    }

    @Test
    fun `more tabs need more width`() {
        assertEquals(TabForm.Full, tabForm(279.dp, 3, scrollable = false, widths))
        assertEquals(TabForm.Small, tabForm(279.dp, 4, scrollable = false, widths))
    }

    @Test
    fun `a scrollable row stays full while two of its widest tabs fit, whatever the count`() {
        assertEquals(TabForm.Full, tabForm(186.dp, 9, scrollable = true, widths))
        assertEquals(TabForm.Tight, tabForm(185.dp, 9, scrollable = true, widths))
        assertEquals(TabForm.Small, tabForm(145.dp, 9, scrollable = true, widths))
        assertEquals(TabForm.Dropdown, tabForm(125.dp, 9, scrollable = true, widths))
    }

    @Test
    fun `no tabs is not a crash`() {
        assertEquals(TabForm.Full, tabForm(100.dp, 0, scrollable = false) { 0.dp })
    }

    @Test
    fun `the list keeps the owner's fraction where it can`() {
        assertEquals(300.dp, listPaneWidth(1000.dp, 0.30f))
        assertEquals(500.dp, listPaneWidth(1000.dp, 0.50f))
        // The fraction itself stays within its bounds.
        assertEquals(500.dp, listPaneWidth(1000.dp, 0.90f))
        assertEquals(240.dp, listPaneWidth(1000.dp, 0.01f))
    }

    @Test
    fun `the list narrows to its minimum, and never takes the chat's minimum`() {
        // 30% of 700 is 210: under the list's minimum.
        assertEquals(MIN_LIST_WIDTH, listPaneWidth(700.dp, 0.30f))
        // Half of 700 would leave the chat 344: the chat keeps 360.
        assertEquals(700.dp - HANDLE_WIDTH - MIN_CHAT_WIDTH, listPaneWidth(700.dp, 0.50f))
        // At the narrowest split both are at their minimums.
        assertEquals(MIN_LIST_WIDTH, listPaneWidth(SPLIT_WIDTH, 0.50f))
        assertEquals(MIN_LIST_WIDTH, listPaneWidth(SPLIT_WIDTH, 0.20f))
    }

    @Test
    fun `the right pane sits beside down to the width that leaves list and chat their minimums`() {
        assertEquals(956.dp, RIGHT_BESIDE_WIDTH)
        assertEquals(MIN_RIGHT_PANE_WIDTH, rightPaneBesideWidth(956.dp, 360.dp))
        assertNull(rightPaneBesideWidth(955.dp, 360.dp))
        assertNull(rightPaneBesideWidth(ExpandedThreshold, 280.dp))
    }

    @Test
    fun `the right pane keeps the owner's width where there is room, and gives it up first`() {
        assertEquals(360.dp, rightPaneBesideWidth(1400.dp, 360.dp))
        // 1100 leaves 1100 - 64 - 606 - 6 = 424 beside list and chat.
        assertEquals(424.dp, rightPaneBesideWidth(1100.dp, 600.dp))
        assertEquals(MIN_RIGHT_PANE_WIDTH, rightPaneBesideWidth(1400.dp, 100.dp))
        assertEquals(MAX_RIGHT_PANE_WIDTH, rightPaneBesideWidth(4000.dp, 2000.dp))
    }

    @Test
    fun `at every wide width the chat keeps its minimum, pane beside or not`() {
        for (w in 840..2600 step 7) {
            val window = w.dp
            for (fraction in listOf(0.2f, 0.3f, 0.5f)) {
                for (wanted in listOf(280.dp, 360.dp, 900.dp)) {
                    val right = rightPaneBesideWidth(window, wanted)
                    val main = window - RAIL_WIDTH - (right?.let { it + HANDLE_WIDTH } ?: 0.dp)
                    val chat = main - HANDLE_WIDTH - listPaneWidth(main, fraction)
                    assertTrue(chat >= MIN_CHAT_WIDTH, "window $window, fraction $fraction, pane $wanted: chat $chat")
                    assertTrue(listPaneWidth(main, fraction) >= MIN_LIST_WIDTH, "window $window: list")
                }
            }
        }
    }
}
