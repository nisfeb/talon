package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared tab row at the widths a desktop window passes through:
 * full Material tabs, tighter ones, smaller labels, then a dropdown.
 * At no width does a label wrap onto a second line, and in every form
 * but the dropdown no label is cut short either.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveTabRowTest {
    private val labels = listOf("Conversations", "Mentions", "Party lines")

    /** The label's own layout, through the semantics the Text exposes. */
    private fun ComposeUiTest.layoutOf(label: String): TextLayoutResult {
        val node = onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    /** Every label on screen, each on one line and whole. */
    private fun ComposeUiTest.allWhole(style: TextStyle, of: List<String> = labels) {
        for (label in of) {
            val layout = layoutOf(label)
            assertEquals(1, layout.lineCount, "$label wrapped")
            assertFalse(layout.multiParagraph.didExceedMaxLines, "$label wanted a second line")
            assertFalse(layout.isLineEllipsized(0), "$label ellipsized")
            // hasVisualOverflow is no use here: a one-line Text reports it
            // whenever its paragraph was laid out wider than the text.
            assertTrue(layout.multiParagraph.maxIntrinsicWidth <= layout.size.width + 0.5f, "$label cut short")
            assertEquals(style.fontSize, layout.layoutInput.style.fontSize, "$label size")
        }
    }

    @Test
    fun `full, then tight, then small labels, then a dropdown that picks the tab`() = runComposeUiTest {
        var width by mutableStateOf(2000.dp)
        var selected by mutableStateOf(0)
        var widestFull = 0.dp
        var widestSmall = 0.dp
        var full = TextStyle.Default
        var small = TextStyle.Default
        setContent {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            full = MaterialTheme.typography.titleSmall
            small = MaterialTheme.typography.labelMedium
            fun widest(style: TextStyle): Dp = labels.maxOf { with(density) { measurer.measure(it, style).size.width.toDp() } }
            widestFull = widest(full)
            widestSmall = widest(small)
            Box(Modifier.width(width)) {
                AdaptiveTabRow(labels, selected, onSelect = { selected = it })
            }
        }
        waitForIdle()
        val n = labels.size
        // What one tab needs in each form: label, padding both sides, 1dp slack.
        val needFull = widestFull + 33.dp
        val needTight = widestFull + 13.dp
        val needSmall = widestSmall + 13.dp
        assertTrue(needSmall < needTight, "the small style is narrower: $widestSmall vs $widestFull")

        width = needFull * n + 6.dp
        waitForIdle()
        allWhole(full)
        onNodeWithText("Conversations").assertIsSelected()
        onNodeWithText("Mentions").assertIsNotSelected()

        // Material's own tab would cut these labels short here.
        width = needTight * n + 6.dp
        assertTrue(width < needFull * n)
        waitForIdle()
        allWhole(full)
        onNodeWithText("Conversations").assertIsSelected()
        onNodeWithText("Mentions").assertIsNotSelected()

        width = needSmall * n + 3.dp
        assertTrue(width < needTight * n)
        waitForIdle()
        allWhole(small)

        width = needSmall * n - 6.dp
        waitForIdle()
        // The dropdown: only the current tab is on screen, on one line.
        assertEquals(1, onAllNodesWithText("Conversations").fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithText("Party lines").fetchSemanticsNodes().isEmpty())
        assertEquals(1, layoutOf("Conversations").lineCount)
        onNodeWithText("Conversations").performClick()
        // The menu marks the current tab.
        onNode(hasText("Conversations") and hasContentDescription("Current")).assertExists()
        onNode(hasText("Mentions") and hasContentDescription("Current")).assertDoesNotExist()
        onNodeWithText("Party lines").performClick()
        waitForIdle()
        assertEquals(2, selected)
        assertEquals(1, onAllNodesWithText("Party lines").fetchSemanticsNodes().size, "the menu closed, the row shows it")
        assertTrue(onAllNodesWithText("Conversations").fetchSemanticsNodes().isEmpty())

        // Wide again: all three tabs, and a click on one selects it.
        width = needFull * n + 6.dp
        waitForIdle()
        onNodeWithText("Mentions").performClick()
        assertEquals(1, selected)
    }

    @Test
    fun `however narrow, the dropdown's label stays on one line`() = runComposeUiTest {
        setContent {
            Box(Modifier.width(60.dp)) {
                AdaptiveTabRow(listOf("Notifications", "Appearance"), 0, onSelect = {}, scrollable = true)
            }
        }
        val layout = layoutOf("Notifications")
        assertEquals(1, layout.lineCount)
    }

    @Test
    fun `a scrollable row keeps scrolling where a fixed one would be a dropdown`() = runComposeUiTest {
        val many = listOf("Appearance", "Home", "Chats", "Notifications", "AI", "Orrery", "Calls", "Account", "About")
        setContent {
            Box(Modifier.width(360.dp)) {
                AdaptiveTabRow(many, 0, onSelect = {}, scrollable = true)
            }
        }
        // Every tab exists in the scrolling row; a dropdown would show only the first.
        for (label in many) {
            assertEquals(1, onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().size, label)
        }
        assertEquals(1, layoutOf("Notifications").lineCount)
        assertFalse(layoutOf("Notifications").isLineEllipsized(0))
    }

    @Test
    fun `a dot stays with its label in a tab and in the dropdown`() = runComposeUiTest {
        var width by mutableStateOf(600.dp)
        setContent {
            Box(Modifier.width(width)) {
                AdaptiveTabRow(listOf("Groups", "DMs", "Party lines"), 0, onSelect = {}, dots = setOf(2))
            }
        }
        // MenuBadgeDot says "Unread" to a screen reader.
        assertEquals(1, onAllNodes(androidx.compose.ui.test.hasContentDescription("Unread"), useUnmergedTree = true).fetchSemanticsNodes().size)
        width = 120.dp
        waitForIdle()
        // Folded into the dropdown, the dot shows on it so it is not lost.
        assertTrue(onAllNodesWithText("Party lines").fetchSemanticsNodes().isEmpty())
        assertEquals(1, onAllNodes(androidx.compose.ui.test.hasContentDescription("Unread"), useUnmergedTree = true).fetchSemanticsNodes().size)
        // And in the menu, on its own tab only.
        onNodeWithText("Groups").performClick()
        assertEquals(2, onAllNodes(androidx.compose.ui.test.hasContentDescription("Unread"), useUnmergedTree = true).fetchSemanticsNodes().size)
        onNode(hasText("Party lines") and hasContentDescription("Unread")).assertExists()
    }

    @Test
    fun `a dot takes room, so a label that fits only without it gets the next form`() = runComposeUiTest {
        // The widest label last, and dotted.
        val tabs = listOf("DMs", "Groups", "Party lines")
        var width by mutableStateOf(2000.dp)
        var label = 0.dp
        var full = TextStyle.Default
        setContent {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            full = MaterialTheme.typography.titleSmall
            label = with(density) { measurer.measure("Party lines", full).size.width.toDp() }
            Box(Modifier.width(width)) {
                AdaptiveTabRow(tabs, 0, onSelect = {}, dots = setOf(2))
            }
        }
        waitForIdle()
        // Room for the label and its dot in a full tab.
        width = (label + 14.dp + 33.dp) * 3 + 3.dp
        waitForIdle()
        allWhole(full, tabs)
        // Room for the label alone: the dot would push it short, so tighter.
        width = (label + 33.dp) * 3 + 3.dp
        waitForIdle()
        allWhole(full, tabs)
    }

    @Test
    fun `a selection past either end shows the nearest tab`() = runComposeUiTest {
        var selected by mutableStateOf(7)
        setContent {
            Box(Modifier.width(600.dp)) { AdaptiveTabRow(labels, selected, onSelect = {}) }
        }
        onNodeWithText("Party lines").assertIsSelected()
        selected = -1
        waitForIdle()
        onNodeWithText("Conversations").assertIsSelected()
    }
}
